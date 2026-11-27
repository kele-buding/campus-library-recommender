package com.campus.library.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record CheckoutRequest(
        @NotNull(message = "bookId 不能为空") @Positive(message = "bookId 须为正整数") Long bookId,
        Long studentId,
        @NotNull(message = "requestId 不能为空") UUID requestId
) {
}
