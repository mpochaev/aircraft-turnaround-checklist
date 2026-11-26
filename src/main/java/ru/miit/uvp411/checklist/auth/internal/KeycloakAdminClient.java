package ru.miit.uvp411.checklist.auth.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import ru.miit.uvp411.checklist.shared.error.ApiException;

/** Server-side adapter for the Keycloak Admin REST API. */
@Component
public class KeycloakAdminClient {

    private static final ParameterizedTypeReference<List<JsonNode>> JSON_LIST = new ParameterizedTypeReference<>() {};
    private final RestClient http;
    private final String baseUrl;
    private final String realm;
    private final String username;
    private final String password;
    private volatile String token;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    KeycloakAdminClient(@Value("${KEYCLOAK_ADMIN_URL:http://localhost:8180}") String baseUrl,
                        @Value("${app.security.keycloak.realm}") String realm,
                        @Value("${app.security.keycloak.admin-username}") String username,
                        @Value("${app.security.keycloak.admin-password}") String password) {
        this.http = RestClient.builder().build();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.realm = realm;
        this.username = username;
        this.password = password;
    }

    public List<JsonNode> users() { return getList(adminPath("/users?first=0&max=1000")); }

    JsonNode user(String id) { return get(adminPath("/users/{id}", id), JsonNode.class); }

    List<JsonNode> findUsers(String login) {
        String uri = UriComponentsBuilder.fromUriString(adminPath("/users"))
                .queryParam("username", login).queryParam("exact", true).toUriString();
        return getList(uri);
    }

    String createUser(Map<String, Object> representation) {
        try {
            ResponseEntity<Void> response = http.post().uri(adminPath("/users"))
                    .headers(this::authorized).contentType(MediaType.APPLICATION_JSON)
                    .body(representation).retrieve().toBodilessEntity();
            String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
            if (location == null) throw new IllegalStateException("Keycloak did not return created user location");
            return location.substring(location.lastIndexOf('/') + 1);
        } catch (RestClientResponseException e) { throw translate(e); }
    }

    void updateUser(String id, Map<String, Object> representation) {
        put(adminPath("/users/{id}", id), representation);
    }

    void resetPassword(String id, String newPassword, boolean temporary) {
        put(adminPath("/users/{id}/reset-password", id),
                Map.of("type", "password", "value", newPassword, "temporary", temporary));
    }

    void logoutUser(String id) {
        try {
            http.post().uri(adminPath("/users/{id}/logout", id))
                    .headers(this::authorized).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) { throw translate(e); }
    }

    void unlock(String id) {
        try {
            http.delete().uri(adminPath("/attack-detection/brute-force/users/{id}", id))
                    .headers(this::authorized).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) { throw translate(e); }
    }

    List<JsonNode> userRoles(String id) {
        return getList(adminPath("/users/{id}/role-mappings/realm", id));
    }

    JsonNode bruteForceStatus(String id) {
        try {
            return get(adminPath("/attack-detection/brute-force/users/{id}", id), JsonNode.class);
        } catch (RuntimeException e) {
            if (e instanceof org.springframework.web.client.RestClientResponseException r && r.getStatusCode().value() == 404) return null;
            if (e instanceof ru.miit.uvp411.checklist.shared.error.ApiException a && a.status().value() == 404) return null;
            throw e;
        }
    }

    List<JsonNode> lastLoginEvents(String id) {
        String uri = UriComponentsBuilder.fromUriString(adminPath("/events"))
                .queryParam("type", "LOGIN").queryParam("user", id).queryParam("max", 1).queryParam("direction","desc")
                .toUriString();
        return getList(uri);
    }

    List<JsonNode> eventsSince(long epochMillis) {
        String uri = UriComponentsBuilder.fromUriString(adminPath("/events"))
                .queryParam("dateFrom", epochMillis).queryParam("first",0).queryParam("max",1000)
                .queryParam("direction","asc").toUriString();
        return getList(uri);
    }

    JsonNode role(String code) { return get(adminPath("/roles/{role}", code), JsonNode.class); }

    List<JsonNode> roles() { return getList(adminPath("/roles")); }

    void addUserRole(String userId, JsonNode role) {
        post(adminPath("/users/{id}/role-mappings/realm", userId), List.of(role));
    }

    void removeUserRole(String userId, JsonNode role) {
        try {
            http.method(HttpMethod.DELETE).uri(adminPath("/users/{id}/role-mappings/realm", userId))
                    .headers(this::authorized).contentType(MediaType.APPLICATION_JSON).body(List.of(role))
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) { throw translate(e); }
    }

    void createRole(String code, String name) {
        post(adminPath("/roles"), Map.of("name", code, "description", name));
    }

    void updateRole(String code, String name) {
        put(adminPath("/roles/{role}", code), Map.of("name", code, "description", name));
    }

    private <T> T get(String uri, Class<T> type) {
        try {
            return http.get().uri(uri).headers(this::authorized).retrieve().body(type);
        } catch (RestClientResponseException e) { throw translate(e); }
    }

    private List<JsonNode> getList(String uri) {
        try {
            return http.get().uri(uri).headers(this::authorized).retrieve().body(JSON_LIST);
        } catch (RestClientResponseException e) { throw translate(e); }
    }

    private void post(String uri, Object body) {
        try {
            http.post().uri(uri).headers(this::authorized).contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) { throw translate(e); }
    }

    private void put(String uri, Object body) {
        try {
            http.put().uri(uri).headers(this::authorized).contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) { throw translate(e); }
    }

    private void authorized(HttpHeaders headers) { headers.setBearerAuth(accessToken()); }

    private String adminPath(String path, Object... vars) {
        String uri = baseUrl + "/admin/realms/" + realm + path;
        return vars.length == 0 ? uri : UriComponentsBuilder.fromUriString(uri).buildAndExpand(vars).toUriString();
    }

    private String accessToken() {
        if (token != null && tokenExpiresAt.isAfter(Instant.now().plusSeconds(15))) return token;
        synchronized (this) {
            if (token != null && tokenExpiresAt.isAfter(Instant.now().plusSeconds(15))) return token;
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "password");
            form.add("client_id", "admin-cli");
            form.add("username", username);
            form.add("password", password);
            JsonNode response = http.post().uri(baseUrl + "/realms/master/protocol/openid-connect/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(JsonNode.class);
            token = response.path("access_token").asText();
            tokenExpiresAt = Instant.now().plusSeconds(response.path("expires_in").asLong(60));
            return token;
        }
    }

    private RuntimeException translate(RestClientResponseException e) {
        if (e.getStatusCode().value() == 404) return ApiException.notFound("Запись Keycloak");
        if (e.getStatusCode().value() == 409)
            return new ApiException(org.springframework.http.HttpStatus.CONFLICT, "DUPLICATE_CODE", "Логин или код уже используется");
        return new IllegalStateException("Keycloak Admin API error: HTTP " + e.getStatusCode().value(), e);
    }
}
