package com.campus.library.controller;

import com.campus.library.security.RequireAction;
import com.campus.library.service.AuthService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 采购建议访问鉴权入口（Week 1 仅完成 DU-01 角色白名单拦截，完整云端采购建议由角色 B 在 Week 2 实现）。
 */
@RestController
@RequestMapping("/api/purchase")
public class PurchaseAccessController {

    @GetMapping("/suggestions")
    @RequireAction(AuthService.Action.VIEW_PURCHASE)
    public Map<String, Object> listPurchaseSuggestions() {
        return Map.of(
                "status", "pending_sync",
                "items", List.of()
        );
    }
}
