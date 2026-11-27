package com.campus.library.controller;

import com.campus.library.config.AppProperties;
import com.campus.library.entity.ClockSetting;
import com.campus.library.entity.DatasetSeedState;
import com.campus.library.mapper.BookMapper;
import com.campus.library.mapper.ClockSettingMapper;
import com.campus.library.mapper.DatasetSeedStateMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DU-10 运维诊断接口（health_check）。
 */
@RestController
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final BookMapper bookMapper;
    private final ClockSettingMapper clockSettingMapper;
    private final DatasetSeedStateMapper datasetSeedStateMapper;
    private final AppProperties appProperties;

    public HealthController(
            JdbcTemplate jdbcTemplate,
            BookMapper bookMapper,
            ClockSettingMapper clockSettingMapper,
            DatasetSeedStateMapper datasetSeedStateMapper,
            AppProperties appProperties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.bookMapper = bookMapper;
        this.clockSettingMapper = clockSettingMapper;
        this.datasetSeedStateMapper = datasetSeedStateMapper;
        this.appProperties = appProperties;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean dbOk = true;
        String dbError = null;
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        } catch (Exception ex) {
            dbOk = false;
            dbError = ex.getMessage();
        }

        DatasetSeedState seedState = dbOk ? datasetSeedStateMapper.selectById((short) 1) : null;
        ClockSetting clock = dbOk ? clockSettingMapper.selectById((short) 1) : null;
        long bookCount = dbOk ? bookMapper.selectCount(null) : 0L;

        Map<String, Object> dbInfo = new LinkedHashMap<>();
        dbInfo.put("ok", dbOk);
        dbInfo.put("error", dbError);

        Map<String, Object> datasetInfo = new LinkedHashMap<>();
        datasetInfo.put("seeded", seedState != null);
        datasetInfo.put("version", seedState != null ? seedState.getDatasetVersion() : null);
        datasetInfo.put("book_count", bookCount);

        Map<String, Object> clockInfo = new LinkedHashMap<>();
        clockInfo.put("experimental_enabled", appProperties.isExperimentalClockEnabled());
        clockInfo.put("sim_date", clock != null && clock.getSimDate() != null ? clock.getSimDate().toString() : null);
        clockInfo.put("clock_version", clock != null ? clock.getClockVersion() : null);

        Map<String, Object> cloudInfo = new LinkedHashMap<>();
        cloudInfo.put("mock_mode", appProperties.getCloud().isMockMode());
        cloudInfo.put("region", appProperties.getCloud().getRegion());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", dbOk ? "ok" : "degraded");
        body.put("database", dbInfo);
        body.put("dataset", datasetInfo);
        body.put("clock", clockInfo);
        body.put("cloud", cloudInfo);

        return ResponseEntity.status(dbOk ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
