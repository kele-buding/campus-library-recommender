package com.campus.library.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record CheckinRequest(
        @NotNull(message = "loanId 不能为空") @Positive(message = "loanId 须为正整数") Long loanId,
        @NotNull(message = "requestId 不能为空") UUID requestId
) {
}
