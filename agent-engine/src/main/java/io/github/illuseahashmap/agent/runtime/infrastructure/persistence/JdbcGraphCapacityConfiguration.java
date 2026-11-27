package io.github.illuseahashmap.agent.runtime.infrastructure.persistence;

import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayCodec;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/** Administrative policy changes share the runtime capacity mutex; no running work is cancelled. */
public final class JdbcGraphCapacityConfiguration {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcGraphCapacityConfiguration(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
        transaction =
                new TransactionTemplate(
                        new DataSourceTransactionManager(
                                Objects.requireNonNull(jdbc.getJdbcTemplate().getDataSource())));
        transaction.setTimeout(5);
    }

    public record Limits(Integer outstanding, Integer active, Integer calls) {}

    public record Usage(long outstanding, long active, long calls) {}

    public record View(
            long revision,
            Limits platform,
            Limits defaults,
            Limits overrides,
            Limits effective,
            Usage usage) {}

    public record PlatformUpdate(long revision, Limits platform, Limits defaults) {}

    public record TenantUpdate(long revision, Limits overrides) {}

    public View read(String tenant) {
        return transaction.execute(
                status -> {
                    var policy = GraphCapacityLock.lock(jdbc);
                    tenantExists(tenant);
                    return view(tenant, policy);
                });
    }

    public View updatePlatform(PlatformUpdate command, String actor) {
        if (command == null) {
            throw invalid();
        }
        check(command.platform(), false);
        check(command.defaults(), false);
        within(command.defaults(), command.platform());
        return transaction.execute(
                status -> {
                    var policy = GraphCapacityLock.lock(jdbc);
                    checkRevision(command.revision());
                    var before = view(null, policy);
                    // Native Run admission uses the same outstanding limits. Lock its counter
                    // before edits.
                    jdbc.queryForObject(
                            "SELECT used FROM agent_run_admission WHERE scope='GLOBAL' FOR UPDATE",
                            Map.of(),
                            Long.class);
                    jdbc.update(
                            """
                            UPDATE agent_graph_capacity_policy SET max_outstanding=:outstanding,max_active=:active,max_calls=:calls,
                                tenant_outstanding=:defaultOutstanding,tenant_active=:defaultActive,tenant_calls=:defaultCalls,
                                configuration_revision=configuration_revision+1 WHERE id=1
                            """,
                            params(command.platform())
                                    .addValue(
                                            "defaultOutstanding", command.defaults().outstanding())
                                    .addValue("defaultActive", command.defaults().active())
                                    .addValue("defaultCalls", command.defaults().calls()));
                    jdbc.update(
                            "UPDATE agent_run_admission SET"
                                + " hard_limit=:limit,tenant_limit=:defaultLimit WHERE"
                                + " scope='GLOBAL'",
                            Map.of(
                                    "limit",
                                    command.platform().outstanding(),
                                    "defaultLimit",
                                    command.defaults().outstanding()));
                    jdbc.update(
                            """
                            UPDATE agent_run_admission a SET hard_limit=LEAST(:limit,COALESCE(
                                (SELECT c.outstanding FROM agent_graph_tenant_capacity c WHERE a.scope='TENANT:'||c.tenant_code),:defaultLimit)),
                                tenant_limit=:defaultLimit WHERE a.scope LIKE 'TENANT:%'
                            """,
                            Map.of(
                                    "limit",
                                    command.platform().outstanding(),
                                    "defaultLimit",
                                    command.defaults().outstanding()));
                    var after = view(null, GraphCapacityLock.lock(jdbc));
                    audit(actor, "PLATFORM", before, after);
                    return after;
                });
    }

    public View updateTenant(String tenant, TenantUpdate command, String actor) {
        if (command == null || tenant == null) {
            throw invalid();
        }
        check(command.overrides(), true);
        return transaction.execute(
                status -> {
                    var policy = GraphCapacityLock.lock(jdbc);
                    tenantExists(tenant);
                    checkRevision(command.revision());
                    var before = view(tenant, policy);
                    within(command.overrides(), before.platform());
                    jdbc.queryForObject(
                            "SELECT used FROM agent_run_admission WHERE scope='GLOBAL' FOR UPDATE",
                            Map.of(),
                            Long.class);
                    jdbc.update(
                            """
                            INSERT INTO agent_graph_tenant_capacity(tenant_code,outstanding,active,calls) VALUES(:tenant,:outstanding,:active,:calls)
                            ON CONFLICT(tenant_code) DO UPDATE SET outstanding=EXCLUDED.outstanding,active=EXCLUDED.active,calls=EXCLUDED.calls
                            """,
                            params(command.overrides()).addValue("tenant", tenant));
                    int limit =
                            Math.min(
                                    policy.outstanding(),
                                    command.overrides().outstanding() == null
                                            ? policy.tenantOutstanding()
                                            : command.overrides().outstanding());
                    jdbc.update(
                            """
                            INSERT INTO agent_run_admission(scope,used,hard_limit,tenant_limit) VALUES(:scope,0,:limit,:limit)
                            ON CONFLICT(scope) DO UPDATE SET hard_limit=EXCLUDED.hard_limit,tenant_limit=EXCLUDED.tenant_limit
                            """,
                            Map.of("scope", "TENANT:" + tenant, "limit", limit));
                    jdbc.update(
                            "UPDATE agent_graph_capacity_policy SET"
                                + " configuration_revision=configuration_revision+1 WHERE id=1",
                            Map.of());
                    var after = view(tenant, policy);
                    audit(actor, tenant, before, after);
                    return after;
                });
    }

