package com.campus.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campus.library.entity.Account;
import com.campus.library.entity.Asset;
import com.campus.library.entity.Book;
import com.campus.library.entity.ClockSetting;
import com.campus.library.entity.Loan;
import com.campus.library.entity.Major;
import com.campus.library.entity.Reservation;
import com.campus.library.mapper.AccountMapper;
import com.campus.library.mapper.AssetMapper;
import com.campus.library.mapper.BookMapper;
import com.campus.library.mapper.ClockSettingMapper;
import com.campus.library.mapper.DatasetSeedStateMapper;
import com.campus.library.mapper.LoanMapper;
import com.campus.library.mapper.MajorMapper;
import com.campus.library.mapper.ReservationMapper;
import com.campus.library.service.SeedConflictException;
import com.campus.library.service.SeedDatasetService;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * DU-10 初始化数据集与健康检查验证测试（对应详细设计用例 INIT-01 与第 13.1 节）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SeedDatasetServiceTest {

    @Autowired
    private SeedDatasetService seedDatasetService;
    @Autowired
    private MajorMapper majorMapper;
    @Autowired
    private AccountMapper accountMapper;
    @Autowired
    private BookMapper bookMapper;
    @Autowired
    private LoanMapper loanMapper;
    @Autowired
    private ReservationMapper reservationMapper;
    @Autowired
    private AssetMapper assetMapper;
    @Autowired
    private ClockSettingMapper clockSettingMapper;
    @Autowired
    private DatasetSeedStateMapper datasetSeedStateMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void resetBeforeEach() {
        seedDatasetService.seedDataset(true);
    }

    @Test
    void init01_seedIsIdempotentAndMatchesChapter13() {
        // 已在 @BeforeEach 装载一次；再次装载必须幂等且不漂移
        SeedDatasetService.SeedResult second = seedDatasetService.seedDataset(false);
        assertThat(second.status()).isEqualTo("already_seeded");
        assertThat(second.datasetVersion()).isEqualTo(SeedDatasetService.DATASET_VERSION);
        assertThat(second.checks().get("inventory_consistent")).isEqualTo(true);

        // 核对第 13.1 节核心规模
        assertThat(majorMapper.selectCount(null)).isEqualTo(2L);
        assertThat(accountMapper.selectCount(null)).isEqualTo(9L);
        assertThat(accountMapper.selectCount(
                Wrappers.<Account>lambdaQuery().eq(Account::getRole, Account.ROLE_STUDENT))).isEqualTo(6L);
        assertThat(bookMapper.selectCount(null)).isEqualTo(15L);
        assertThat(loanMapper.selectCount(null)).isEqualTo(17L);
        assertThat(loanMapper.selectCount(
                Wrappers.<Loan>lambdaQuery().isNull(Loan::getReturnedOn))).isEqualTo(4L);
        assertThat(reservationMapper.selectCount(
                Wrappers.<Reservation>lambdaQuery()
                        .eq(Reservation::getBookId, 115L)
                        .isNull(Reservation::getCancelledAt))).isEqualTo(3L);
        assertThat(assetMapper.selectCount(null)).isEqualTo(16L);
        assertThat(assetMapper.selectCount(
                Wrappers.<Asset>lambdaQuery()
                        .eq(Asset::getBookId, 101L)
                        .eq(Asset::getKind, Asset.KIND_ATTACHMENT))).isEqualTo(1L);

        // 核对 115 号书与 104 号书库存边界
        Book b115 = bookMapper.selectById(115L);
        assertThat(b115.getTotalCount()).isEqualTo(1);
        assertThat(b115.getAvailableCount()).isEqualTo(1);

        Book b104 = bookMapper.selectById(104L);
        assertThat(b104.getTotalCount()).isEqualTo(1);
        assertThat(b104.getAvailableCount()).isEqualTo(0);

        // 核对模拟当天与 4 条在借记录的到期差值 (-1, 0, 3, 4)
        ClockSetting clock = clockSettingMapper.selectById((short) 1);
        assertThat(clock.getSimDate()).isEqualTo(LocalDate.of(2026, 11, 20));

        List<Loan> activeLoans = loanMapper.selectList(
                Wrappers.<Loan>lambdaQuery().isNull(Loan::getReturnedOn));
        Map<Long, Long> activeDeltas = new HashMap<>();
        for (Loan loan : activeLoans) {
            activeDeltas.put(loan.getLoanId(), ChronoUnit.DAYS.between(clock.getSimDate(), loan.getDueOn()));
        }
        assertThat(activeDeltas).containsExactlyInAnyOrderEntriesOf(
                Map.of(2L, 3L, 5L, 4L, 9L, -1L, 10L, 0L));

        // 核对 BCrypt 密码哈希
        Account a1 = accountMapper.selectOne(
                Wrappers.<Account>lambdaQuery().eq(Account::getLoginName, "a1"));
        assertThat(passwordEncoder.matches("CampusLib#2026", a1.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches("wrong-password", a1.getPasswordHash())).isFalse();

        // 核对序列已同步：后续新增书目 ID 应大于 115
        Book newBook = new Book();
        newBook.setTitle("测试新书");
        newBook.setCategory("综合");
        newBook.setTotalCount(1);
        newBook.setAvailableCount(1);
        newBook.setVersion(1L);
        bookMapper.insert(newBook);
        assertThat(newBook.getBookId()).isGreaterThan(115L);
    }

    @Test
    void seedRejectsConflictingNonSeededDatabase() {
        // 移除样例版本登记，模拟目标库中已有非同版本业务数据
        datasetSeedStateMapper.delete(null);

        assertThatThrownBy(() -> seedDatasetService.seedDataset(false))
                .isInstanceOf(SeedConflictException.class);

        // 显式 reset=true 可干净恢复为 13.1 节标准样例
        SeedDatasetService.SeedResult resetResult = seedDatasetService.seedDataset(true);
        assertThat(resetResult.status()).isEqualTo("seeded");
        assertThat(majorMapper.selectCount(null)).isEqualTo(2L);
        assertThat(bookMapper.selectCount(null)).isEqualTo(15L);
    }

    @Test
    void healthEndpointReturnsSeededStatus() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.dataset.seeded").value(true))
                .andExpect(jsonPath("$.dataset.book_count").value(15))
                .andExpect(jsonPath("$.clock.sim_date").value("2026-11-20"));
    }
}
