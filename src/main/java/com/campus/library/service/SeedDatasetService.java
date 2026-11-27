package com.campus.library.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campus.library.config.AppProperties;
import com.campus.library.entity.Account;
import com.campus.library.entity.Asset;
import com.campus.library.entity.AuditLog;
import com.campus.library.entity.Book;
import com.campus.library.entity.ClockSetting;
import com.campus.library.entity.DatasetSeedState;
import com.campus.library.entity.Loan;
import com.campus.library.entity.Major;
import com.campus.library.entity.Reservation;
import com.campus.library.mapper.AccountMapper;
import com.campus.library.mapper.AssetMapper;
import com.campus.library.mapper.AuditLogMapper;
import com.campus.library.mapper.BookMapper;
import com.campus.library.mapper.ClockSettingMapper;
import com.campus.library.mapper.DatasetSeedStateMapper;
import com.campus.library.mapper.LoanMapper;
import com.campus.library.mapper.MajorMapper;
import com.campus.library.mapper.PurchaseResultMapper;
import com.campus.library.mapper.RecommendationSnapshotMapper;
import com.campus.library.mapper.ReminderMapper;
import com.campus.library.mapper.RequestReceiptMapper;
import com.campus.library.mapper.ReservationMapper;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DU-10 初始化数据装载服务（INT-06 seed_dataset）。
 *
 * 严格依据《详细设计文档》12 节与 13.1 节课程合成数据：
 * - 模拟当天：2026-11-20（Asia/Shanghai，+08:00）
 * - 2 个专业：软件工程（SE）、信息管理（IM）
 * - 9 个测试账号：6 名学生（A1~A6）+ 图书馆管理员 + 采购员 + 系统管理员
 * - 15 种馆藏书目（book_id 101~115）
 * - 17 条借阅记录（loan_id 1~17 对应 L01~L17，其中 4 条在借覆盖 -1/0/3/4 天边界）
 * - 3 条有效预约（115 号书：A2、A3、A5）
 * - 16 条私有对象存储元数据（每本书 1 张封面，101 号书另含 1 份私有附件）
 */
@Service
public class SeedDatasetService {

    public static final String DATASET_VERSION = "2026-11-20-v1";
    public static final LocalDate SIM_DATE = LocalDate.of(2026, 11, 20);
    public static final int LOAN_PERIOD_DAYS = 14;
    private static final ZoneOffset SHANGHAI_OFFSET = ZoneOffset.ofHours(8);

    private record MajorSpec(String code, String name) {}

    private record AccountSpec(String loginName, String role, String majorCode, String displayCode) {}

    private record BookSpec(long bookId, String title, String category, int totalCount,
                            int expectedBorrowed, int expectedAvailable) {}

    private record LoanSpec(long loanId, String studentCode, long bookId,
                            LocalDate borrowedOn, LocalDate dueOn, LocalDate returnedOn) {}

    private record ReservationSpec(String studentCode, long bookId, OffsetDateTime createdAt) {}

    public record SeedResult(
            String status,
            String datasetVersion,
            Map<String, Long> counts,
            Map<String, Object> checks
    ) {}

    private static final List<MajorSpec> MAJORS_SPEC = List.of(
            new MajorSpec("SE", "软件工程"),
            new MajorSpec("IM", "信息管理")
    );

    private static final List<AccountSpec> ACCOUNTS_SPEC = List.of(
            new AccountSpec("a1", Account.ROLE_STUDENT, "SE", "A1"),
            new AccountSpec("a2", Account.ROLE_STUDENT, "SE", "A2"),
            new AccountSpec("a3", Account.ROLE_STUDENT, "IM", "A3"),
            new AccountSpec("a4", Account.ROLE_STUDENT, "IM", "A4"),
            new AccountSpec("a5", Account.ROLE_STUDENT, "SE", "A5"),
            new AccountSpec("a6", Account.ROLE_STUDENT, "IM", "A6"),
            new AccountSpec("librarian", Account.ROLE_LIBRARIAN, null, null),
            new AccountSpec("buyer", Account.ROLE_BUYER, null, null),
            new AccountSpec("sysadmin", Account.ROLE_SYSADMIN, null, null)
    );

