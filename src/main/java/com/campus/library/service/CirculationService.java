package com.campus.library.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campus.library.config.AppProperties;
import com.campus.library.dto.CheckinRequest;
import com.campus.library.dto.CheckoutRequest;
import com.campus.library.dto.ReservationRequest;
import com.campus.library.entity.Account;
import com.campus.library.entity.Book;
import com.campus.library.entity.ClockSetting;
import com.campus.library.entity.Loan;
import com.campus.library.entity.Reservation;
import com.campus.library.exception.ApiException;
import com.campus.library.mapper.AccountMapper;
import com.campus.library.mapper.BookMapper;
import com.campus.library.mapper.ClockSettingMapper;
import com.campus.library.mapper.LoanMapper;
import com.campus.library.mapper.ReservationMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DU-03 借出与归还事务 + DU-04 预约与取消事务（MOD-02 / INT-02）。
 *
 * 事务与锁顺序：
 * 1. 事务外完成归属校验（越权拒绝审计日志独立落库，不随业务事务回滚，也不额外占用第二条连接）；
 * 2. 快速检查幂等回执 request_receipt（同键同参复用，同键异参报错）；
 * 3. 在编程式事务内统一按 book_id 加排他行锁（SELECT ... FOR UPDATE），二次核对回执后完成校验与原子更新；
 * 4. 若并发跨书请求撞 request_receipt 唯一约束（DuplicateKeyException），原事务自动回滚后重新核对回执：
 *    参数一致复用原回执，参数不同返回 409 REQUEST_ID_CONFLICT。
 */
@Service
public class CirculationService {

    private static final int LOAN_PERIOD_DAYS = 14;
    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");
    private static final ZoneOffset SHANGHAI_OFFSET = ZoneOffset.ofHours(8);

    private final BookMapper bookMapper;
    private final LoanMapper loanMapper;
    private final ReservationMapper reservationMapper;
    private final AccountMapper accountMapper;
    private final ClockSettingMapper clockSettingMapper;
    private final IdempotencyService idempotencyService;
    private final AuthService authService;
    private final AppProperties appProperties;
    private final TransactionTemplate transactionTemplate;

