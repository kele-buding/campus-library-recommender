package com.campus.library.controller;

import com.campus.library.dto.CheckinRequest;
import com.campus.library.dto.CheckoutRequest;
import com.campus.library.dto.ReservationRequest;
import com.campus.library.entity.Account;
import com.campus.library.exception.ApiException;
import com.campus.library.security.AuthInterceptor;
import com.campus.library.security.RequireAction;
import com.campus.library.service.AuthService;
import com.campus.library.service.CirculationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * DU-03 借出与归还 + DU-04 预约与取消 REST 接口（INT-02）。
 */
@RestController
@RequestMapping("/api/circulation")
public class CirculationController {

    private final CirculationService circulationService;

    public CirculationController(CirculationService circulationService) {
        this.circulationService = circulationService;
    }

    @PostMapping("/checkout")
    @RequireAction(AuthService.Action.CHECKOUT)
    public Map<String, Object> checkout(
            @Valid @RequestBody CheckoutRequest req,
            HttpServletRequest request
    ) {
        return circulationService.checkout(AuthInterceptor.currentContext(request), req);
    }

    @PostMapping("/checkin")
    @RequireAction(AuthService.Action.CHECKIN)
    public Map<String, Object> checkin(
            @Valid @RequestBody CheckinRequest req,
            HttpServletRequest request
    ) {
        return circulationService.checkin(AuthInterceptor.currentContext(request), req);
    }

    @PostMapping("/reservations")
    @RequireAction(AuthService.Action.RESERVE)
    public Map<String, Object> setReservation(
            @Valid @RequestBody ReservationRequest req,
            HttpServletRequest request
    ) {
        return circulationService.setReservation(AuthInterceptor.currentContext(request), req);
    }

    @GetMapping("/loans")
    @RequireAction(value = AuthService.Action.VIEW_STUDENT_LOANS, studentIdParam = "studentId")
    public List<Map<String, Object>> listLoans(
            @RequestParam(required = false) Long studentId,
            HttpServletRequest request
    ) {
        AuthService.AuthContext actor = AuthInterceptor.currentContext(request);
        Long targetStudentId = resolveQueryStudentId(actor, studentId);
        return circulationService.listStudentLoans(targetStudentId);
    }

    @GetMapping("/reservations")
    @RequireAction(value = AuthService.Action.VIEW_STUDENT_LOANS, studentIdParam = "studentId")
    public List<Map<String, Object>> listReservations(
            @RequestParam(required = false) Long studentId,
            HttpServletRequest request
    ) {
        AuthService.AuthContext actor = AuthInterceptor.currentContext(request);
        Long targetStudentId = resolveQueryStudentId(actor, studentId);
        return circulationService.listStudentReservations(targetStudentId);
    }

    private Long resolveQueryStudentId(AuthService.AuthContext actor, Long requestedStudentId) {
        if (Account.ROLE_STUDENT.equals(actor.role())) {
            return actor.actorId();
        }
        if (requestedStudentId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "STUDENT_ID_REQUIRED", "管理员查询学生记录须指定 studentId");
        }
        return requestedStudentId;
    }
}
