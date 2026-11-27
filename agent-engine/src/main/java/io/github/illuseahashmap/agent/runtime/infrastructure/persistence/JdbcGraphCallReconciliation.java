package io.github.illuseahashmap.agent.runtime.infrastructure.persistence;

import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Two-operator, immutable attestation of externally confirmed termination. Never restarts a Run.
 */
public final class JdbcGraphCallReconciliation {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public JdbcGraphCallReconciliation(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
        tx =
                new TransactionTemplate(
                        new DataSourceTransactionManager(
                                Objects.requireNonNull(jdbc.getJdbcTemplate().getDataSource())));
        tx.setTimeout(5);
    }

    public record Evidence(
            UUID token,
            String externalRequestId,
            String outcome,
            String evidenceUri,
            String evidenceSha256) {}

    public List<Map<String, Object>> pending() {
        return jdbc.queryForList(
                "SELECT"
                    + " p.token,p.tenant_code,p.run_id,p.operation_id,p.request_digest,p.generation,p.claimed_at,r.id"
                    + " AS reconciliation_id,r.external_request_id,r.outcome,r.evidence_uri,r.evidence_sha256,r.proposed_by"
                    + " FROM agent_graph_call_permit p LEFT JOIN agent_graph_call_reconciliation r"
                    + " ON r.token=p.token WHERE p.state='QUARANTINED' ORDER BY"
                    + " p.claimed_at,p.token,r.created_at LIMIT 200",
                Map.of());
    }

    public UUID propose(Evidence evidence, String actor) {
        validate(evidence, actor);
        return tx.execute(
                s -> {
                    GraphCapacityLock.lock(jdbc);
                    var permit = permit(evidence.token());
                    if (!"QUARANTINED".equals(permit.get("state"))) {
                        throw conflict("Call is not quarantined");
                    }
                    UUID id = UUID.randomUUID();
                    var p =
                            new MapSqlParameterSource(permit)
                                    .addValue("id", id)
                                    .addValue("external", evidence.externalRequestId())
                                    .addValue("outcome", evidence.outcome())
                                    .addValue("uri", evidence.evidenceUri())
                                    .addValue("hash", evidence.evidenceSha256())
                                    .addValue("actor", actor);
                    jdbc.update(
                            "INSERT INTO"
                                + " agent_graph_call_reconciliation(id,token,tenant_code,run_id,operation_id,request_digest,"
                                + "generation,external_request_id,outcome,evidence_uri,evidence_sha256,proposed_by)"
                                + " VALUES(:id,:token,:tenant_code,:run_id,:operation_id,:request_digest,:generation,:external,:outcome,:uri,:hash,:actor)",
                            p);
                    return id;
                });
    }

    public boolean approve(UUID id, String expectedEvidenceSha256, String actor) {
        if (actor == null || actor.isBlank() || actor.length() > 128) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Invalid operator identity");
        }
        return Boolean.TRUE.equals(
                tx.execute(
                        s -> {
                            GraphCapacityLock.lock(jdbc);
                            var rows =
                                    jdbc.queryForList(
                                            "SELECT * FROM agent_graph_call_reconciliation WHERE"
                                                    + " id=:id",
                                            Map.of("id", id));
                            if (rows.isEmpty()) {
                                throw new BusinessException(
                                        ErrorCode.NOT_FOUND, "Reconciliation does not exist");
                            }
                            var evidence = rows.getFirst();
                            if (actor.equals(evidence.get("proposed_by"))) {
                                throw conflict(
                                        "A different operator must independently verify the"
                                                + " evidence");
                            }
                            if (!Objects.equals(
                                    expectedEvidenceSha256, evidence.get("evidence_sha256"))) {
                                throw conflict(
                                        "Evidence digest does not match the reviewed proposal");
                            }
                            var permit = permit((UUID) evidence.get("token"));
                            if (jdbc.queryForObject(
                                            "SELECT count(*) FROM"
                                                + " agent_graph_call_reconciliation_approval WHERE"
                                                + " reconciliation_id=:id",
                                            Map.of("id", id),
                                            Integer.class)
                                    == 1) {
                                return false;
                            }
                            if (!"QUARANTINED".equals(permit.get("state"))) {
                                throw conflict("Call is no longer quarantined");
                            }
                            for (String field :
                                    List.of(
                                            "tenant_code",
                                            "run_id",
                                            "operation_id",
                                            "request_digest",
                                            "generation")) {
                                if (!Objects.equals(permit.get(field), evidence.get(field))) {
                                    throw conflict("Call identity changed");
                                }
                            }
                            jdbc.update(
                                    "INSERT INTO"
                                        + " agent_graph_call_reconciliation_approval(reconciliation_id,approved_by)"
                                        + " VALUES(:id,:actor)",
                                    Map.of("id", id, "actor", actor));
                            jdbc.update(
                                    "UPDATE agent_graph_call_permit SET"
                                        + " state='RELEASED',resolution=:resolution,settled_at=clock_timestamp()"
                                        + " WHERE token=:token AND state='QUARANTINED'",
                                    Map.of(
                                            "token",
                                            permit.get("token"),
                                            "resolution",
                                            "operator-attested:" + id));
                            return true;
                        }));
    }

    private Map<String, Object> permit(UUID token) {
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM agent_graph_call_permit WHERE token=:token FOR UPDATE",
                        Map.of("token", token));
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Call does not exist");
        }
        return rows.getFirst();
    }

    private void validate(Evidence e, String actor) {
        try {
            if (e == null
                    || e.token() == null
                    || e.externalRequestId() == null
                    || e.externalRequestId().isBlank()
                    || e.externalRequestId().length() > 256
                    || !List.of("COMPLETED", "CANCELLED", "NOT_ACCEPTED").contains(e.outcome())
                    || e.evidenceUri() == null
                    || e.evidenceUri().length() > 2048
                    || e.evidenceSha256() == null
                    || !e.evidenceSha256().matches("[a-f0-9]{64}")
                    || actor == null
                    || actor.isBlank()
                    || actor.length() > 128) {
                throw new IllegalArgumentException();
            }
            var uri = URI.create(e.evidenceUri());
            if (!"https".equals(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "Provide the external terminal receipt, HTTPS evidence location, and SHA-256"
                            + " digest");
        }
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }
}