    public CirculationService(
            BookMapper bookMapper,
            LoanMapper loanMapper,
            ReservationMapper reservationMapper,
            AccountMapper accountMapper,
            ClockSettingMapper clockSettingMapper,
            IdempotencyService idempotencyService,
            AuthService authService,
            AppProperties appProperties,
            TransactionTemplate transactionTemplate
    ) {
        this.bookMapper = bookMapper;
        this.loanMapper = loanMapper;
        this.reservationMapper = reservationMapper;
        this.accountMapper = accountMapper;
        this.clockSettingMapper = clockSettingMapper;
        this.idempotencyService = idempotencyService;
        this.authService = authService;
        this.appProperties = appProperties;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * DU-03 借出：学生本人借阅或图书馆管理员代办。
     */
    public Map<String, Object> checkout(AuthService.AuthContext actor, CheckoutRequest req) {
        String reqIdStr = req.requestId().toString();
        Long targetStudentId = resolveTargetStudent(actor, req.studentId(), reqIdStr);

        String payloadHash = idempotencyService.hashPayload(
                "checkout:" + targetStudentId + ":" + req.bookId());
        Optional<Map<String, Object>> cached = idempotencyService.findExistingResponse(
                actor.actorId(), "checkout", req.requestId(), payloadHash);
        if (cached.isPresent()) {
            return cached.get();
        }

        return executeIdempotentTransaction(
                actor.actorId(),
                "checkout",
                req.requestId(),
                payloadHash,
                () -> {
                    Book lockedBook = bookMapper.selectByIdForUpdate(req.bookId());
                    if (lockedBook == null) {
                        throw new ApiException(HttpStatus.NOT_FOUND, "BOOK_NOT_FOUND", "书目不存在", reqIdStr);
                    }

                    // 获取书目行锁后二次核对幂等回执，确保并发同 request_id 重放直接复用已提交结果
                    Optional<Map<String, Object>> cachedAfterLock = idempotencyService.findExistingResponse(
                            actor.actorId(), "checkout", req.requestId(), payloadHash);
                    if (cachedAfterLock.isPresent()) {
                        return cachedAfterLock.get();
                    }

                    // 校验同一学生同一书目无在借记录
                    long activeSameBook = loanMapper.selectCount(
                            Wrappers.<Loan>lambdaQuery()
                                    .eq(Loan::getStudentId, targetStudentId)
                                    .eq(Loan::getBookId, req.bookId())
                                    .isNull(Loan::getReturnedOn)
                    );
                    if (activeSameBook > 0) {
                        throw new ApiException(HttpStatus.CONFLICT, "ALREADY_BORROWED", "该图书正在借阅中，不可重复借出", reqIdStr);
                    }

                    if (lockedBook.getAvailableCount() == null || lockedBook.getAvailableCount() < 1) {
                        throw new ApiException(HttpStatus.CONFLICT, "OUT_OF_STOCK", "暂无可借图书", reqIdStr);
                    }

                    int updated = bookMapper.decrementAvailableAndBumpVersion(req.bookId());
                    if (updated != 1) {
                        throw new ApiException(HttpStatus.CONFLICT, "OUT_OF_STOCK", "暂无可借图书", reqIdStr);
                    }

                    LocalDate today = readBusinessDate();
                    LocalDate dueOn = today.plusDays(LOAN_PERIOD_DAYS);

                    Loan loan = new Loan();
                    loan.setStudentId(targetStudentId);
                    loan.setBookId(req.bookId());
                    loan.setBorrowedOn(today);
                    loan.setDueOn(dueOn);
                    loan.setReturnedOn(null);
                    loanMapper.insert(loan);

                    Book refreshed = bookMapper.selectById(req.bookId());

                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("loan_id", loan.getLoanId());
                    result.put("book_id", req.bookId());
                    result.put("student_id", targetStudentId);
                    result.put("borrowed_on", today.toString());
                    result.put("due_on", dueOn.toString());
                    result.put("available_count", refreshed.getAvailableCount());
                    result.put("version", refreshed.getVersion());

                    idempotencyService.saveReceipt(actor.actorId(), "checkout", req.requestId(), payloadHash, result);
                    authService.recordAudit(actor.actorId(), "checkout", "loan:" + loan.getLoanId(), "success");
                    return result;
                },
                "ALREADY_BORROWED",
                "该图书正在借阅中，不可重复借出"
        );
    }

    /**
     * DU-03 归还：仅办理在借记录，标记 returned_on 并恢复 1 册库存。
     */
    public Map<String, Object> checkin(AuthService.AuthContext actor, CheckinRequest req) {
        String reqIdStr = req.requestId().toString();

        String payloadHash = idempotencyService.hashPayload("checkin:" + req.loanId());
        Optional<Map<String, Object>> cached = idempotencyService.findExistingResponse(
                actor.actorId(), "checkin", req.requestId(), payloadHash);
        if (cached.isPresent()) {
            return cached.get();
        }

        // 在事务外预查借阅记录并做学生归属校验，确保越权审计独立落库且不污染事务一级缓存
        Loan initialLoan = loanMapper.selectById(req.loanId());
        if (initialLoan == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "LOAN_NOT_FOUND", "借阅记录不存在", reqIdStr);
        }
        if (Account.ROLE_STUDENT.equals(actor.role()) && !actor.actorId().equals(initialLoan.getStudentId())) {
            authService.recordAudit(actor.actorId(), "checkin", "loan:" + req.loanId(), "ownership_denied");
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权归还他人的借阅记录", reqIdStr);
        }

        return executeIdempotentTransaction(
                actor.actorId(),
                "checkin",
                req.requestId(),
                payloadHash,
                () -> {
                    // 统一先锁书目，再二次核对幂等回执并加锁重读借阅记录防止并发重复归还
                    Book lockedBook = bookMapper.selectByIdForUpdate(initialLoan.getBookId());
                    if (lockedBook == null) {
                        throw new ApiException(HttpStatus.NOT_FOUND, "BOOK_NOT_FOUND", "关联书目不存在", reqIdStr);
                    }

                    Optional<Map<String, Object>> cachedAfterLock = idempotencyService.findExistingResponse(
                            actor.actorId(), "checkin", req.requestId(), payloadHash);
                    if (cachedAfterLock.isPresent()) {
                        return cachedAfterLock.get();
                    }

                    Loan loan = loanMapper.selectByIdForUpdate(req.loanId());
                    if (loan == null) {
                        throw new ApiException(HttpStatus.NOT_FOUND, "LOAN_NOT_FOUND", "借阅记录不存在", reqIdStr);
                    }
                    if (loan.getReturnedOn() != null) {
                        throw new ApiException(HttpStatus.CONFLICT, "LOAN_ALREADY_RETURNED", "该借阅记录已归还，不可重复办理", reqIdStr);
                    }

                    LocalDate today = readBusinessDate();
                    if (today.isBefore(loan.getBorrowedOn())) {
                        throw new ApiException(
                                HttpStatus.CONFLICT,
                                "CLOCK_BEFORE_BORROW_DATE",
                                "当前业务日期早于借出日期，请调整模拟时钟后再办理归还",
                                reqIdStr
                        );
                    }

                    // 原子条件更新：仅当 returned_on IS NULL 时生效，彻底防止并发重复归还
                    int marked = loanMapper.markReturnedIfActive(loan.getLoanId(), today);
                    if (marked != 1) {
                        throw new ApiException(HttpStatus.CONFLICT, "LOAN_ALREADY_RETURNED", "该借阅记录已归还，不可重复办理", reqIdStr);
                    }

                    int updated = bookMapper.incrementAvailableAndBumpVersion(loan.getBookId());
                    if (updated != 1) {
                        throw new ApiException(HttpStatus.CONFLICT, "INVENTORY_OVERFLOW", "库存恢复失败，请核对馆藏总数", reqIdStr);
                    }

                    Book refreshed = bookMapper.selectById(loan.getBookId());

                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("loan_id", loan.getLoanId());
                    result.put("book_id", loan.getBookId());
                    result.put("returned_on", today.toString());
                    result.put("available_count", refreshed.getAvailableCount());
                    result.put("version", refreshed.getVersion());

                    idempotencyService.saveReceipt(actor.actorId(), "checkin", req.requestId(), payloadHash, result);
                    authService.recordAudit(actor.actorId(), "checkin", "loan:" + loan.getLoanId(), "success");
                    return result;
                },
                "LOAN_ALREADY_RETURNED",
                "该借阅记录已归还，不可重复办理"
        );
    }

