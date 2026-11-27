package io.github.illuseahashmap.workflow.knowledge;

import io.github.illuseahashmap.knowledge.retrieval.domain.SourceIndexBinding;
import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeManagementPort.CreateProfile;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Frozen source membership is explicit; scopes alone never imply a live index lookup. */
final class FrozenProfileIndexes {
    private FrozenProfileIndexes() {}

    static List<SourceIndexBinding> read(NamedParameterJdbcTemplate jdbc, String tenant, long id) {
        return jdbc.query("""
                SELECT source_code,index_version_id FROM knowledge_profile_index_binding
                WHERE tenant_code=:tenant AND profile_version_id=:id ORDER BY source_code
                """, Map.of("tenant", tenant, "id", id),
                (row, n) -> new SourceIndexBinding(row.getString(1), row.getLong(2)));
    }

    static List<SourceIndexBinding> validate(NamedParameterJdbcTemplate jdbc, String tenant, CreateProfile command) {
        List<SourceIndexBinding> bindings = command.indexBindings() == null ? List.of() : command.indexBindings();
        if (bindings.isEmpty()) {
            bindings = jdbc.query("""
                    SELECT source_code,id FROM knowledge_index_version
                    WHERE tenant_code=:tenant AND id=:id AND status IN ('ACTIVE','RETIRED') FOR SHARE
                    """, Map.of("tenant", tenant, "id", command.indexVersionId()),
                    (row, n) -> new SourceIndexBinding(row.getString(1), row.getLong(2)));
        }
        if (bindings.isEmpty() || bindings.size() > 100 || command.knowledgeScopes() == null
                || bindings.stream().map(SourceIndexBinding::sourceCode).distinct().count() != bindings.size()
                || !new java.util.HashSet<>(command.knowledgeScopes()).equals(bindings.stream()
                        .map(SourceIndexBinding::sourceCode).collect(java.util.stream.Collectors.toSet()))
                || bindings.stream().noneMatch(binding -> binding.indexVersionId() == command.indexVersionId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Every knowledge scope requires exactly one frozen source index");
        }
        var models = new java.util.HashSet<String>();
        for (var binding : bindings) {
            var found = jdbc.query("""
                    SELECT embedding_model FROM knowledge_index_version
                    WHERE tenant_code=:tenant AND id=:id AND source_code=:source
                      AND status IN ('ACTIVE','RETIRED') FOR SHARE
                    """, Map.of("tenant", tenant, "id", binding.indexVersionId(), "source", binding.sourceCode()),
                    (row, n) -> row.getString(1));
            if (found.size() != 1) { throw new BusinessException(ErrorCode.CONFLICT, "Frozen source index is unavailable"); }
            models.add(found.getFirst());
        }
        if (("VECTOR".equals(command.strategy()) || "HYBRID".equals(command.strategy()))
                && (models.size() != 1 || models.contains("none"))) {
            throw new BusinessException(ErrorCode.CONFLICT, "Vector snapshot members require one compatible embedding recipe");
        }
        return List.copyOf(bindings);
    }
}
