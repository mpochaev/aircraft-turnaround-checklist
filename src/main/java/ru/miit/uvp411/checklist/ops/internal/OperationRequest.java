package ru.miit.uvp411.checklist.ops.internal;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;

import ru.miit.uvp411.checklist.shared.error.ApiException;

/** Раздел 7.7: действие определяется текущим и новым статусами, времена ставит сервер. */
public record OperationRequest(
        @Pattern(regexp = "not_started|in_progress|done|problem|not_required") String status,
        @Size(max = 500) String comment,
        @Positive Integer delayCodeId) {

    @AssertTrue(message = "Укажите status или delayCodeId")
    @JsonIgnore
    public boolean isChangeRequested() {
        return status != null || delayCodeId != null;
    }

    String requireComment() {
        if (comment == null || comment.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, OpsErrors.COMMENT_REQUIRED,
                    "Укажите комментарий");
        }
        return comment;
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Неизвестное поле: " + name);
    }
}