    private static final List<BookSpec> BOOKS_SPEC = List.of(
            new BookSpec(101, "软件工程导论", "计算机", 3, 0, 3),
            new BookSpec(102, "离散数学", "数学", 3, 1, 2),
            new BookSpec(103, "数据库系统", "计算机", 2, 0, 2),
            new BookSpec(104, "算法设计", "计算机", 1, 1, 0),
            new BookSpec(105, "计算机网络", "计算机", 1, 0, 1),
            new BookSpec(106, "信息检索", "信息管理", 3, 1, 2),
            new BookSpec(107, "档案管理", "信息管理", 2, 1, 1),
            new BookSpec(108, "图书馆学基础", "信息管理", 2, 0, 2),
            new BookSpec(109, "数据可视化", "计算机", 2, 0, 2),
            new BookSpec(110, "概率论基础", "数学", 2, 0, 2),
            new BookSpec(111, "线性代数", "数学", 3, 0, 3),
            new BookSpec(112, "学术写作", "通识", 1, 0, 1),
            new BookSpec(113, "中国文学史", "通识", 2, 0, 2),
            new BookSpec(114, "科技英语", "通识", 2, 0, 2),
            new BookSpec(115, "人工智能概论", "计算机", 1, 0, 1)
    );

    private static final List<LoanSpec> LOANS_SPEC = List.of(
            new LoanSpec(1, "A1", 101, LocalDate.of(2026, 10, 20), LocalDate.of(2026, 11, 3), LocalDate.of(2026, 10, 27)),
            new LoanSpec(2, "A1", 102, LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 23), null),
            new LoanSpec(3, "A2", 101, LocalDate.of(2026, 10, 21), LocalDate.of(2026, 11, 4), LocalDate.of(2026, 10, 28)),
            new LoanSpec(4, "A2", 103, LocalDate.of(2026, 10, 22), LocalDate.of(2026, 11, 5), LocalDate.of(2026, 10, 30)),
            new LoanSpec(5, "A2", 104, LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 24), null),
            new LoanSpec(6, "A2", 105, LocalDate.of(2026, 10, 23), LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 3)),
            new LoanSpec(7, "A3", 102, LocalDate.of(2026, 10, 24), LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 1)),
            new LoanSpec(8, "A3", 103, LocalDate.of(2026, 10, 27), LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 4)),
            new LoanSpec(9, "A3", 106, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 19), null),
            new LoanSpec(10, "A4", 107, LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 20), null),
            new LoanSpec(11, "A4", 108, LocalDate.of(2026, 10, 25), LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 2)),
            new LoanSpec(12, "A6", 109, LocalDate.of(2026, 10, 24), LocalDate.of(2026, 11, 7), LocalDate.of(2026, 10, 26)),
            new LoanSpec(13, "A6", 110, LocalDate.of(2026, 10, 25), LocalDate.of(2026, 11, 8), LocalDate.of(2026, 10, 28)),
            new LoanSpec(14, "A6", 111, LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 9), LocalDate.of(2026, 10, 29)),
            new LoanSpec(15, "A6", 112, LocalDate.of(2026, 10, 27), LocalDate.of(2026, 11, 10), LocalDate.of(2026, 10, 29)),
            new LoanSpec(16, "A6", 113, LocalDate.of(2026, 10, 28), LocalDate.of(2026, 11, 11), LocalDate.of(2026, 10, 30)),
            new LoanSpec(17, "A6", 114, LocalDate.of(2026, 10, 29), LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 1))
    );

    private static final List<ReservationSpec> RESERVATIONS_SPEC = List.of(
            new ReservationSpec("A2", 115, OffsetDateTime.of(2026, 11, 18, 10, 0, 0, 0, SHANGHAI_OFFSET)),
            new ReservationSpec("A3", 115, OffsetDateTime.of(2026, 11, 19, 10, 0, 0, 0, SHANGHAI_OFFSET)),
            new ReservationSpec("A5", 115, OffsetDateTime.of(2026, 11, 20, 10, 0, 0, 0, SHANGHAI_OFFSET))
    );

    private final MajorMapper majorMapper;
    private final AccountMapper accountMapper;
    private final BookMapper bookMapper;
    private final AssetMapper assetMapper;
    private final LoanMapper loanMapper;
    private final ReservationMapper reservationMapper;
    private final PurchaseResultMapper purchaseResultMapper;
    private final ClockSettingMapper clockSettingMapper;
    private final ReminderMapper reminderMapper;
    private final RecommendationSnapshotMapper recommendationSnapshotMapper;
    private final RequestReceiptMapper requestReceiptMapper;
    private final AuditLogMapper auditLogMapper;
    private final DatasetSeedStateMapper datasetSeedStateMapper;
    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties appProperties;

    public SeedDatasetService(
            MajorMapper majorMapper,
            AccountMapper accountMapper,
            BookMapper bookMapper,
            AssetMapper assetMapper,
            LoanMapper loanMapper,
            ReservationMapper reservationMapper,
            PurchaseResultMapper purchaseResultMapper,
            ClockSettingMapper clockSettingMapper,
            ReminderMapper reminderMapper,
            RecommendationSnapshotMapper recommendationSnapshotMapper,
            RequestReceiptMapper requestReceiptMapper,
            AuditLogMapper auditLogMapper,
            DatasetSeedStateMapper datasetSeedStateMapper,
            JdbcTemplate jdbcTemplate,
            DataSource dataSource,
            PasswordEncoder passwordEncoder,
            AppProperties appProperties
    ) {
        this.majorMapper = majorMapper;
        this.accountMapper = accountMapper;
        this.bookMapper = bookMapper;
        this.assetMapper = assetMapper;
        this.loanMapper = loanMapper;
        this.reservationMapper = reservationMapper;
        this.purchaseResultMapper = purchaseResultMapper;
        this.clockSettingMapper = clockSettingMapper;
        this.reminderMapper = reminderMapper;
        this.recommendationSnapshotMapper = recommendationSnapshotMapper;
        this.requestReceiptMapper = requestReceiptMapper;
        this.auditLogMapper = auditLogMapper;
        this.datasetSeedStateMapper = datasetSeedStateMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
        this.passwordEncoder = passwordEncoder;
        this.appProperties = appProperties;
    }

    /**
     * 装载前校验样例定义自身的主外键、库存守恒与活跃唯一性（DU-10 12.6）。
     */
    public void validateSpecIntegrity() {
        Set<String> majorCodes = new HashSet<>();
        for (MajorSpec m : MAJORS_SPEC) {
            majorCodes.add(m.code());
        }
        Set<String> studentCodes = new HashSet<>();
        for (AccountSpec a : ACCOUNTS_SPEC) {
            if (Account.ROLE_STUDENT.equals(a.role())
                    && a.displayCode() != null
                    && majorCodes.contains(a.majorCode())) {
                studentCodes.add(a.displayCode());
            }
        }
        Map<Long, BookSpec> booksById = new HashMap<>();
        for (BookSpec b : BOOKS_SPEC) {
            booksById.put(b.bookId(), b);
        }

        Map<Long, Integer> activeByBook = new HashMap<>();
        Set<String> activeLoanPairs = new HashSet<>();
        for (LoanSpec l : LOANS_SPEC) {
            if (!studentCodes.contains(l.studentCode())) {
                throw new IllegalStateException("Loan " + l.loanId() + " references unknown student " + l.studentCode());
            }
            if (!booksById.containsKey(l.bookId())) {
                throw new IllegalStateException("Loan " + l.loanId() + " references unknown book " + l.bookId());
            }
            if (!l.dueOn().equals(l.borrowedOn().plusDays(LOAN_PERIOD_DAYS))) {
                throw new IllegalStateException("Loan " + l.loanId() + " due_on does not match 14-day rule");
            }
            if (l.returnedOn() != null && l.returnedOn().isBefore(l.borrowedOn())) {
                throw new IllegalStateException("Loan " + l.loanId() + " returned_on < borrowed_on");
            }
            if (l.returnedOn() == null) {
                String pair = l.studentCode() + ":" + l.bookId();
                if (!activeLoanPairs.add(pair)) {
                    throw new IllegalStateException("Duplicate active loan for " + pair);
                }
                activeByBook.merge(l.bookId(), 1, Integer::sum);
            }
        }

        for (BookSpec b : BOOKS_SPEC) {
            int actualBorrowed = activeByBook.getOrDefault(b.bookId(), 0);
            if (actualBorrowed != b.expectedBorrowed()
                    || b.totalCount() - actualBorrowed != b.expectedAvailable()) {
                throw new IllegalStateException("Book " + b.bookId() + " inventory mismatch");
            }
        }

        Set<String> resPairs = new HashSet<>();
        for (ReservationSpec r : RESERVATIONS_SPEC) {
            if (!studentCodes.contains(r.studentCode()) || !booksById.containsKey(r.bookId())) {
                throw new IllegalStateException("Invalid reservation " + r);
            }
            String pair = r.studentCode() + ":" + r.bookId();
            if (!resPairs.add(pair)) {
                throw new IllegalStateException("Duplicate active reservation " + pair);
            }
        }
    }

    @Transactional
    public SeedResult seedDataset(boolean reset) {
        return seedDataset(reset, null);
    }

    @Transactional
    public SeedResult seedDataset(boolean reset, String overridePassword) {
        validateSpecIntegrity();
        String rawPassword = (overridePassword != null && !overridePassword.isBlank())
                ? overridePassword
                : appProperties.getSeed().getDefaultPassword();
        String encodedPassword = passwordEncoder.encode(rawPassword);

        DatasetSeedState existingState = datasetSeedStateMapper.selectById((short) 1);
        if (!reset) {
            if (existingState != null && DATASET_VERSION.equals(existingState.getDatasetVersion())) {
                return new SeedResult("already_seeded", DATASET_VERSION, collectCounts(), verifyConsistency());
            }
            if (hasExistingBusinessData()) {
                throw new SeedConflictException(
                        "目标数据库已存在非同版本业务数据；请在独立演示库执行，或使用 --seed-reset 显式重置。");
            }
        } else {
            clearAllBusinessTables();
        }

        // 1. 实验时钟设置（模拟当天 2026-11-20）
        ClockSetting clock = new ClockSetting();
        clock.setSingletonId((short) 1);
        clock.setSimDate(SIM_DATE);
        clock.setClockVersion(1L);
        clockSettingMapper.insert(clock);

        // 2. 专业
        Map<String, Major> majorsByCode = new HashMap<>();
        for (MajorSpec spec : MAJORS_SPEC) {
            Major major = new Major();
            major.setCode(spec.code());
            major.setName(spec.name());
            majorMapper.insert(major);
            majorsByCode.put(spec.code(), major);
        }

        // 3. 合成账号
        Map<String, Account> accountsByCode = new HashMap<>();
        for (AccountSpec spec : ACCOUNTS_SPEC) {
            Account account = new Account();
            account.setLoginName(spec.loginName());
            account.setPasswordHash(encodedPassword);
            account.setRole(spec.role());
            account.setMajorId(spec.majorCode() != null ? majorsByCode.get(spec.majorCode()).getMajorId() : null);
            account.setDisplayCode(spec.displayCode());
            account.setIsActive(Boolean.TRUE);
            accountMapper.insert(account);
            if (spec.displayCode() != null) {
                accountsByCode.put(spec.displayCode(), account);
            }
        }

        // 4. 馆藏书目（显式写入 book_id 101~115）
        for (BookSpec spec : BOOKS_SPEC) {
            jdbcTemplate.update(
                    "INSERT INTO book (book_id, title, category, total_count, available_count, version) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    spec.bookId(),
                    spec.title(),
                    spec.category(),
                    spec.totalCount(),
                    spec.expectedAvailable(),
                    1L
            );
        }

        // 5. 借阅记录（显式写入 loan_id 1~17 对应 L01~L17）
        for (LoanSpec spec : LOANS_SPEC) {
            Long studentId = accountsByCode.get(spec.studentCode()).getUserId();
            jdbcTemplate.update(
                    "INSERT INTO loan (loan_id, student_id, book_id, borrowed_on, due_on, returned_on) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    spec.loanId(),
                    studentId,
                    spec.bookId(),
                    spec.borrowedOn(),
                    spec.dueOn(),
                    spec.returnedOn()
            );
        }

        // 6. 有效预约（115 号书：A2、A3、A5）
        for (ReservationSpec spec : RESERVATIONS_SPEC) {
            Reservation reservation = new Reservation();
            reservation.setStudentId(accountsByCode.get(spec.studentCode()).getUserId());
            reservation.setBookId(spec.bookId());
            reservation.setCreatedAt(spec.createdAt());
            reservationMapper.insert(reservation);
        }

        // 7. 私有对象存储元数据（每本书 1 张封面，101 号书另含 1 份私有附件）
        for (BookSpec spec : BOOKS_SPEC) {
            Asset cover = new Asset();
            cover.setBookId(spec.bookId());
            cover.setKind(Asset.KIND_COVER);
            cover.setObjectKey("private/books/" + spec.bookId() + "/cover.png");
            cover.setOriginalName("book_" + spec.bookId() + "_cover.png");
            cover.setContentType("image/png");
            assetMapper.insert(cover);

            Book updateBook = new Book();
            updateBook.setBookId(spec.bookId());
            updateBook.setCoverAssetId(cover.getAssetId());
            bookMapper.updateById(updateBook);
        }

        Asset attachment101 = new Asset();
        attachment101.setBookId(101L);
        attachment101.setKind(Asset.KIND_ATTACHMENT);
        attachment101.setObjectKey("private/books/101/syllabus.pdf");
        attachment101.setOriginalName("software_engineering_syllabus.pdf");
        attachment101.setContentType("application/pdf");
        assetMapper.insert(attachment101);

        // 8. 审计日志与样例版本登记
        AuditLog log = new AuditLog();
        log.setActorId(null);
        log.setAction("seed_dataset");
        log.setResource("dataset:" + DATASET_VERSION);
        log.setOutcome("success");
        log.setCreatedAt(OffsetDateTime.now(SHANGHAI_OFFSET));
        auditLogMapper.insert(log);

        DatasetSeedState state = new DatasetSeedState();
        state.setSingletonId((short) 1);
        state.setDatasetVersion(DATASET_VERSION);
        state.setSeededAt(OffsetDateTime.now(SHANGHAI_OFFSET));
        datasetSeedStateMapper.insert(state);

        // 9. 同步自增序列，避免后续新增书目/借阅与显式 ID（101~115, 1~17）冲突
        resetIdentitySequences();

        return new SeedResult("seeded", DATASET_VERSION, collectCounts(), verifyConsistency());
    }

    public Map<String, Long> collectCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("majors", majorMapper.selectCount(null));
        counts.put("accounts", accountMapper.selectCount(null));
        counts.put("books", bookMapper.selectCount(null));
        counts.put("loans", loanMapper.selectCount(null));
        counts.put("active_loans", loanMapper.selectCount(
                Wrappers.<Loan>lambdaQuery().isNull(Loan::getReturnedOn)));
        counts.put("reservations", reservationMapper.selectCount(null));
        counts.put("active_reservations", reservationMapper.selectCount(
                Wrappers.<Reservation>lambdaQuery().isNull(Reservation::getCancelledAt)));
        counts.put("assets", assetMapper.selectCount(null));
        return counts;
    }

    public Map<String, Object> verifyConsistency() {
        List<Long> mismatches = new ArrayList<>();
        for (Book book : bookMapper.selectList(null)) {
            long activeLoans = loanMapper.selectCount(
                    Wrappers.<Loan>lambdaQuery()
                            .eq(Loan::getBookId, book.getBookId())
                            .isNull(Loan::getReturnedOn));
            if (book.getAvailableCount() != book.getTotalCount() - activeLoans) {
                mismatches.add(book.getBookId());
            }
        }
        Book book115 = bookMapper.selectById(115L);
        long res115 = reservationMapper.selectCount(
                Wrappers.<Reservation>lambdaQuery()
                        .eq(Reservation::getBookId, 115L)
                        .isNull(Reservation::getCancelledAt));
        ClockSetting clock = clockSettingMapper.selectById((short) 1);

        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("inventory_consistent", mismatches.isEmpty());
        checks.put("inventory_mismatches", mismatches);
        checks.put("book_115_available", book115 != null ? book115.getAvailableCount() : null);
        checks.put("book_115_active_reservations", res115);
        checks.put("sim_date", clock != null && clock.getSimDate() != null ? clock.getSimDate().toString() : null);
        return checks;
    }

    private boolean hasExistingBusinessData() {
        return majorMapper.selectCount(null) > 0
                || accountMapper.selectCount(null) > 0
                || bookMapper.selectCount(null) > 0
                || loanMapper.selectCount(null) > 0
                || reservationMapper.selectCount(null) > 0
                || assetMapper.selectCount(null) > 0;
    }

    private void clearAllBusinessTables() {
        reminderMapper.delete(null);
        purchaseResultMapper.delete(null);
        recommendationSnapshotMapper.delete(null);
        requestReceiptMapper.delete(null);
        auditLogMapper.delete(null);
        reservationMapper.delete(null);
        loanMapper.delete(null);
        jdbcTemplate.update("UPDATE book SET cover_asset_id = NULL");
        assetMapper.delete(null);
        bookMapper.delete(null);
        accountMapper.delete(null);
        majorMapper.delete(null);
        clockSettingMapper.delete(null);
        datasetSeedStateMapper.delete(null);
    }

    private void resetIdentitySequences() {
        String dbProduct = detectDatabaseProductName();
        if (dbProduct.contains("postgresql")) {
            jdbcTemplate.execute("SELECT setval(pg_get_serial_sequence('book', 'book_id'), COALESCE((SELECT MAX(book_id) FROM book), 1), true)");
            jdbcTemplate.execute("SELECT setval(pg_get_serial_sequence('loan', 'loan_id'), COALESCE((SELECT MAX(loan_id) FROM loan), 1), true)");
        } else if (dbProduct.contains("h2")) {
            Long maxBookId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(book_id), 0) FROM book", Long.class);
            Long maxLoanId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(loan_id), 0) FROM loan", Long.class);
            jdbcTemplate.execute("ALTER TABLE book ALTER COLUMN book_id RESTART WITH " + ((maxBookId != null ? maxBookId : 0L) + 1));
            jdbcTemplate.execute("ALTER TABLE loan ALTER COLUMN loan_id RESTART WITH " + ((maxLoanId != null ? maxLoanId : 0L) + 1));
        }
    }

    private String detectDatabaseProductName() {
        String name = jdbcTemplate.execute((ConnectionCallback<String>) conn ->
                conn.getMetaData().getDatabaseProductName().toLowerCase());
        return name != null ? name : "";
    }
}