    private View view(String tenant, GraphCapacityLock.Policy policy) {
        var overrides =
                tenant == null
                        ? new Limits(null, null, null)
                        : jdbc
                                .query(
                                        "SELECT outstanding,active,calls FROM"
                                            + " agent_graph_tenant_capacity WHERE"
                                            + " tenant_code=:tenant",
                                        Map.of("tenant", tenant),
                                        (rs, n) ->
                                                new Limits(
                                                        (Integer) rs.getObject(1),
                                                        (Integer) rs.getObject(2),
                                                        (Integer) rs.getObject(3)))
                                .stream()
                                .findFirst()
                                .orElse(new Limits(null, null, null));
        var effective =
                tenant == null
                        ? new Limits(policy.outstanding(), policy.active(), policy.calls())
                        : limits(GraphCapacityLock.tenant(jdbc, policy, tenant));
        var key =
                new MapSqlParameterSource("tenant", tenant)
                        .addValue("scope", tenant == null ? "GLOBAL" : "TENANT:" + tenant);
        long outstanding =
                jdbc.queryForObject(
                        "SELECT COALESCE((SELECT used FROM agent_run_admission WHERE"
                            + " scope=:scope),0)",
                        key,
                        Long.class);
        long active =
                jdbc.queryForObject(
                        """
                        SELECT count(*) FROM agent_graph_execution e JOIN agent_graph_dispatch d USING(tenant_code,run_id)
                        WHERE d.status='RUNNING' AND e.state='ACTIVE' AND e.lease_expires_at>clock_timestamp()
                            AND (CAST(:tenant AS text) IS NULL OR e.tenant_code=:tenant)
                        """,
                        key,
                        Long.class);
        long calls =
                jdbc.queryForObject(
                        """
                        SELECT count(*) FROM agent_graph_call_permit WHERE state<>'RELEASED'
                            AND (CAST(:tenant AS text) IS NULL OR tenant_code=:tenant)
                        """,
                        key,
                        Long.class);
        return new View(
                revision(),
                new Limits(policy.outstanding(), policy.active(), policy.calls()),
                limits(policy),
                overrides,
                effective,
                new Usage(outstanding, active, calls));
    }

    private Limits limits(GraphCapacityLock.Policy policy) {
        return new Limits(policy.tenantOutstanding(), policy.tenantActive(), policy.tenantCalls());
    }

    private long revision() {
        return jdbc.queryForObject(
                "SELECT configuration_revision FROM agent_graph_capacity_policy WHERE id=1",
                Map.of(),
                Long.class);
    }

    private void checkRevision(long expected) {
        if (expected != revision()) {
            throw new BusinessException(ErrorCode.CONFLICT, "配额已被其他管理员修改，请刷新后重试");
        }
    }

    private void tenantExists(String tenant) {
        if (tenant != null
                && jdbc.queryForObject(
                                "SELECT count(*) FROM workflow_tenant WHERE tenant_code=:tenant",
                                Map.of("tenant", tenant),
                                Integer.class)
                        != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "租户不存在");
        }
    }

    private void audit(String actor, String scope, View before, View after) {
        if (actor == null || actor.isBlank() || actor.length() > 256) {
            throw invalid();
        }
        String json =
                new String(
                        new GraphGatewayCodec()
                                .write(
                                        Map.of(
                                                "type",
                                                "CAPACITY",
                                                "scope",
                                                scope,
                                                "before",
                                                before,
                                                "after",
                                                after)),
                        StandardCharsets.UTF_8);
        jdbc.update(
                "INSERT INTO agent_graph_resource_audit(actor,patch_json) VALUES(:actor,CAST(:patch"
                    + " AS jsonb))",
                Map.of("actor", actor, "patch", json));
    }

    private MapSqlParameterSource params(Limits limits) {
        return new MapSqlParameterSource()
                .addValue("outstanding", limits.outstanding())
                .addValue("active", limits.active())
                .addValue("calls", limits.calls());
    }

    private void check(Limits limits, boolean nullable) {
        if (limits == null) {
            throw invalid();
        }
        for (Integer value :
                new Integer[] {limits.outstanding(), limits.active(), limits.calls()}) {
            if (value == null ? !nullable : value < 1 || value > 1000000) {
                throw invalid();
            }
        }
    }

    private void within(Limits values, Limits ceiling) {
        if ((values.outstanding() != null && values.outstanding() > ceiling.outstanding())
                || (values.active() != null && values.active() > ceiling.active())
                || (values.calls() != null && values.calls() > ceiling.calls())) {
            throw invalid();
        }
    }

    private BusinessException invalid() {
        return new BusinessException(ErrorCode.BAD_REQUEST, "配额必须是 1 至 1000000 的整数，租户配额不得超过平台上限");
    }
}
