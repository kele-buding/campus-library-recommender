package com.campus.library.security;

import com.campus.library.exception.ApiException;
import com.campus.library.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * DU-01 统一角色白名单与学生资源归属拦截器。
 *
 * 每次受保护请求均经过 AuthService.authorize：
 * 1. 从数据库重读账号激活状态（停用后旧会话立即拒绝）；
 * 2. 校验角色白名单（Action -> Role）；
 * 3. 若声明了 studentIdParam，校验学生仅可访问本人资源。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_AUTH_CONTEXT = "AUTH_CONTEXT";

    private final AuthService authService;

    public AuthInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequireAction requireAction = method.getMethodAnnotation(RequireAction.class);
        if (requireAction == null) {
            requireAction = method.getBeanType().getAnnotation(RequireAction.class);
        }
        if (requireAction == null) {
            return true;
        }

        Long targetStudentId = resolveStudentIdParam(request, requireAction.studentIdParam());
        String requestId = request.getHeader("X-Request-Id");
        AuthService.AuthContext ctx = authService.authorize(
                request.getSession(false),
                requireAction.value(),
                targetStudentId,
                requestId
        );
        request.setAttribute(ATTR_AUTH_CONTEXT, ctx);
        return true;
    }

    public static AuthService.AuthContext currentContext(HttpServletRequest request) {
        Object attr = request.getAttribute(ATTR_AUTH_CONTEXT);
        if (attr instanceof AuthService.AuthContext ctx) {
            return ctx;
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录");
    }

    @SuppressWarnings("unchecked")
    private Long resolveStudentIdParam(HttpServletRequest request, String paramName) {
        if (paramName == null || paramName.isBlank()) {
            return null;
        }
        String raw = request.getParameter(paramName);
        if (raw == null || raw.isBlank()) {
            Object pathVars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
            if (pathVars instanceof Map<?, ?> map && map.get(paramName) != null) {
                raw = map.get(paramName).toString();
            }
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            if (parsed <= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", paramName + " 须为正整数");
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", paramName + " 须为正整数");
        }
    }
}
