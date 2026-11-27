package com.campus.library.service;

import com.campus.library.exception.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 写操作幂等回执服务（对应《详细设计文档》2.4 节 request_receipt 约定）。
 *
 * 同一 (actor_id, operation, request_id)：
 * - 参数摘要一致：直接复用并返回先前成功响应；
 * - 参数摘要不同：拒绝并提示 REQUEST_ID_CONFLICT。
 *
 * 读写均通过 JdbcTemplate 显式绑定 UUID 参数并读取文本响应，确保 PostgreSQL (UUID/JSONB) 与 H2 (UUID/CLOB) 一致兼容。
 */
@Service
public class IdempotencyService {

    private static final ZoneOffset SHANGHAI_OFFSET = ZoneOffset.ofHours(8);

    private record ReceiptRow(String payloadHash, String responseJson) {}

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public IdempotencyService(
            JdbcTemplate jdbcTemplate,
            DataSource dataSource,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    public String hashPayload(String canonicalPayload) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonicalPayload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public Optional<Map<String, Object>> findExistingResponse(
            Long actorId,
            String operation,
            UUID requestId,
            String payloadHash
    ) {
        List<ReceiptRow> rows = jdbcTemplate.query(
                "SELECT payload_hash, response FROM request_receipt "
                        + "WHERE actor_id = ? AND operation = ? AND request_id = ?",
                (rs, rowNum) -> new ReceiptRow(rs.getString("payload_hash"), rs.getString("response")),
                actorId, operation, requestId
        );
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        ReceiptRow existing = rows.get(0);
        if (!payloadHash.equals(existing.payloadHash())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "REQUEST_ID_CONFLICT",
                    "相同的 request_id 不能用于不同参数的请求",
                    requestId.toString()
            );
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(
                    existing.responseJson(),
                    new TypeReference<Map<String, Object>>() {}
            );
            return Optional.of(parsed);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to deserialize request receipt", ex);
        }
    }

    public void saveReceipt(
            Long actorId,
            String operation,
            UUID requestId,
            String payloadHash,
            Map<String, Object> responsePayload
    ) {
        String json;
        try {
            json = objectMapper.writeValueAsString(responsePayload);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize request receipt", ex);
        }

        OffsetDateTime now = OffsetDateTime.now(SHANGHAI_OFFSET);
        if (isPostgres()) {
            jdbcTemplate.update(
                    "INSERT INTO request_receipt (actor_id, operation, request_id, payload_hash, response, created_at) "
                            + "VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?)",
                    actorId, operation, requestId, payloadHash, json, now
            );
        } else {
            jdbcTemplate.update(
                    "INSERT INTO request_receipt (actor_id, operation, request_id, payload_hash, response, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    actorId, operation, requestId, payloadHash, json, now
            );
        }
    }

    private boolean isPostgres() {
        try (Connection conn = dataSource.getConnection()) {
            return conn.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
        } catch (Exception e) {
            return false;
        }
    }
}
