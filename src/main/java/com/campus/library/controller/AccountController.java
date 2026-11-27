package com.campus.library.controller;

import com.campus.library.dto.AccountSaveRequest;
import com.campus.library.dto.AccountStatusRequest;
import com.campus.library.dto.MajorSaveRequest;
import com.campus.library.security.AuthInterceptor;
import com.campus.library.security.RequireAction;
import com.campus.library.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * DU-01 系统管理员账号与专业维护接口（仅 sysadmin 可访问）。
 */
@RestController
@RequestMapping("/api/admin")
@RequireAction(AuthService.Action.MANAGE_ACCOUNT)
public class AccountController {

    private final AuthService authService;

    public AccountController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/accounts")
    public List<Map<String, Object>> listAccounts(HttpServletRequest request) {
        return authService.listAccounts(AuthInterceptor.currentContext(request));
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createAccount(
            @Valid @RequestBody AccountSaveRequest req,
            HttpServletRequest request
    ) {
        return authService.saveAccount(AuthInterceptor.currentContext(request), null, req);
    }

    @PutMapping("/accounts/{userId}")
    public Map<String, Object> updateAccount(
            @PathVariable Long userId,
            @Valid @RequestBody AccountSaveRequest req,
            HttpServletRequest request
    ) {
        return authService.saveAccount(AuthInterceptor.currentContext(request), userId, req);
    }

    @PatchMapping("/accounts/{userId}/status")
    public Map<String, Object> updateAccountStatus(
            @PathVariable Long userId,
            @Valid @RequestBody AccountStatusRequest req,
            HttpServletRequest request
    ) {
        return authService.updateAccountStatus(AuthInterceptor.currentContext(request), userId, req.isActive());
    }

    @GetMapping("/majors")
    public List<Map<String, Object>> listMajors(HttpServletRequest request) {
        return authService.listMajors(AuthInterceptor.currentContext(request));
    }

    @PostMapping("/majors")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createMajor(
            @Valid @RequestBody MajorSaveRequest req,
            HttpServletRequest request
    ) {
        return authService.saveMajor(AuthInterceptor.currentContext(request), null, req);
    }

    @PutMapping("/majors/{majorId}")
    public Map<String, Object> updateMajor(
            @PathVariable Long majorId,
            @Valid @RequestBody MajorSaveRequest req,
            HttpServletRequest request
    ) {
        return authService.saveMajor(AuthInterceptor.currentContext(request), majorId, req);
    }
}
