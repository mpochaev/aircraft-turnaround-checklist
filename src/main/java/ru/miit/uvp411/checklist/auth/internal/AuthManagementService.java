package ru.miit.uvp411.checklist.auth.internal;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.miit.uvp411.checklist.auth.AuthApi;
import ru.miit.uvp411.checklist.log.AuditLog;
import ru.miit.uvp411.checklist.shared.error.ApiException;

@Service
public class AuthManagementService {

    private static final Pattern ROLE_CODE = Pattern.compile("[a-z_]{3,30}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%".toCharArray();
    private final JdbcClient jdbc;
    private final KeycloakAdminClient keycloak;
    private final DbPermissionResolver permissions;
    private final AuditLog audit;
    private final ObjectMapper mapper;

    AuthManagementService(JdbcClient jdbc, KeycloakAdminClient keycloak, DbPermissionResolver permissions,
                          AuditLog audit, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.keycloak = keycloak;
        this.permissions = permissions;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Transactional
    public Map<String, Object> listUsers(String role, Boolean active, int page, int size) {
        var kcUsers = keycloak.users();
        List<Map<String, Object>> items = new ArrayList<>();
        for (JsonNode kcUser : kcUsers) {
            String login = kcUser.path("username").asText();
            if (login.isBlank()) continue;
            boolean isActive = kcUser.path("enabled").asBoolean(true);
            syncUser(kcUser);
            List<String> roleCodes = currentRoleCodes(kcUser.path("id").asText());
            if (role != null && !roleCodes.contains(role)) continue;
            if (active != null && active != isActive) continue;
            Map<String, Object> user = userView(kcUser, roleCodes);
            items.add(user);
        }
        int from = Math.min((page - 1) * size, items.size());
        int to = Math.min(from + size, items.size());
        return Map.of("items", items.subList(from, to), "total", items.size());
    }

    @Transactional
    public Map<String, Object> createUser(UserInput input) {
        String login = required(input.login(), "login", 3, 50);
        if (!login.matches("[a-z0-9._]{3,50}")) throw validation("login", login, "Логин: 3–50 строчных латинских букв, цифр, точки или подчёркивания");
        if (!keycloak.findUsers(login).isEmpty()) throw conflict("LOGIN_TAKEN", "Логин уже занят");
        validateName(input.lastName(), "lastName", true);
        validateName(input.firstName(), "firstName", true);
        validateName(input.middleName(), "middleName", false);
        List<Role> roles = rolesByIds(input.roleIds());
        String tempPassword = temporaryPassword();
        Map<String, Object> rep = new LinkedHashMap<>();
        rep.put("username", login);
        rep.put("enabled", true);
        rep.put("firstName", input.firstName().trim());
        rep.put("lastName", input.lastName().trim());
        if (input.middleName() != null && !input.middleName().isBlank()) rep.put("attributes", Map.of("middleName", List.of(input.middleName().trim())));
        rep.put("credentials", List.of(Map.of("type", "password", "value", tempPassword, "temporary", true)));
        String keycloakId = keycloak.createUser(rep);
        for (Role role : roles) keycloak.addUserRole(keycloakId, keycloak.role(role.code()));
        JsonNode created = keycloak.user(keycloakId);
        int dbId = syncUser(created);
        Map<String, Object> after = Map.of("login", login, "roleIds", input.roleIds(), "isActive", true);
        audit.record(new AuditLog.Entry("user_created", "user", (long) dbId, null, null, after));
        return Map.of("user", userView(created, roles.stream().map(Role::code).toList()), "temporaryPassword", tempPassword);
    }

    @Transactional
    public Map<String, Object> updateUser(int id, UserPatch patch, String actor) {
        DbUser before = dbUser(id);
        JsonNode current = findKeycloakUser(before);
        if (patch.lastName() != null) validateName(patch.lastName(), "lastName", true);
        if (patch.firstName() != null) validateName(patch.firstName(), "firstName", true);
        if (patch.middleName() != null) validateName(patch.middleName(), "middleName", false);
        List<Role> newRoles = patch.roleIds() == null ? null : rolesByIds(patch.roleIds());
        boolean selfSupervisorRemoval = actor.equals(before.login()) &&
                ((Boolean.FALSE.equals(patch.isActive())) || (newRoles != null && newRoles.stream().noneMatch(r -> r.code().equals("supervisor"))));
        if (selfSupervisorRemoval) throw conflict("SELF_RESTRICTION", "Нельзя отключить себя или снять с себя роль супервайзера");

        Map<String, Object> rep = mapper.convertValue(current, LinkedHashMap.class);
        if (patch.lastName() != null) rep.put("lastName", patch.lastName().trim());
        if (patch.firstName() != null) rep.put("firstName", patch.firstName().trim());
        if (patch.isActive() != null) rep.put("enabled", patch.isActive());
        String keycloakId = current.path("id").asText();
        keycloak.updateUser(keycloakId, rep);
        if (patch.middleName() != null) {
            Map<String, Object> attrs = new LinkedHashMap<>();
            JsonNode oldAttrs = current.path("attributes");
            if (oldAttrs.isObject()) oldAttrs.properties().forEach(e -> attrs.put(e.getKey(), mapper.convertValue(e.getValue(), List.class)));
            if (patch.middleName().isBlank()) attrs.remove("middleName");
            else attrs.put("middleName", List.of(patch.middleName().trim()));
            rep.put("attributes", attrs);
            keycloak.updateUser(keycloakId, rep);
        }
        if (newRoles != null) replaceRoles(keycloakId, newRoles);
        String tempPassword = null;
        if (Boolean.TRUE.equals(patch.resetPassword())) {
            tempPassword = temporaryPassword();
            keycloak.resetPassword(keycloakId, tempPassword, true);
            keycloak.logoutUser(keycloakId);
        }
        if (Boolean.TRUE.equals(patch.unlock())) keycloak.unlock(keycloakId);
        if (Boolean.FALSE.equals(patch.isActive())) keycloak.logoutUser(keycloakId);
        JsonNode updated = keycloak.user(keycloakId);
        syncUser(updated);
        List<String> roleCodes = newRoles == null
                ? currentRoleCodes(keycloakId)
                : newRoles.stream().map(Role::code).toList();
        Map<String, Object> oldValue = new LinkedHashMap<>();
        oldValue.put("lastName", current.path("lastName").asText());
        oldValue.put("firstName", current.path("firstName").asText());
        oldValue.put("middleName", current.path("attributes").path("middleName").path(0).asText(null));
        oldValue.put("roleCodes", currentRoleCodes(keycloakId));
        oldValue.put("isActive", current.path("enabled").asBoolean(true));
        Map<String, Object> newValue = new LinkedHashMap<>();
        if (patch.lastName() != null) newValue.put("lastName", patch.lastName().trim());
        if (patch.firstName() != null) newValue.put("firstName", patch.firstName().trim());
        if (patch.middleName() != null) newValue.put("middleName", patch.middleName().trim());
        if (patch.roleIds() != null) newValue.put("roleIds", patch.roleIds());
        if (patch.isActive() != null) newValue.put("isActive", patch.isActive());
        if (Boolean.TRUE.equals(patch.unlock())) newValue.put("unlock", true);
        if (Boolean.TRUE.equals(patch.resetPassword())) newValue.put("resetPassword", true);
        if (!newValue.isEmpty()) audit.record(new AuditLog.Entry("user_changed", "user", (long) id, null, oldValue, newValue));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user", userView(updated, roleCodes));
        result.put("temporaryPassword", tempPassword);
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRoles() {
        List<Map<String, Object>> result = new ArrayList<>();
        List<JsonNode> kcRoles = keycloak.roles();
        Map<String,Long> roleCounts = new java.util.HashMap<>();
        for (JsonNode user : keycloak.users()) {
            for (JsonNode assigned : keycloak.userRoles(user.path("id").asText())) {
                String code = assigned.path("name").asText();
                if (roleByCode(code).isPresent()) roleCounts.merge(code,1L,Long::sum);
            }
        }
        for (JsonNode role : kcRoles) {
            String code = role.path("name").asText();
            jdbc.sql("select id, name, is_system from auth.role where code = :code").param("code", code)
                    .query((rs, n) -> {
                        List<String> codes = jdbc.sql("""
                                select p.code from auth.role r join auth.role_permission rp on rp.role_id=r.id
                                join auth.permission p on p.id=rp.permission_id where r.code=:code order by p.code
                                """).param("code", code).query(String.class).list();
                        long count = roleCounts.getOrDefault(code,0L);
                        return Map.<String, Object>of("id", rs.getInt("id"), "code", code, "name", rs.getString("name"),
                                "isSystem", rs.getBoolean("is_system"), "usersCount", count, "permissions", codes);
                    }).optional().ifPresent(result::add);
        }
        return result;
    }

    @Transactional
    public Map<String, Object> createRole(RoleInput input) {
        String code = required(input.code(), "code", 3, 30);
        String name = required(input.name(), "name", 1, 50);
        if (!ROLE_CODE.matcher(code).matches()) throw validation("code", code, "Код роли: 3–30 символов a–z и _");
        if (roleExists(code)) throw conflict("DUPLICATE_CODE", "Код роли занят");
        validatePermissionCodes(input.permissionCodes());
        jdbc.sql("insert into auth.role (code,name,is_system) values (:code,:name,false)")
                .param("code", code).param("name", name).update();
        replacePermissionCodes(code, input.permissionCodes());
        keycloak.createRole(code, name);
        permissions.evictAll();
        int id = roleId(code);
        audit.record(new AuditLog.Entry("role_created", "role", (long) id, null, null,
                Map.of("code", code, "name", name, "permissionCodes", input.permissionCodes())));
        return roleView(id);
    }

    @Transactional
    public Map<String, Object> updateRole(int id, RolePatch patch) {
        Role before = roleById(id);
        String newName = patch.name() == null ? before.name() : required(patch.name(), "name", 1, 50);
        if (before.system() && !before.name().equals(newName)) throw conflict("SYSTEM_ROLE", "Базовую роль нельзя переименовать");
        if (patch.permissionCodes() != null) {
            validatePermissionCodes(patch.permissionCodes());
            if ("supervisor".equals(before.code()) && !patch.permissionCodes().contains("roles.edit"))
                throw conflict("SYSTEM_ROLE", "Нельзя забрать у супервайзера право roles.edit");
        }
        if (patch.name() != null) {
            jdbc.sql("update auth.role set name=:name where id=:id").param("name", newName).param("id", id).update();
            keycloak.updateRole(before.code(), newName);
        }
        if (patch.permissionCodes() != null) replacePermissionCodes(before.code(), patch.permissionCodes());
        if (!before.name().equals(newName) || patch.permissionCodes() != null) {
            permissions.evictAll();
            audit.record(new AuditLog.Entry("role_changed", "role", (long) id, null,
                    Map.of("name", before.name()), Map.of("name", newName, "permissionCodes",
                            patch.permissionCodes() == null ? permissionCodes(before.code()) : patch.permissionCodes())));
        }
        return roleView(id);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listPermissions() {
        return jdbc.sql("select code, http_method, path, description from auth.permission order by code")
                .query((rs, n) -> Map.<String, Object>of("code", rs.getString("code"),
                        "httpMethod", rs.getString("http_method"), "path", rs.getString("path"),
                        "description", rs.getString("description"))).list();
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> pendingRequests(String login) {
        return jdbc.sql("""
                select id,kind,status,created_at from auth.change_request
                where user_id=(select id from auth.app_user where login=:login) and status='pending'
                order by created_at desc
                """).param("login",login)
                .query((rs,n)->Map.<String,Object>of("id",rs.getLong("id"),"kind",rs.getString("kind"),
                        "status",rs.getString("status"),"createdAt",rs.getTimestamp("created_at").toLocalDateTime())).list();
    }

    @Transactional
    public Map<String, Object> createPersonalRequest(String login, PersonalRequest request) {
        int userId = userId(login);
        Map<String, Object> newData = new LinkedHashMap<>();
        if (request.lastName() != null) { validateName(request.lastName(), "lastName", true); newData.put("lastName", request.lastName().trim()); }
        if (request.firstName() != null) { validateName(request.firstName(), "firstName", true); newData.put("firstName", request.firstName().trim()); }
        if (request.middleName() != null) { validateName(request.middleName(), "middleName", false); newData.put("middleName", request.middleName().isBlank() ? null : request.middleName().trim()); }
        if (newData.isEmpty()) throw validation("newData", null, "Укажите хотя бы одно новое поле");
        String reason = required(request.reason(), "reason", 1, 300);
        return createRequest(userId, "personal_data", newData, reason);
    }

    @Transactional
    public Map<String, Object> createPasswordRequest(PasswordRequest request) {
        String login = required(request.login(), "login", 3, 50);
        List<JsonNode> users = keycloak.findUsers(login);
        if (users.isEmpty() || !users.getFirst().path("enabled").asBoolean(true)) {
            return passwordResetAccepted();
        }
        int userId = syncUser(users.getFirst());
        var previous = org.springframework.security.core.context.SecurityContextHolder.getContext();
        var anonymousRequestActor = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                login, null, List.of());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(anonymousRequestActor);
        try {
            createPasswordResetRequest(userId, request.reason() == null ? "" : request.reason().trim());
            return passwordResetAccepted();
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.setContext(previous);
        }
    }

    private void createPasswordResetRequest(int userId, String reason) {
        Long id = jdbc.sql("""
                insert into auth.change_request (user_id,kind,new_data,reason)
                values (:uid,'password_reset',null,:reason)
                on conflict (user_id,kind) where status='pending' do nothing
                returning id
                """).param("uid", userId).param("reason", reason.isBlank() ? null : reason)
                .query(Long.class).optional().orElse(null);
        if (id != null) {
            audit.record(new AuditLog.Entry("request_created", "change_request", id, null, null,
                    Map.of("kind", "password_reset", "reason", reason)));
        }
    }

    private static Map<String, Object> passwordResetAccepted() {
        return Map.of("status", "pending", "message",
                "Если учётная запись существует, заявка направлена супервайзеру");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> listRequests(String status, String kind, int page, int size) {
        String filterStatus = status == null ? "pending" : status;
        if (!Set.of("pending", "approved", "rejected").contains(filterStatus))
            throw validation("status", status, "Неизвестный статус заявки");
        if (kind != null && !Set.of("personal_data", "password_reset").contains(kind))
            throw validation("kind", kind, "Неизвестный вид заявки");
        var query = jdbc.sql("""
                select cr.id, cr.kind, cr.status, cr.new_data, cr.reason, cr.created_at, cr.review_comment,
                       u.id user_id, u.login, u.last_name, u.first_name, u.middle_name
                from auth.change_request cr join auth.app_user u on u.id=cr.user_id
                where cr.status=:status and (cast(:kind as varchar) is null or cr.kind=:kind)
                order by cr.created_at desc, cr.id desc limit :limit offset :offset
                """).param("status", filterStatus).param("kind", kind).param("limit", size).param("offset", (page - 1) * size);
        List<Map<String, Object>> items = query.query((rs, n) -> requestView(rs, mapper)).list();
        long total = jdbc.sql("select count(*) from auth.change_request where status=:status and (cast(:kind as varchar) is null or kind=:kind)")
                .param("status", filterStatus).param("kind", kind).query(Long.class).single();
        return Map.of("items", items, "total", total);
    }

    @Transactional
    public Map<String, Object> reviewRequest(long id, ReviewRequest request, String reviewerLogin) {
        if (!Set.of("approved", "rejected").contains(request.status()))
            throw validation("status", request.status(), "Статус должен быть approved или rejected");
        if ("rejected".equals(request.status()) && (request.comment() == null || request.comment().trim().isEmpty()))
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"COMMENT_REQUIRED","Для отклонения заявки укажите комментарий");
        if (request.comment() != null && request.comment().trim().length() > 300)
            throw validation("comment",request.comment(),"Не более 300 символов");
        RequestRow row = jdbc.sql("""
                select cr.id, cr.user_id, cr.kind, cr.status, cr.new_data, u.login, u.keycloak_id,
                       u.last_name, u.first_name, u.middle_name
                from auth.change_request cr join auth.app_user u on u.id=cr.user_id where cr.id=:id for update
                """).param("id", id).query((rs, n) -> new RequestRow(rs.getLong("id"), rs.getInt("user_id"),
                rs.getString("kind"), rs.getString("status"), rs.getString("new_data"), rs.getString("login"),
                rs.getString("keycloak_id"), rs.getString("last_name"), rs.getString("first_name"), rs.getString("middle_name"))).optional()
                .orElseThrow(() -> ApiException.notFound("Заявка"));
        if (!"pending".equals(row.status())) throw conflict("ALREADY_REVIEWED", "По заявке уже есть решение");
        String temporaryPassword = null;
        if ("approved".equals(request.status())) {
            JsonNode kcUser = findKeycloakUser(new DbUser(row.userId(), row.login(), row.keycloakId(),
                    row.lastName(), row.firstName(), row.middleName()));
            String kcId = kcUser.path("id").asText();
            if ("personal_data".equals(row.kind())) {
                Map<String, Object> data = jsonMap(row.newData());
                jdbc.sql("""
                        update auth.app_user set last_name=coalesce(:lastName,last_name),
                          first_name=coalesce(:firstName,first_name),
                          middle_name=case when :hasMiddle then :middleName else middle_name end
                        where id=:id
                        """).param("lastName", data.get("lastName")).param("firstName", data.get("firstName"))
                        .param("middleName", data.get("middleName")).param("hasMiddle", data.containsKey("middleName"))
                        .param("id", row.userId()).update();
                Map<String, Object> rep = mapper.convertValue(kcUser, LinkedHashMap.class);
                if (data.containsKey("lastName")) rep.put("lastName", data.get("lastName"));
                if (data.containsKey("firstName")) rep.put("firstName", data.get("firstName"));
                keycloak.updateUser(kcId, rep);
            } else {
                temporaryPassword = temporaryPassword();
                keycloak.resetPassword(kcId, temporaryPassword, true);
                keycloak.logoutUser(kcId);
            }
        }
        LocalDateTime reviewedAt = jdbc.sql("""
                update auth.change_request set status=:status, reviewed_by=:reviewer, reviewed_at=localtimestamp,
                  review_comment=:comment where id=:id
                returning reviewed_at
                """).param("status", request.status()).param("reviewer", userId(reviewerLogin))
                .param("comment", request.comment()).param("id", id).query(LocalDateTime.class).single();
        audit.record(new AuditLog.Entry("request_reviewed", "change_request", id, null,
                Map.of("status", "pending"), Map.of("status", request.status(), "comment", request.comment() == null ? "" : request.comment())));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("request", Map.of("id", id, "status", request.status(), "reviewedAt", reviewedAt,
                "reviewedBy", Map.of("id", userId(reviewerLogin), "fullName", fullName(reviewerLogin))));
        result.put("temporaryPassword", temporaryPassword);
        return result;
    }

    private Map<String, Object> createRequest(int userId, String kind, Map<String, Object> newData, String reason) {
        try {
            Long id = jdbc.sql("""
                    insert into auth.change_request (user_id,kind,new_data,reason)
                    values (:uid,:kind,cast(:data as jsonb),:reason) returning id
                    """).param("uid", userId).param("kind", kind)
                    .param("data", newData == null ? null : mapper.writeValueAsString(newData))
                    .param("reason", reason.isBlank() ? null : reason).query(Long.class).single();
            audit.record(new AuditLog.Entry("request_created", "change_request", id, null, null,
                    Map.of("kind", kind, "reason", reason)));
            return Map.of("id", id, "status", "pending", "message", "Заявка отправлена супервайзеру");
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw conflict("REQUEST_EXISTS", "У вас уже есть открытая заявка такого вида");
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    private int syncUser(JsonNode kc) {
        String login = kc.path("username").asText();
        String middleName = kc.path("attributes").path("middleName").isArray()
                ? kc.path("attributes").path("middleName").path(0).asText(null) : null;
        Integer id = jdbc.sql("""
                insert into auth.app_user (login,last_name,first_name,middle_name,is_active,keycloak_id)
                values (:login,:last,:first,:middle,:active,:kid)
                on conflict (login) do update set last_name=excluded.last_name, first_name=excluded.first_name,
                middle_name=excluded.middle_name, is_active=excluded.is_active, keycloak_id=excluded.keycloak_id
                returning id
                """).param("login", login).param("last", kc.path("lastName").asText(""))
                .param("first", kc.path("firstName").asText("")).param("middle", middleName)
                .param("active", kc.path("enabled").asBoolean(true)).param("kid", kc.path("id").asText())
                .query(Integer.class).single();
        return id;
    }

    private Map<String, Object> userView(JsonNode kc, List<String> roleCodes) {
        String login = kc.path("username").asText();
        Integer id = jdbc.sql("select id from auth.app_user where login=:login").param("login", login).query(Integer.class).single();
        JsonNode attrs = kc.path("attributes");
        String middle = attrs.path("middleName").isArray() ? attrs.path("middleName").path(0).asText(null) : null;
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", id); user.put("login", login);
        user.put("firstName", kc.path("firstName").asText()); user.put("lastName", kc.path("lastName").asText());
        user.put("middleName", middle);
        user.put("fullName", (kc.path("lastName").asText("") + " " + kc.path("firstName").asText("") + " " + (middle == null ? "" : middle)).trim());
        user.put("shortName", (kc.path("lastName").asText("") + " " +
                (kc.path("firstName").asText().isEmpty() ? "" : kc.path("firstName").asText().substring(0, 1) + ".")).trim());
        user.put("roles", roleCodes.stream().map(code -> roleByCode(code).map(AuthManagementService::roleMap)
                .orElse(Map.of("id", 0, "code", code, "name", code))).toList());
        user.put("isActive", kc.path("enabled").asBoolean(true));
        JsonNode attack = keycloak.bruteForceStatus(kc.path("id").asText());
        long unlockAt = attack == null ? 0 : attack.path("failedLoginNotBefore").asLong();
        user.put("lockedUntil", unlockAt > java.time.Instant.now().getEpochSecond()
                ? java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(unlockAt), java.time.ZoneId.systemDefault()) : null);
        List<JsonNode> events = keycloak.lastLoginEvents(kc.path("id").asText());
        user.put("lastLoginAt", events == null || events.isEmpty() || events.getFirst().path("time").isMissingNode() ? null
                : java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(events.getFirst().path("time").asLong()), java.time.ZoneId.systemDefault()));
        return user;
    }

    private static Map<String, Object> roleMap(Role role) {
        return Map.of("id", role.id(), "code", role.code(), "name", role.name());
    }

    private static Map<String, Object> requestView(java.sql.ResultSet rs, ObjectMapper mapper) throws java.sql.SQLException {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", rs.getLong("id")); view.put("kind", rs.getString("kind")); view.put("status", rs.getString("status"));
        view.put("user", Map.of("id", rs.getInt("user_id"), "fullName",
                (rs.getString("last_name") + " " + rs.getString("first_name") + " " +
                        (rs.getString("middle_name") == null ? "" : rs.getString("middle_name"))).trim()));
        Map<String,Object> current = new LinkedHashMap<>();
        current.put("lastName",rs.getString("last_name"));current.put("firstName",rs.getString("first_name"));
        current.put("middleName",rs.getString("middle_name"));
        view.put("currentData",current);
        try { view.put("newData", mapper.readValue(rs.getString("new_data"), Map.class)); }
        catch (Exception e) { view.put("newData", Map.of()); }
        view.put("reason", rs.getString("reason")); view.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
        view.put("reviewComment", rs.getString("review_comment"));
        return view;
    }

    private DbUser dbUser(int id) {
        return jdbc.sql("select id,login,keycloak_id,last_name,first_name,middle_name from auth.app_user where id=:id")
                .param("id", id).query((rs,n) -> new DbUser(rs.getInt("id"), rs.getString("login"), rs.getString("keycloak_id"),
                        rs.getString("last_name"), rs.getString("first_name"), rs.getString("middle_name")))
                .optional().orElseThrow(() -> ApiException.notFound("Пользователь"));
    }

    private JsonNode findKeycloakUser(DbUser db) {
        if (db.keycloakId() != null) return keycloak.user(db.keycloakId());
        List<JsonNode> found = keycloak.findUsers(db.login());
        if (found.isEmpty()) throw ApiException.notFound("Пользователь Keycloak");
        jdbc.sql("update auth.app_user set keycloak_id=:kid where id=:id")
                .param("kid", found.getFirst().path("id").asText()).param("id", db.id()).update();
        return found.getFirst();
    }

    private int userId(String login) {
        return jdbc.sql("select id from auth.app_user where login=:login").param("login", login)
                .query(Integer.class).optional().orElseThrow(() -> ApiException.notFound("Пользователь"));
    }

    private String fullName(String login) {
        return jdbc.sql("select trim(last_name || ' ' || first_name || ' ' || coalesce(middle_name,'')) from auth.app_user where login=:login")
                .param("login", login).query(String.class).optional().orElse(login);
    }

    private List<Role> rolesByIds(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) throw validation("roleIds", ids, "Назначьте хотя бы одну роль");
        List<Role> found = jdbc.sql("select id,code,name,is_system from auth.role where id in (:ids)")
                .param("ids", ids).query((rs,n) -> new Role(rs.getInt("id"),rs.getString("code"),rs.getString("name"),rs.getBoolean("is_system"))).list();
        if (found.size() != ids.stream().distinct().count()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"ROLE_NOT_FOUND","Роль не найдена");
        return found;
    }

    private void replaceRoles(String userId, List<Role> newRoles) {
        List<JsonNode> current = keycloak.userRoles(userId).stream()
                .filter(r -> roleByCode(r.path("name").asText()).isPresent()).toList();
        Set<String> target = newRoles.stream().map(Role::code).collect(java.util.stream.Collectors.toSet());
        for (JsonNode role : current) if (!target.contains(role.path("name").asText())) keycloak.removeUserRole(userId, role);
        Set<String> have = current.stream().map(r -> r.path("name").asText()).collect(java.util.stream.Collectors.toSet());
        for (Role role : newRoles) if (!have.contains(role.code())) keycloak.addUserRole(userId,keycloak.role(role.code()));
    }

    private List<String> currentRoleCodes(String userId) {
        return keycloak.userRoles(userId).stream().map(r -> r.path("name").asText())
                .filter(code -> roleByCode(code).isPresent()).toList();
    }

    private void replacePermissionCodes(String role, List<String> codes) {
        jdbc.sql("delete from auth.role_permission where role_id=(select id from auth.role where code=:code)").param("code",role).update();
        if (codes == null || codes.isEmpty()) return;
        jdbc.sql("""
                insert into auth.role_permission(role_id,permission_id)
                select r.id,p.id from auth.role r cross join auth.permission p
                where r.code=:role and p.code in (:codes)
                """).param("role",role).param("codes",codes).update();
    }

    private void validatePermissionCodes(List<String> codes) {
        if (codes == null) throw validation("permissionCodes", null, "Укажите permissionCodes");
        long count = codes.isEmpty() ? 0 : jdbc.sql("select count(*) from auth.permission where code in (:codes)")
                .param("codes",codes).query(Long.class).single();
        if (count != codes.stream().distinct().count())
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"PERMISSION_NOT_FOUND","Неизвестное право");
    }

    private Map<String, Object> roleView(int id) {
        return listRoles().stream().filter(r -> ((Number)r.get("id")).intValue() == id).findFirst()
                .orElseThrow(() -> ApiException.notFound("Роль"));
    }

    private List<String> permissionCodes(String code) {
        return jdbc.sql("select p.code from auth.role r join auth.role_permission rp on rp.role_id=r.id join auth.permission p on p.id=rp.permission_id where r.code=:code order by p.code")
                .param("code",code).query(String.class).list();
    }

    private boolean roleExists(String code) {
        return jdbc.sql("select count(*) from auth.role where code=:code").param("code",code).query(Long.class).single() > 0;
    }

    private int roleId(String code) {
        return jdbc.sql("select id from auth.role where code=:code").param("code",code).query(Integer.class).single();
    }

    private Role roleById(int id) {
        return jdbc.sql("select id,code,name,is_system from auth.role where id=:id").param("id",id)
                .query((rs,n)->new Role(rs.getInt("id"),rs.getString("code"),rs.getString("name"),rs.getBoolean("is_system")))
                .optional().orElseThrow(()->ApiException.notFound("Роль"));
    }

    private java.util.Optional<Role> roleByCode(String code) {
        return jdbc.sql("select id,code,name,is_system from auth.role where code=:code").param("code",code)
                .query((rs,n)->new Role(rs.getInt("id"),rs.getString("code"),rs.getString("name"),rs.getBoolean("is_system"))).optional();
    }

    private static String temporaryPassword() {
        StringBuilder value = new StringBuilder(16).append((char) ('0' + RANDOM.nextInt(10)));
        for (int i=1;i<16;i++) value.append(PASSWORD_CHARS[RANDOM.nextInt(PASSWORD_CHARS.length)]);
        for (int i=value.length()-1;i>0;i--) { int j=RANDOM.nextInt(i+1); char c=value.charAt(i); value.setCharAt(i,value.charAt(j)); value.setCharAt(j,c); }
        return value.toString();
    }

    private static String required(String value, String field, int min, int max) {
        if (value == null || value.trim().length() < min || value.trim().length() > max)
            throw validation(field, value, "Длина поля должна быть от " + min + " до " + max + " символов");
        return value.trim();
    }

    private static void validateName(String value, String field, boolean required) {
        if (value == null) { if (required) throw validation(field,null,"Поле обязательно"); return; }
        if (required && value.trim().isEmpty()) throw validation(field,value,"Поле обязательно");
        if (value.trim().length() > 50) throw validation(field,value,"Не более 50 символов");
    }

    private static ApiException validation(String field, Object value, String message) {
        return ApiException.validation(field,value,message);
    }

    private static ApiException conflict(String code,String message) {
        return new ApiException(HttpStatus.CONFLICT,code,message);
    }

    private Map<String,Object> roleViewFrom(Role r) {
        return Map.of("id",r.id(),"code",r.code(),"name",r.name(),"isSystem",r.system(),"permissions",permissionCodes(r.code()));
    }

    private Map<String,Object> jsonMap(String json) {
        try { return mapper.readValue(json,Map.class); } catch(Exception e) { return Map.of(); }
    }

    public record UserInput(String login,String lastName,String firstName,String middleName,List<Integer> roleIds) {}
    public record UserPatch(String lastName,String firstName,String middleName,List<Integer> roleIds,Boolean isActive,Boolean resetPassword,Boolean unlock) {}
    public record RoleInput(String code,String name,List<String> permissionCodes) {}
    public record RolePatch(String name,List<String> permissionCodes) {}
    public record PersonalRequest(String lastName,String firstName,String middleName,String reason) {}
    public record PasswordRequest(String login,String reason) {}
    public record ReviewRequest(String status,String comment) {}
    private record Role(int id,String code,String name,boolean system) {}
    private record DbUser(int id,String login,String keycloakId,String lastName,String firstName,String middleName) {}
    private record RequestRow(long id,int userId,String kind,String status,String newData,String login,String keycloakId,String lastName,String firstName,String middleName) {}
}
