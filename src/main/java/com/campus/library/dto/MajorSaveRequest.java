package com.campus.library.dto;

import jakarta.validation.constraints.NotBlank;

public record MajorSaveRequest(
        @NotBlank(message = "专业简称不能为空") String code,
        @NotBlank(message = "专业名称不能为空") String name
) {
}
