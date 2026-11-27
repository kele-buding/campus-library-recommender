package com.campus.library.dto;

import jakarta.validation.constraints.NotNull;

public record AccountStatusRequest(
        @NotNull(message = "isActive 不能为空") Boolean isActive
) {
}
