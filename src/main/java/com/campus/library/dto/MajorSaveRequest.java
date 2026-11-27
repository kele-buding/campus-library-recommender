package com.campus.library.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MajorSaveRequest(
        @NotBlank(message = "专业简称不能为空")
        @Size(max = 32, message = "专业简称长度不能超过 32 个字符")
        String code,
        @NotBlank(message = "专业名称不能为空")
        @Size(max = 64, message = "专业名称长度不能超过 64 个字符")
        String name
) {
}
