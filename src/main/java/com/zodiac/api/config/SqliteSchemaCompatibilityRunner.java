package com.zodiac.api.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class SqliteSchemaCompatibilityRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        ensureColumn("pay_order", "channel", "VARCHAR(20)");
        ensureColumn("pay_order", "trade_type", "VARCHAR(20)");
        ensureColumn("pay_order", "subject", "VARCHAR(255)");
        ensureColumn("pay_order", "amount_fen", "INTEGER");
        ensureColumn("pay_order", "scene_code", "VARCHAR(30)");
        ensureColumn("pay_order", "client_ip", "VARCHAR(64)");
        ensureColumn("pay_order", "return_url", "VARCHAR(1000)");
        ensureColumn("pay_order", "wechat_prepay_id", "VARCHAR(128)");
        ensureColumn("pay_order", "wechat_mweb_url", "VARCHAR(1000)");
        ensureColumn("pay_order", "wechat_code_url", "VARCHAR(1000)");
        ensureColumn("pay_order", "alipay_trade_no", "VARCHAR(128)");
        ensureColumn("pay_order", "alipay_pay_url", "VARCHAR(2000)");
        ensureColumn("pay_order", "notify_type", "VARCHAR(32)");
        ensureColumn("pay_order", "notify_verified", "BOOLEAN");
        ensureColumn("pay_order", "notify_raw", "TEXT");
        ensureColumn("pay_order", "attach_payload", "TEXT");
        ensureColumn("pay_order", "referral_user_id", "BIGINT");
        ensureColumn("pay_order", "referral_settled", "BOOLEAN DEFAULT 0");
        ensureColumn("pay_order", "unlock_status", "VARCHAR(32) DEFAULT 'LOCKED'");
        ensureColumn("pay_order", "unlock_source", "VARCHAR(64)");
        ensureColumn("pay_order", "unlock_granted_at", "DATETIME");
        ensureColumn("pay_order", "unlock_granted_by", "VARCHAR(128)");
        ensureColumn("pay_order", "unlock_remark", "VARCHAR(1000)");
    }

    private void ensureColumn(String table, String column, String definition) {
        if (hasColumn(table, column)) {
            return;
        }
        jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        log.info("SQLite schema patched: {}.{}", table, column);
    }

    private boolean hasColumn(String table, String column) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("PRAGMA table_info(" + table + ")");
        return rows.stream().anyMatch(row -> column.equalsIgnoreCase(String.valueOf(row.get("name"))));
    }
}
