package com.campus.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campus.library.dto.CheckinRequest;
import com.campus.library.dto.CheckoutRequest;
import com.campus.library.entity.Account;
import com.campus.library.entity.AuditLog;
import com.campus.library.entity.Book;
import com.campus.library.entity.ClockSetting;
import com.campus.library.entity.Loan;
import com.campus.library.entity.Reservation;
import com.campus.library.exception.ApiException;
import com.campus.library.mapper.AccountMapper;
import com.campus.library.mapper.AuditLogMapper;
import com.campus.library.mapper.BookMapper;
import com.campus.library.mapper.ClockSettingMapper;
import com.campus.library.mapper.LoanMapper;
import com.campus.library.mapper.ReservationMapper;
import com.campus.library.service.AuthService;
import com.campus.library.service.CirculationService;
import com.campus.library.service.SeedDatasetService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 角色 A Week 1 完整验收测试：
 * 覆盖 DU-01 (ID-01, ID-02)、DU-03 (LOAN-01 ~ LOAN-03)、DU-04 (RES-01, RES-02)。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoleAWeek1AcceptanceTest {

    private static final String DEFAULT_PWD = "CampusLib#2026";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private SeedDatasetService seedDatasetService;
    @Autowired
    private AccountMapper accountMapper;
    @Autowired
    private AuditLogMapper auditLogMapper;
    @Autowired
    private BookMapper bookMapper;
    @Autowired
    private LoanMapper loanMapper;
    @Autowired
    private ReservationMapper reservationMapper;
    @Autowired
    private ClockSettingMapper clockSettingMapper;
    @Autowired
    private CirculationService circulationService;

    @BeforeEach
    void resetDataset() {
        seedDatasetService.seedDataset(true);
    }

    // =========================================================================
    // A-W1-1 / DU-01: ID-01 & ID-02
    // =========================================================================

    @Test
    void id01_studentCannotAccessAnotherStudentLoansOrReservations() throws Exception {
        Account a1 = findAccount("a1");
        Account a2 = findAccount("a2");
        MockHttpSession a1Session = login("a1", DEFAULT_PWD);

        // A1 查询本人借阅记录成功（仅返回 A1 的 L01、L02）
        mockMvc.perform(get("/api/circulation/loans").session(a1Session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].student_id").value(a1.getUserId()));

        // A1 越权请求 A2 借阅记录 -> 403 FORBIDDEN，且响应不含任何 A2 资料
        MvcResult deniedLoans = mockMvc.perform(get("/api/circulation/loans")
                        .param("studentId", a2.getUserId().toString())
                        .session(a1Session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("FORBIDDEN"))
                .andReturn();
        String deniedBody = deniedLoans.getResponse().getContentAsString();
        assertThat(deniedBody).doesNotContain("\"student_id\":" + a2.getUserId());
        assertThat(deniedBody).doesNotContain("104");

        // A1 越权请求 A2 预约记录 -> 403 FORBIDDEN
        mockMvc.perform(get("/api/circulation/reservations")
                        .param("studentId", a2.getUserId().toString())
                        .session(a1Session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("FORBIDDEN"));
    }

    @Test
    void id02_sysadminAccountMajorMaintenanceAndDisabledSessionImmediateRejection() throws Exception {
        Account a1 = findAccount("a1");
        MockHttpSession a1Session = login("a1", DEFAULT_PWD);
        MockHttpSession adminSession = login("sysadmin", DEFAULT_PWD);

        // 系统管理员可维护专业与账号
        MvcResult createMajorRes = mockMvc.perform(post("/api/admin/majors")
                        .session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "CS",
                                "name", "计算机科学"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CS"))
                .andReturn();
        long newMajorId = objectMapper.readTree(createMajorRes.getResponse().getContentAsString())
                .get("major_id").asLong();

        // 系统管理员维护 A1 专业 -> 成功
        mockMvc.perform(put("/api/admin/accounts/" + a1.getUserId())
                        .session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "loginName", "a1",
                                "role", "student",
                                "majorId", newMajorId,
                                "displayCode", "A1",
                                "isActive", true
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.major_id").value(newMajorId));

        // 系统管理员打开采购建议 -> 403 FORBIDDEN
        mockMvc.perform(get("/api/purchase/suggestions").session(adminSession))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("FORBIDDEN"));

        // 采购员打开采购建议 -> 200 OK
        MockHttpSession buyerSession = login("buyer", DEFAULT_PWD);
        mockMvc.perform(get("/api/purchase/suggestions").session(buyerSession))
                .andExpect(status().isOk());

        // 系统管理员停用 A1 账号
        mockMvc.perform(patch("/api/admin/accounts/" + a1.getUserId() + "/status")
                        .session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("isActive", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.is_active").value(false));

        // 停用 A1 后，A1 旧会话下一次受保护请求立即拒绝
        mockMvc.perform(get("/api/circulation/loans").session(a1Session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error_code").value("ACCOUNT_INACTIVE"));

        // 停用后再次登录被拒，且与错误密码返回统一错误码
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "loginName", "a1",
                                "password", DEFAULT_PWD
                        ))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error_code").value("INVALID_CREDENTIALS"));
    }

    // =========================================================================
    // A-W1-2 / DU-03: LOAN-01 ~ LOAN-03
    // =========================================================================

    @Test
    void loan01_checkoutOutOfStockAndCheckinRestoresInventory() throws Exception {
        MockHttpSession a4Session = login("a4", DEFAULT_PWD);
        MockHttpSession a5Session = login("a5", DEFAULT_PWD);
        long initialLoanCount = loanMapper.selectCount(null);

        // 1. A4 借出 115 号书（库存 1 -> 0, version 1 -> 2）
        UUID reqA4Borrow = UUID.randomUUID();
        MvcResult borrowRes = mockMvc.perform(post("/api/circulation/checkout")
                        .session(a4Session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bookId", 115,
                                "requestId", reqA4Borrow.toString()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available_count").value(0))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.borrowed_on").value("2026-11-20"))
                .andExpect(jsonPath("$.due_on").value("2026-12-04"))
                .andReturn();
        long loanId = objectMapper.readTree(borrowRes.getResponse().getContentAsString())
                .get("loan_id").asLong();

        // 2. A5 再借 115 号书 -> 409 OUT_OF_STOCK，库存保持 0，不新增借阅
        UUID reqA5Borrow = UUID.randomUUID();
        mockMvc.perform(post("/api/circulation/checkout")
                        .session(a5Session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bookId", 115,
                                "requestId", reqA5Borrow.toString()
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error_code").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.request_id").value(reqA5Borrow.toString()));

        assertThat(bookMapper.selectById(115L).getAvailableCount()).isEqualTo(0);
        assertThat(loanMapper.selectCount(null)).isEqualTo(initialLoanCount + 1);

        // 3. A4 归还 115 号书（库存 0 -> 1, version 2 -> 3）
        UUID reqA4Return = UUID.randomUUID();
        mockMvc.perform(post("/api/circulation/checkin")
                        .session(a4Session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "loanId", loanId,
                                "requestId", reqA4Return.toString()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loan_id").value(loanId))
                .andExpect(jsonPath("$.returned_on").value("2026-11-20"))
                .andExpect(jsonPath("$.available_count").value(1))
                .andExpect(jsonPath("$.version").value(3));

        Book b115 = bookMapper.selectById(115L);
        assertThat(b115.getAvailableCount()).isEqualTo(1);
        assertThat(b115.getVersion()).isEqualTo(3L);
    }

    @Test
    void loan02_concurrentCheckoutOnlyOneSucceedsAndReplayReturnsReceipt() throws Exception {
        Account a4 = findAccount("a4");
        Account a5 = findAccount("a5");
        AuthService.AuthContext ctxA4 = toAuthContext(a4);
        AuthService.AuthContext ctxA5 = toAuthContext(a5);
        UUID reqA4 = UUID.randomUUID();
        UUID reqA5 = UUID.randomUUID();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<Object> f1 = pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    return circulationService.checkout(ctxA4, new CheckoutRequest(115L, null, reqA4));
                } catch (ApiException ex) {
                    return ex;
                }
            });
            Future<Object> f2 = pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    return circulationService.checkout(ctxA5, new CheckoutRequest(115L, null, reqA5));
                } catch (ApiException ex) {
                    return ex;
                }
            });

            ready.await();
            start.countDown();

            Object r1 = f1.get();
            Object r2 = f2.get();

            List<Map<String, Object>> successes = new ArrayList<>();
            List<ApiException> failures = new ArrayList<>();
            AuthService.AuthContext winnerCtx = null;
            UUID winnerReqId = null;

            if (r1 instanceof Map<?, ?> m1) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) m1;
                successes.add(cast);
                winnerCtx = ctxA4;
                winnerReqId = reqA4;
            } else if (r1 instanceof ApiException ex1) {
                failures.add(ex1);
            }
            if (r2 instanceof Map<?, ?> m2) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) m2;
                successes.add(cast);
                winnerCtx = ctxA5;
                winnerReqId = reqA5;
            } else if (r2 instanceof ApiException ex2) {
                failures.add(ex2);
            }

            // 仅 1 笔成功，另 1 笔因无库存失败
            assertThat(successes).hasSize(1);
            assertThat(failures).hasSize(1);
            assertThat(failures.get(0).getErrorCode()).isEqualTo("OUT_OF_STOCK");

            Book b115 = bookMapper.selectById(115L);
            assertThat(b115.getAvailableCount()).isEqualTo(0);
            assertThat(b115.getVersion()).isEqualTo(2L);

            // 重放成功请求：返回原回执，不重复扣减，不新增借阅
            long loanCountAfterWin = loanMapper.selectCount(null);
            Map<String, Object> replayed = circulationService.checkout(
                    winnerCtx, new CheckoutRequest(115L, null, winnerReqId));
            assertThat(((Number) replayed.get("loan_id")).longValue())
                    .isEqualTo(((Number) successes.get(0).get("loan_id")).longValue());
            assertThat(((Number) replayed.get("available_count")).intValue()).isEqualTo(0);
            assertThat(loanMapper.selectCount(null)).isEqualTo(loanCountAfterWin);

            // 同 request_id 不同参数 -> 409 REQUEST_ID_CONFLICT
            AuthService.AuthContext finalWinnerCtx = winnerCtx;
            UUID finalWinnerReqId = winnerReqId;
            try {
                circulationService.checkout(finalWinnerCtx, new CheckoutRequest(101L, null, finalWinnerReqId));
                throw new AssertionError("Expected REQUEST_ID_CONFLICT");
            } catch (ApiException ex) {
                assertThat(ex.getErrorCode()).isEqualTo("REQUEST_ID_CONFLICT");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void loan03_checkinFailureRollsBackAndReplayDoesNotDoubleIncrement() throws Exception {
        // L02 为 A1 在借 102 号书（borrowed_on = 2026-11-09，102 号书初始 available_count = 2）
        MockHttpSession a1Session = login("a1", DEFAULT_PWD);
        Book beforeBook = bookMapper.selectById(102L);
        assertThat(beforeBook.getAvailableCount()).isEqualTo(2);

        // 1. 模拟日期早于借出日期 -> 归还失败并回滚，库存与 returned_on 均不变
        ClockSetting clock = clockSettingMapper.selectById((short) 1);
        clock.setSimDate(LocalDate.of(2026, 11, 1));
        clockSettingMapper.updateById(clock);

        UUID failReqId = UUID.randomUUID();
        mockMvc.perform(post("/api/circulation/checkin")
                        .session(a1Session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "loanId", 2,
                                "requestId", failReqId.toString()
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error_code").value("CLOCK_BEFORE_BORROW_DATE"));

        assertThat(loanMapper.selectById(2L).getReturnedOn()).isNull();
        assertThat(bookMapper.selectById(102L).getAvailableCount()).isEqualTo(2);

        // 恢复模拟日期为 2026-11-20
        clock.setSimDate(LocalDate.of(2026, 11, 20));
        clockSettingMapper.updateById(clock);

        // 2. 正常归还一次（库存 2 -> 3, version 1 -> 2）
        UUID returnReqId = UUID.randomUUID();
        mockMvc.perform(post("/api/circulation/checkin")
                        .session(a1Session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "loanId", 2,
                                "requestId", returnReqId.toString()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available_count").value(3))
                .andExpect(jsonPath("$.version").value(2));

        // 3. 相同 request_id 重放 -> 复用原回执，不二次加量
        mockMvc.perform(post("/api/circulation/checkin")
                        .session(a1Session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "loanId", 2,
                                "requestId", returnReqId.toString()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available_count").value(3))
                .andExpect(jsonPath("$.version").value(2));

        assertThat(bookMapper.selectById(102L).getAvailableCount()).isEqualTo(3);

        // 4. 新 request_id 再次归还同一记录 -> 409 LOAN_ALREADY_RETURNED，库存仍为 3
        mockMvc.perform(post("/api/circulation/checkin")
                        .session(a1Session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "loanId", 2,
                                "requestId", UUID.randomUUID().toString()
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error_code").value("LOAN_ALREADY_RETURNED"));

        assertThat(bookMapper.selectById(102L).getAvailableCount()).isEqualTo(3);
    }

    // =========================================================================
    // A-W1-3 / DU-04: RES-01 & RES-02
    // =========================================================================

    @Test
    void res01_duplicateCancelAndReReserveTrackCountAndVersionWithoutChangingInventory() throws Exception {
        MockHttpSession a5Session = login("a5", DEFAULT_PWD);

        // 初始：115 号书有 A2/A3/A5 有效预约（3人），库存 1，version 1
        assertThat(countActiveReservations(115L)).isEqualTo(3L);
        assertThat(bookMapper.selectById(115L).getAvailableCount()).isEqualTo(1);
        assertThat(bookMapper.selectById(115L).getVersion()).isEqualTo(1L);

        // 1. A5 重提预约 -> 409 ALREADY_RESERVED，人数仍 3，version 仍 1，库存仍 1
        mockMvc.perform(post("/api/circulation/reservations")
                        .session(a5Session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bookId", 115,
                                "active", true,
                                "requestId", UUID.randomUUID().toString()
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error_code").value("ALREADY_RESERVED"));

        assertThat(countActiveReservations(115L)).isEqualTo(3L);
        assertThat(bookMapper.selectById(115L).getVersion()).isEqualTo(1L);
        assertThat(bookMapper.selectById(115L).getAvailableCount()).isEqualTo(1);

        // 2. A5 取消预约 -> 人数 3 -> 2，version 1 -> 2，库存仍 1
        UUID cancelReqId = UUID.randomUUID();
        mockMvc.perform(post("/api/circulation/reservations")
                        .session(a5Session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bookId", 115,
                                "active", false,
                                "requestId", cancelReqId.toString()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservation_count").value(2))
                .andExpect(jsonPath("$.available_count").value(1))
                .andExpect(jsonPath("$.version").value(2));

        // 重放同一取消请求 -> 复用原回执，不重复递增 version
        mockMvc.perform(post("/api/circulation/reservations")
                        .session(a5Session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bookId", 115,
                                "active", false,
                                "requestId", cancelReqId.toString()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservation_count").value(2))
                .andExpect(jsonPath("$.version").value(2));

        // 3. A5 再次预约 -> 人数 2 -> 3，version 2 -> 3，库存仍 1
        mockMvc.perform(post("/api/circulation/reservations")
                        .session(a5Session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bookId", 115,
                                "active", true,
                                "requestId", UUID.randomUUID().toString()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservation_count").value(3))
                .andExpect(jsonPath("$.available_count").value(1))
                .andExpect(jsonPath("$.version").value(3));

        assertThat(countActiveReservations(115L)).isEqualTo(3L);
        assertThat(bookMapper.selectById(115L).getAvailableCount()).isEqualTo(1);
        assertThat(bookMapper.selectById(115L).getVersion()).isEqualTo(3L);
    }

    @Test
    void res02_studentCannotCancelAnotherStudentReservation() throws Exception {
        Account a2 = findAccount("a2");
        MockHttpSession a1Session = login("a1", DEFAULT_PWD);

        // A1 尝试替 A2 取消 115 号书的预约 -> 403 FORBIDDEN
        mockMvc.perform(post("/api/circulation/reservations")
                        .session(a1Session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bookId", 115,
                                "studentId", a2.getUserId(),
                                "active", false,
                                "requestId", UUID.randomUUID().toString()
                        ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("FORBIDDEN"));

        // A2 的有效预约仍然存在，115 号书预约人数仍为 3
        long a2Active = reservationMapper.selectCount(
                Wrappers.<Reservation>lambdaQuery()
                        .eq(Reservation::getStudentId, a2.getUserId())
                        .eq(Reservation::getBookId, 115L)
                        .isNull(Reservation::getCancelledAt));
        assertThat(a2Active).isEqualTo(1L);
        assertThat(countActiveReservations(115L)).isEqualTo(3L);
    }

    @Test
    void librarianCanProxyCheckoutAndCheckinForStudent() throws Exception {
        Account a4 = findAccount("a4");
        MockHttpSession libSession = login("librarian", DEFAULT_PWD);

        MvcResult borrowRes = mockMvc.perform(post("/api/circulation/checkout")
                        .session(libSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bookId", 115,
                                "studentId", a4.getUserId(),
                                "requestId", UUID.randomUUID().toString()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.student_id").value(a4.getUserId()))
                .andExpect(jsonPath("$.available_count").value(0))
                .andReturn();
        long loanId = objectMapper.readTree(borrowRes.getResponse().getContentAsString())
                .get("loan_id").asLong();

        mockMvc.perform(post("/api/circulation/checkin")
                        .session(libSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "loanId", loanId,
                                "requestId", UUID.randomUUID().toString()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available_count").value(1));
    }

    // =========================================================================
    // 回归测试：覆盖测试日志中暴露的 8 项缺陷（BUG-01 ~ BUG-08）
    // =========================================================================

    @Test
    void regression_studentConvertedToBuyerPersistsNullMajorAndDisplayCode() throws Exception {
        Account a1 = findAccount("a1");
        MockHttpSession adminSession = login("sysadmin", DEFAULT_PWD);

        mockMvc.perform(put("/api/admin/accounts/" + a1.getUserId())
                        .session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "loginName", "a1",
                                "role", "buyer",
                                "isActive", true
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("buyer"))
                .andExpect(jsonPath("$.major_id").doesNotExist())
                .andExpect(jsonPath("$.display_code").doesNotExist());

        Account reloaded = accountMapper.selectById(a1.getUserId());
        assertThat(reloaded.getRole()).isEqualTo(Account.ROLE_BUYER);
        assertThat(reloaded.getMajorId()).isNull();
        assertThat(reloaded.getDisplayCode()).isNull();
    }

    @Test
    void regression_oversizedMajorCodeReturns400InvalidArgument() throws Exception {
        MockHttpSession adminSession = login("sysadmin", DEFAULT_PWD);
        String oversizedCode = "A".repeat(33);

        mockMvc.perform(post("/api/admin/majors")
                        .session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", oversizedCode,
                                "name", "超长测试专业"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value("INVALID_ARGUMENT"));
    }

    @Test
    void regression_tenConcurrentCheckoutsForLastCopyReturnOneOkAndNineOutOfStock() throws Exception {
        List<Account> students = new ArrayList<>(accountMapper.selectList(
                Wrappers.<Account>lambdaQuery().eq(Account::getRole, Account.ROLE_STUDENT)));
        Account template = students.get(0);
        for (int i = students.size() + 1; i <= 10; i++) {
            Account extra = new Account();
            extra.setLoginName("extra_student_" + i);
            extra.setPasswordHash(template.getPasswordHash());
            extra.setRole(Account.ROLE_STUDENT);
            extra.setMajorId(template.getMajorId());
            extra.setDisplayCode("E" + i);
            extra.setIsActive(Boolean.TRUE);
            accountMapper.insert(extra);
            students.add(extra);
        }
        int concurrency = 10;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                Account student = students.get(i);
                AuthService.AuthContext ctx = toAuthContext(student);
                UUID reqId = UUID.randomUUID();
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        circulationService.checkout(ctx, new CheckoutRequest(115L, null, reqId));
                        return "OK";
                    } catch (ApiException ex) {
                        return ex.getErrorCode();
                    }
                }));
            }

            ready.await();
            start.countDown();

            List<String> outcomes = new ArrayList<>();
            for (Future<String> f : futures) {
                outcomes.add(f.get());
            }

            assertThat(outcomes).containsOnly("OK", "OUT_OF_STOCK");
            assertThat(outcomes.stream().filter("OK"::equals).count()).isEqualTo(1L);
            assertThat(outcomes.stream().filter("OUT_OF_STOCK"::equals).count()).isEqualTo(9L);

            Book b115 = bookMapper.selectById(115L);
            assertThat(b115.getAvailableCount()).isEqualTo(0);
            assertThat(b115.getVersion()).isEqualTo(2L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void regression_concurrentCrossBookSameRequestIdReturnsRequestIdConflict() throws Exception {
        Account a4 = findAccount("a4");
        AuthService.AuthContext ctxA4 = toAuthContext(a4);
        UUID sharedReqId = UUID.randomUUID();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Object> f1 = pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    return circulationService.checkout(ctxA4, new CheckoutRequest(101L, null, sharedReqId));
                } catch (Throwable t) {
                    return t;
                }
            });
            Future<Object> f2 = pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    return circulationService.checkout(ctxA4, new CheckoutRequest(103L, null, sharedReqId));
                } catch (Throwable t) {
                    return t;
                }
            });

            ready.await();
            start.countDown();

            Object r1 = f1.get();
            Object r2 = f2.get();
            List<Object> results = List.of(r1, r2);

            long okCount = results.stream().filter(Map.class::isInstance).count();
            List<Throwable> errors = results.stream()
                    .filter(Throwable.class::isInstance)
                    .map(Throwable.class::cast)
                    .toList();

            assertThat(okCount).isEqualTo(1L);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0)).isInstanceOf(ApiException.class);
            assertThat(((ApiException) errors.get(0)).getErrorCode()).isEqualTo("REQUEST_ID_CONFLICT");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void regression_forbiddenCheckoutPersistsOwnershipDenialAuditLog() {
        Account a1 = findAccount("a1");
        Account a2 = findAccount("a2");
        AuthService.AuthContext ctxA1 = toAuthContext(a1);

        long beforeLogs = auditLogMapper.selectCount(
                Wrappers.<AuditLog>lambdaQuery()
                        .eq(AuditLog::getActorId, a1.getUserId())
                        .eq(AuditLog::getOutcome, "ownership_denied"));

        try {
            circulationService.checkout(ctxA1, new CheckoutRequest(115L, a2.getUserId(), UUID.randomUUID()));
            throw new AssertionError("Expected FORBIDDEN");
        } catch (ApiException ex) {
            assertThat(ex.getErrorCode()).isEqualTo("FORBIDDEN");
        }

        long afterLogs = auditLogMapper.selectCount(
                Wrappers.<AuditLog>lambdaQuery()
                        .eq(AuditLog::getActorId, a1.getUserId())
                        .eq(AuditLog::getOutcome, "ownership_denied"));
        assertThat(afterLogs).isEqualTo(beforeLogs + 1);
    }

    @Test
    void regression_concurrentCheckinWithDifferentRequestIdsOnlySucceedsOnce() throws Exception {
        Account a1 = findAccount("a1");
        AuthService.AuthContext ctxA1 = toAuthContext(a1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Object> f1 = pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    return circulationService.checkin(ctxA1, new CheckinRequest(2L, UUID.randomUUID()));
                } catch (Throwable t) {
                    return t;
                }
            });
            Future<Object> f2 = pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    return circulationService.checkin(ctxA1, new CheckinRequest(2L, UUID.randomUUID()));
                } catch (Throwable t) {
                    return t;
                }
            });

            ready.await();
            start.countDown();

            List<Object> results = List.of(f1.get(), f2.get());
            long okCount = results.stream().filter(Map.class::isInstance).count();
            List<Throwable> errors = results.stream()
                    .filter(Throwable.class::isInstance)
                    .map(Throwable.class::cast)
                    .toList();

            assertThat(okCount).isEqualTo(1L);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0)).isInstanceOf(ApiException.class);
            assertThat(((ApiException) errors.get(0)).getErrorCode()).isEqualTo("LOAN_ALREADY_RETURNED");

            Book b102 = bookMapper.selectById(102L);
            assertThat(b102.getAvailableCount()).isEqualTo(3);
            assertThat(b102.getVersion()).isEqualTo(2L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void regression_seedLeavesNextLoanIdAvailable() {
        Account a4 = findAccount("a4");
        Loan loan = new Loan();
        loan.setStudentId(a4.getUserId());
        loan.setBookId(115L);
        loan.setBorrowedOn(LocalDate.of(2026, 11, 20));
        loan.setDueOn(LocalDate.of(2026, 12, 4));
        loanMapper.insert(loan);
        assertThat(loan.getLoanId()).isGreaterThan(17L);
    }

    private MockHttpSession login(String loginName, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "loginName", loginName,
                                "password", password
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.get("login_name").asText()).isEqualTo(loginName);
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private Account findAccount(String loginName) {
        Account acc = accountMapper.selectOne(
                Wrappers.<Account>lambdaQuery().eq(Account::getLoginName, loginName));
        assertThat(acc).isNotNull();
        return acc;
    }

    private AuthService.AuthContext toAuthContext(Account acc) {
        return new AuthService.AuthContext(
                acc.getUserId(),
                acc.getLoginName(),
                acc.getRole(),
                acc.getMajorId(),
                acc.getDisplayCode()
        );
    }

    private long countActiveReservations(long bookId) {
        return reservationMapper.selectCount(
                Wrappers.<Reservation>lambdaQuery()
                        .eq(Reservation::getBookId, bookId)
                        .isNull(Reservation::getCancelledAt));
    }
}