    /**
     * DU-04 预约与取消：按学生去重，仅状态实际变化时递增 book.version，库存始终不变。
     */
    public Map<String, Object> setReservation(AuthService.AuthContext actor, ReservationRequest req) {
        String reqIdStr = req.requestId().toString();
        Long targetStudentId = resolveTargetStudent(actor, req.studentId(), reqIdStr);

        String payloadHash = idempotencyService.hashPayload(
                "reserve:" + targetStudentId + ":" + req.bookId() + ":" + req.active());
        Optional<Map<String, Object>> cached = idempotencyService.findExistingResponse(
                actor.actorId(), "reserve", req.requestId(), payloadHash);
        if (cached.isPresent()) {
            return cached.get();
        }

        return executeIdempotentTransaction(
                actor.actorId(),
                "reserve",
                req.requestId(),
                payloadHash,
                () -> {
                    Book lockedBook = bookMapper.selectByIdForUpdate(req.bookId());
                    if (lockedBook == null) {
                        throw new ApiException(HttpStatus.NOT_FOUND, "BOOK_NOT_FOUND", "书目不存在", reqIdStr);
                    }

                    // 获取书目行锁后二次核对幂等回执
                    Optional<Map<String, Object>> cachedAfterLock = idempotencyService.findExistingResponse(
                            actor.actorId(), "reserve", req.requestId(), payloadHash);
                    if (cachedAfterLock.isPresent()) {
                        return cachedAfterLock.get();
                    }

                    Reservation activeRes = reservationMapper.selectOne(
                            Wrappers.<Reservation>lambdaQuery()
                                    .eq(Reservation::getStudentId, targetStudentId)
                                    .eq(Reservation::getBookId, req.bookId())
                                    .isNull(Reservation::getCancelledAt)
                    );

                    Long reservationId;
                    if (Boolean.TRUE.equals(req.active())) {
                        if (activeRes != null) {
                            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_RESERVED", "已有该图书的有效预约", reqIdStr);
                        }
                        Reservation created = new Reservation();
                        created.setStudentId(targetStudentId);
                        created.setBookId(req.bookId());
                        created.setCreatedAt(OffsetDateTime.now(SHANGHAI_OFFSET));
                        created.setCancelledAt(null);
                        reservationMapper.insert(created);
                        reservationId = created.getReservationId();
                    } else {
                        if (activeRes == null) {
                            throw new ApiException(HttpStatus.CONFLICT, "RESERVATION_NOT_FOUND", "当前无该图书的有效预约", reqIdStr);
                        }
                        activeRes.setCancelledAt(OffsetDateTime.now(SHANGHAI_OFFSET));
                        reservationMapper.updateById(activeRes);
                        reservationId = activeRes.getReservationId();
                    }

                    bookMapper.bumpVersionOnly(req.bookId());
                    Book refreshed = bookMapper.selectById(req.bookId());
                    long reservationCount = reservationMapper.selectCount(
                            Wrappers.<Reservation>lambdaQuery()
                                    .eq(Reservation::getBookId, req.bookId())
                                    .isNull(Reservation::getCancelledAt)
                    );

                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("reservation_id", reservationId);
                    result.put("book_id", req.bookId());
                    result.put("active", req.active());
                    result.put("reservation_count", reservationCount);
                    result.put("available_count", refreshed.getAvailableCount());
                    result.put("version", refreshed.getVersion());

                    idempotencyService.saveReceipt(actor.actorId(), "reserve", req.requestId(), payloadHash, result);
                    authService.recordAudit(
                            actor.actorId(),
                            Boolean.TRUE.equals(req.active()) ? "reserve_create" : "reserve_cancel",
                            "reservation:" + reservationId,
                            "success"
                    );
                    return result;
                },
                "ALREADY_RESERVED",
                "已有该图书的有效预约"
        );
    }

