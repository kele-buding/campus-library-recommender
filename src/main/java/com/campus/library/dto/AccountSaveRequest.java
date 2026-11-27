package com.campus.library.dto;

import jakarta.validation.constraints.NotBlank;

public record AccountSaveRequest(
        @NotBlank(message = "登录名不能为空") String loginName,
        String password,
        @NotBlank(message = "角色不能为空") String role,
        Long majorId,
        String displayCode,
        Boolean isActive
) {
}
