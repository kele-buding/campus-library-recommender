package com.campus.library.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "登录名不能为空")
        @Size(max = 64, message = "登录名长度不能超过 64 个字符")
        String loginName,
        @NotBlank(message = "密码不能为空")
        @Size(max = 128, message = "密码长度不能超过 128 个字符")
        String password
) {
}