    /**
     * 查询指定学生的借阅记录（供 DU-01 归属校验与学生本人记录页使用）。
     */
    public List<Map<String, Object>> listStudentLoans(Long studentId) {
        LocalDate today = readBusinessDate();
        return loanMapper.selectList(
                        Wrappers.<Loan>lambdaQuery()
                                .eq(Loan::getStudentId, studentId)
                                .orderByDesc(Loan::getLoanId))
                .stream()
                .map(loan -> {
                    boolean active = loan.getReturnedOn() == null;
                    long delta = java.time.temporal.ChronoUnit.DAYS.between(today, loan.getDueOn());
                    boolean highlight = active && delta <= 3;
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("loan_id", loan.getLoanId());
                    item.put("student_id", loan.getStudentId());
                    item.put("book_id", loan.getBookId());
                    item.put("borrowed_on", loan.getBorrowedOn().toString());
                    item.put("due_on", loan.getDueOn().toString());
                    item.put("returned_on", loan.getReturnedOn() != null ? loan.getReturnedOn().toString() : null);
                    item.put("highlight", highlight);
                    return item;
                })
                .toList();
    }

    /**
     * 查询指定学生的预约记录（含有效与已取消历史）。
     */
    public List<Map<String, Object>> listStudentReservations(Long studentId) {
        return reservationMapper.selectList(
                        Wrappers.<Reservation>lambdaQuery()
                                .eq(Reservation::getStudentId, studentId)
                                .orderByDesc(Reservation::getReservationId))
                .stream()
                .map(res -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("reservation_id", res.getReservationId());
                    item.put("student_id", res.getStudentId());
                    item.put("book_id", res.getBookId());
                    item.put("active", res.getCancelledAt() == null);
                    item.put("created_at", res.getCreatedAt() != null ? res.getCreatedAt().toString() : null);
                    item.put("cancelled_at", res.getCancelledAt() != null ? res.getCancelledAt().toString() : null);
                    return item;
                })
                .toList();
    }

    private Map<String, Object> executeIdempotentTransaction(
            Long actorId,
            String operation,
            UUID requestId,
            String payloadHash,
            Supplier<Map<String, Object>> txBody,
            String fallbackConflictCode,
            String fallbackConflictMsg
    ) {
        try {
            return transactionTemplate.execute(status -> txBody.get());
        } catch (DuplicateKeyException dupEx) {
            // 事务已自动回滚；在干净连接上复核是否命中 request_receipt 唯一约束：
            // - 同键同参 -> 返回原成功回执
            // - 同键异参 -> findExistingResponse 抛出 409 REQUEST_ID_CONFLICT
            Optional<Map<String, Object>> existing = idempotencyService.findExistingResponse(
                    actorId, operation, requestId, payloadHash);
            if (existing.isPresent()) {
                return existing.get();
            }
            throw new ApiException(HttpStatus.CONFLICT, fallbackConflictCode, fallbackConflictMsg, requestId.toString());
        }
    }

    private Long resolveTargetStudent(AuthService.AuthContext actor, Long requestedStudentId, String requestId) {
        if (Account.ROLE_STUDENT.equals(actor.role())) {
            if (requestedStudentId != null && !actor.actorId().equals(requestedStudentId)) {
                authService.recordAudit(actor.actorId(), "ownership_check", "student:" + requestedStudentId, "ownership_denied");
                throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "学生只能办理本人业务", requestId);
            }
            return actor.actorId();
        }
        // 图书馆管理员代办时必须指定有效的学生账号
        if (requestedStudentId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "STUDENT_ID_REQUIRED", "代办业务必须指定 studentId", requestId);
        }
        Account student = accountMapper.selectById(requestedStudentId);
        if (student == null
                || !Account.ROLE_STUDENT.equals(student.getRole())
                || !Boolean.TRUE.equals(student.getIsActive())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STUDENT", "目标学生账号不存在或已停用", requestId);
        }
        return student.getUserId();
    }

    private LocalDate readBusinessDate() {
        if (appProperties.isExperimentalClockEnabled()) {
            ClockSetting clock = clockSettingMapper.selectById((short) 1);
            if (clock != null && clock.getSimDate() != null) {
                return clock.getSimDate();
            }
        }
        return LocalDate.now(SHANGHAI_ZONE);
    }
}
