package com.campus.library.security;

import com.campus.library.service.AuthService;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明接口所需的 DU-01 角色白名单操作权限与可选的学生归属参数名。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAction {

    AuthService.Action value();

    /**
     * 若接口通过 Query 参数或 Path 变量接收目标学生 ID（如 studentId），
     * 拦截器将自动解析并在学生角色下执行本人归属校验（INT-01）。
     */
    String studentIdParam() default "";
}
