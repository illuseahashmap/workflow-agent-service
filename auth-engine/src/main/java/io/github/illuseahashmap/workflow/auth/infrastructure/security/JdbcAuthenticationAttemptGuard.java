package io.github.illuseahashmap.workflow.auth.infrastructure.security;

import io.github.illuseahashmap.workflow.auth.application.port.AuthenticationAttemptGuard;
import io.github.illuseahashmap.workflow.auth.domain.SecurityAuditRepository;
import io.github.illuseahashmap.workflow.auth.infrastructure.config.AuthenticationProtectionProperties;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class JdbcAuthenticationAttemptGuard implements AuthenticationAttemptGuard {

    private static final String ACCOUNT_BUCKET = "ACCOUNT";
    private static final String SOURCE_BUCKET = "SOURCE";

    private final JdbcTemplate jdbcTemplate;
    private final AuthenticationProtectionProperties properties;
    private final SecurityAuditRepository securityAuditRepository;

    public JdbcAuthenticationAttemptGuard(JdbcTemplate jdbcTemplate,
                                          AuthenticationProtectionProperties properties,
                                          SecurityAuditRepository securityAuditRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.securityAuditRepository = securityAuditRepository;
    }

    @Override
    public void assertAllowed(String operation, String account, String sourceAddress) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (isBlocked(operation, ACCOUNT_BUCKET, fingerprint(account), now)
                || isBlocked(operation, SOURCE_BUCKET, fingerprint(sourceAddress), now)) {
            throw new BusinessException(ErrorCode.RATE_LIMITED,
                    "Too many authentication attempts; try again later");
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String operation, String account, String sourceAddress) {
        String accountFingerprint = fingerprint(account);
        OffsetDateTime accountBlockedUntil = incrementBucket(
                operation, ACCOUNT_BUCKET, accountFingerprint);
        OffsetDateTime sourceBlockedUntil = incrementBucket(
                operation, SOURCE_BUCKET, fingerprint(sourceAddress));
        OffsetDateTime blockedUntil = latest(accountBlockedUntil, sourceBlockedUntil);
        securityAuditRepository.record(
                "AUTHENTICATION_FAILURE", null, null, sourceAddress,
                accountFingerprint, "FAILURE",
                blockedUntil == null ? operation : operation + " blockedUntil=" + blockedUntil);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(String operation, String account, String sourceAddress) {
        jdbcTemplate.update("""
                DELETE FROM auth_attempt_guard
                WHERE operation = ? AND bucket_type = ? AND fingerprint = ?
                """, operation, ACCOUNT_BUCKET, fingerprint(account));
    }

    private boolean isBlocked(String operation, String bucketType,
                              String fingerprint, OffsetDateTime now) {
        return jdbcTemplate.query("""
                        SELECT blocked_until
                        FROM auth_attempt_guard
                        WHERE operation = ? AND bucket_type = ? AND fingerprint = ?
                        """, (resultSet, rowNumber) -> resultSet.getObject(
                                "blocked_until", OffsetDateTime.class),
                        operation, bucketType, fingerprint).stream()
                .anyMatch(blockedUntil -> blockedUntil != null && blockedUntil.isAfter(now));
    }

    private OffsetDateTime incrementBucket(String operation, String bucketType, String fingerprint) {
        jdbcTemplate.update("""
                INSERT INTO auth_attempt_guard
                    (operation, bucket_type, fingerprint, failure_count,
                     last_failed_at, created_at, updated_at)
                VALUES (?, ?, ?, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (operation, bucket_type, fingerprint) DO NOTHING
                """, operation, bucketType, fingerprint);
        AttemptState state = jdbcTemplate.queryForObject("""
                        SELECT failure_count, last_failed_at
                        FROM auth_attempt_guard
                        WHERE operation = ? AND bucket_type = ? AND fingerprint = ?
                        FOR UPDATE
                        """, (resultSet, rowNumber) -> new AttemptState(
                                resultSet.getInt("failure_count"),
                                resultSet.getObject("last_failed_at", OffsetDateTime.class)),
                        operation, bucketType, fingerprint);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        boolean expired = state.lastFailedAt() == null
                || state.lastFailedAt().plusSeconds(properties.getResetWindowSeconds()).isBefore(now);
        int failureCount = expired ? 1 : state.failureCount() + 1;
        OffsetDateTime blockedUntil = calculateBlockedUntil(now, failureCount);
        jdbcTemplate.update("""
                UPDATE auth_attempt_guard
                SET failure_count = ?, last_failed_at = ?, blocked_until = ?, updated_at = CURRENT_TIMESTAMP
                WHERE operation = ? AND bucket_type = ? AND fingerprint = ?
                """, failureCount, now, blockedUntil, operation, bucketType, fingerprint);
        return blockedUntil;
    }

    private OffsetDateTime calculateBlockedUntil(OffsetDateTime now, int failureCount) {
        if (failureCount < properties.getFailureThreshold()) {
            return null;
        }
        int exponent = Math.min(failureCount - properties.getFailureThreshold(), 20);
        long multiplier = 1L << exponent;
        long delay = Math.min(
                properties.getBaseLockSeconds() * multiplier,
                properties.getMaxLockSeconds());
        return now.plusSeconds(delay);
    }

    private OffsetDateTime latest(OffsetDateTime left, OffsetDateTime right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isAfter(right) ? left : right;
    }

    private String fingerprint(String value) {
        String normalized = value == null || value.isBlank()
                ? "unknown" : value.trim().toLowerCase(Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record AttemptState(int failureCount, OffsetDateTime lastFailedAt) {
    }
}
