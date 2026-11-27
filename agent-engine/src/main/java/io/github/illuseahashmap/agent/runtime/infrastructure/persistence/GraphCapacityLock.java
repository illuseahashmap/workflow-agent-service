package io.github.illuseahashmap.agent.runtime.infrastructure.persistence;

import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.require;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.Map;

/** Global short-transaction mutex. Always lock before Run rows; never hold across I/O. */
final class GraphCapacityLock {
    private GraphCapacityLock() {}

    static Policy lock(NamedParameterJdbcTemplate jdbc) {
        // A tenant-filtered COUNT cannot enforce a global limit. Never elevate a caller here.
        Boolean system =
                jdbc.getJdbcTemplate()
                        .queryForObject(
                                """
                                SELECT COALESCE(current_setting('app.system_worker',true)='true',false)
                                    OR EXISTS (SELECT 1 FROM pg_roles WHERE rolname=current_user AND rolsuper)
                                """,
                                Boolean.class);
        require(Boolean.TRUE.equals(system));
        var rows =
                jdbc.query(
                        "SELECT * FROM agent_graph_capacity_policy WHERE id=1 FOR UPDATE",
                        Map.of(),
                        (rs, n) ->
                                new Policy(
                                        rs.getInt("max_outstanding"),
                                        rs.getInt("tenant_outstanding"),
                                        rs.getInt("max_active"),
                                        rs.getInt("tenant_active"),
                                        rs.getInt("max_calls"),
                                        rs.getInt("tenant_calls")));
        require(rows.size() == 1);
        return rows.getFirst();
    }

    static Policy tenant(NamedParameterJdbcTemplate jdbc, Policy defaults, String tenant) {
        var rows =
                jdbc.query(
                        "SELECT * FROM agent_graph_tenant_capacity WHERE tenant_code=:tenant",
                        Map.of("tenant", tenant),
                        (rs, n) ->
                                new Policy(
                                        defaults.outstanding(),
                                        Math.min(
                                                defaults.outstanding(),
                                                rs.getObject("outstanding") == null
                                                        ? defaults.tenantOutstanding()
                                                        : rs.getInt("outstanding")),
                                        defaults.active(),
                                        Math.min(
                                                defaults.active(),
                                                rs.getObject("active") == null
                                                        ? defaults.tenantActive()
                                                        : rs.getInt("active")),
                                        defaults.calls(),
                                        Math.min(
                                                defaults.calls(),
                                                rs.getObject("calls") == null
                                                        ? defaults.tenantCalls()
                                                        : rs.getInt("calls"))));
        return rows.isEmpty() ? defaults : rows.getFirst();
    }

    record Policy(
            int outstanding,
            int tenantOutstanding,
            int active,
            int tenantActive,
            int calls,
            int tenantCalls) {}
}
