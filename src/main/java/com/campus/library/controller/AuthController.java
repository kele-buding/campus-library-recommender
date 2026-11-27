package com.campus.library.controller;

import com.campus.library.dto.LoginRequest;
import com.campus.library.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DU-01 身份认证接口：登录、退出、当前会话校验。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest req, HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        AuthService.AuthContext ctx = authService.login(req.loginName(), req.password(), session);
        return toSessionView(ctx);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        authService.logout(request.getSession(false));
        return Map.of("status", "ok");
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        AuthService.AuthContext ctx = authService.requireAuthenticated(
                request.getSession(false),
                request.getHeader("X-Request-Id")
        );
        return toSessionView(ctx);
    }

    private Map<String, Object> toSessionView(AuthService.AuthContext ctx) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_id", ctx.actorId());
        body.put("login_name", ctx.loginName());
        body.put("role", ctx.role());
        body.put("major_id", ctx.majorId());
        body.put("display_code", ctx.displayCode());
        return body;
    }
}
