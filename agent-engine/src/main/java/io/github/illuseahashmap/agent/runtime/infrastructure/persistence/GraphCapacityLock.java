package io.github.illuseahashmap.agent.runtime.infrastructure.persistence;

import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.require;

/** Global short-transaction mutex. Always lock before Run rows; never hold across I/O. */
final class GraphCapacityLock {
    private GraphCapacityLock() { }

    static Policy lock(NamedParameterJdbcTemplate jdbc) {
        // A tenant-filtered COUNT cannot enforce a global limit. Never elevate a caller here.
        Boolean system = jdbc.getJdbcTemplate().queryForObject("""
                SELECT COALESCE(current_setting('app.system_worker',true)='true',false)
                    OR EXISTS (SELECT 1 FROM pg_roles WHERE rolname=current_user AND rolsuper)
                """, Boolean.class);
        require(Boolean.TRUE.equals(system));
        var rows = jdbc.query("SELECT * FROM agent_graph_capacity_policy WHERE id=1 FOR UPDATE", Map.of(),
                (rs, n) -> new Policy(rs.getInt("max_outstanding"), rs.getInt("tenant_outstanding"),
                        rs.getInt("max_active"), rs.getInt("tenant_active"), rs.getInt("max_calls"), rs.getInt("tenant_calls")));
        require(rows.size() == 1);
        return rows.getFirst();
    }

    record Policy(int outstanding, int tenantOutstanding, int active, int tenantActive, int calls, int tenantCalls) { }
}
