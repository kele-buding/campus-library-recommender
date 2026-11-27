package com.campus.library.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AccountSaveRequest(
        @NotBlank(message = "登录名不能为空")
        @Size(max = 64, message = "登录名长度不能超过 64 个字符")
        String loginName,
        @Size(max = 128, message = "密码长度不能超过 128 个字符")
        String password,
        @NotBlank(message = "角色不能为空")
        @Size(max = 32, message = "角色长度不能超过 32 个字符")
        String role,
        Long majorId,
        @Size(max = 32, message = "试验代号长度不能超过 32 个字符")
        String displayCode,
        Boolean isActive
) {
}
