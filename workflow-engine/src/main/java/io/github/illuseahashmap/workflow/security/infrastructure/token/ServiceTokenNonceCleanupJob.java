package io.github.illuseahashmap.workflow.security.infrastructure.token;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ServiceTokenNonceCleanupJob {

    private final JdbcTemplate jdbcTemplate;

    public ServiceTokenNonceCleanupJob(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(fixedDelayString = "${workflow.security.nonce-cleanup-interval-ms:600000}")
    @Transactional
    public void deleteExpiredNonces() {
        jdbcTemplate.update("DELETE FROM workflow_service_token_nonce WHERE expires_at < CURRENT_TIMESTAMP");
    }
}
