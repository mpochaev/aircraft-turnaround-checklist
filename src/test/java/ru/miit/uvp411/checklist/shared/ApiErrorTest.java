package ru.miit.uvp411.checklist.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import ru.miit.uvp411.checklist.shared.error.ApiError;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Тело ошибки совпадает с разделом 2.4 спецификации API. */
class ApiErrorTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void jsonHasSpecShape() {
        ApiError e = ApiError.of("PREDECESSORS_NOT_DONE", "Сначала завершите: \"Высадка\"",
                List.of(new ApiError.Detail(null, 70052, "Высадка пассажиров не выполнена")), "c0a8-2f41");

        JsonNode node = json.readTree(e.toJson());

        assertThat(node.get("error").get("code").asString()).isEqualTo("PREDECESSORS_NOT_DONE");
        assertThat(node.get("error").get("message").asString()).isEqualTo("Сначала завершите: \"Высадка\"");
        assertThat(node.get("error").get("details").get(0).get("field").isNull()).isTrue();
        assertThat(node.get("error").get("details").get(0).get("value").asString()).isEqualTo("70052");
        assertThat(node.get("requestId").asString()).isEqualTo("c0a8-2f41");
    }

    @Test
    void emptyDetailsIsEmptyArray() {
        JsonNode node = json.readTree(ApiError.of("FORBIDDEN", "Недостаточно прав", null, null).toJson());
        assertThat(node.get("error").get("details").isArray()).isTrue();
        assertThat(node.get("error").get("details").size()).isZero();
        assertThat(node.get("requestId").isNull()).isTrue();
    }
}
