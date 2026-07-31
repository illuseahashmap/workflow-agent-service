package io.github.illuseahashmap.workflow.tenant.infrastructure.persistence;

import io.github.illuseahashmap.workflow.shared.response.PageResult;
import io.github.illuseahashmap.workflow.tenant.domain.WorkflowTenant;
import io.github.illuseahashmap.workflow.tenant.domain.WorkflowTenantRepository;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class JdbcWorkflowTenantRepository implements WorkflowTenantRepository {

    private static final String SELECT_COLUMNS = """
            SELECT id, tenant_id, tenant_code, tenant_name, description, enabled, created_at, updated_at
            FROM workflow_tenant
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcWorkflowTenantRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public PageResult<WorkflowTenant> page(int pageNum, int pageSize, String keyword, Boolean enabled) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> arguments = new ArrayList<>();
        if (StringUtils.hasText(keyword)) {
            where.append(" AND (tenant_id ILIKE ? OR tenant_code ILIKE ? OR tenant_name ILIKE ?)");
            String pattern = "%" + keyword + "%";
            arguments.add(pattern);
            arguments.add(pattern);
            arguments.add(pattern);
        }
        if (enabled != null) {
            where.append(" AND enabled = ?");
            arguments.add(enabled ? 1 : 0);
        }
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_tenant" + where, Long.class, arguments.toArray());
        arguments.add(pageSize);
        arguments.add((pageNum - 1) * pageSize);
        List<WorkflowTenant> records = jdbcTemplate.query(
                SELECT_COLUMNS + where + " ORDER BY updated_at DESC, id ASC LIMIT ? OFFSET ?",
                this::mapTenant,
                arguments.toArray());
        return new PageResult<>(total == null ? 0 : total, pageNum, pageSize, records);
    }

    @Override
    public List<WorkflowTenant> findEnabled() {
        return jdbcTemplate.query(
                SELECT_COLUMNS + " WHERE enabled = 1 ORDER BY tenant_name ASC, id ASC", this::mapTenant);
    }

    @Override
    public Optional<WorkflowTenant> findById(long id) {
        return jdbcTemplate.query(SELECT_COLUMNS + " WHERE id = ?", this::mapTenant, id).stream().findFirst();
    }

    @Override
    public WorkflowTenant save(WorkflowTenant tenant) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO workflow_tenant
                        (tenant_id, tenant_code, tenant_name, description, enabled, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, tenant.tenantId());
            statement.setString(2, tenant.tenantCode());
            statement.setString(3, tenant.tenantName());
            statement.setString(4, tenant.description());
            statement.setInt(5, tenant.enabled() ? 1 : 0);
            return statement;
        }, keyHolder);
        Number id = keyHolder.getKey();
        return findById(id.longValue()).orElseThrow();
    }

    @Override
    public void update(WorkflowTenant tenant) {
        jdbcTemplate.update("""
                UPDATE workflow_tenant
                SET tenant_id = ?, tenant_code = ?, tenant_name = ?, description = ?, enabled = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, tenant.tenantId(), tenant.tenantCode(), tenant.tenantName(), tenant.description(),
                tenant.enabled() ? 1 : 0, tenant.id());
    }

    @Override
    public void updateEnabled(long id, boolean enabled) {
        jdbcTemplate.update("""
                UPDATE workflow_tenant SET enabled = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, enabled ? 1 : 0, id);
    }

    private WorkflowTenant mapTenant(java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
        return new WorkflowTenant(
                resultSet.getLong("id"),
                resultSet.getString("tenant_id"),
                resultSet.getString("tenant_code"),
                resultSet.getString("tenant_name"),
                resultSet.getString("description"),
                resultSet.getInt("enabled") == 1,
                resultSet.getObject("created_at", java.time.OffsetDateTime.class),
                resultSet.getObject("updated_at", java.time.OffsetDateTime.class));
    }
}
