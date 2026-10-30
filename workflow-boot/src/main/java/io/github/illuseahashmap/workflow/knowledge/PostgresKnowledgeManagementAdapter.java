package io.github.illuseahashmap.workflow.knowledge;

import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeManagementPort;
import io.github.illuseahashmap.knowledge.catalog.domain.DocumentContent;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** PostgreSQL adapter; all statements are tenant-scoped and profile publication is atomic. */
@Component
public class PostgresKnowledgeManagementAdapter implements KnowledgeManagementPort {
    private record ExistingDocument(long id, String content, String hash) {}
    private final NamedParameterJdbcTemplate jdbc;

    public PostgresKnowledgeManagementAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Page<ProfileView> profiles(String tenant, int page, int size, String keyword, String status) {
        int offset = (page - 1) * size;
        MapSqlParameterSource p = params(tenant).addValue("offset", offset).addValue("size", size)
                .addValue("keyword", keyword == null ? null : "%" + keyword.trim() + "%")
                .addValue("status", status);
        long total = jdbc.queryForObject("""
                SELECT count(*) FROM knowledge_retrieval_profile_version
                WHERE tenant_code=:tenant AND (CAST(:keyword AS VARCHAR) IS NULL OR profile_code ILIKE :keyword)
                  AND (CAST(:status AS VARCHAR) IS NULL OR status=:status)
                """, p, Long.class);
        List<ProfileView> rows = jdbc.query("""
                SELECT id, profile_code, version, strategy, max_results, minimum_score,
                       index_version_id, status, created_at
                FROM knowledge_retrieval_profile_version
                WHERE tenant_code=:tenant AND (CAST(:keyword AS VARCHAR) IS NULL OR profile_code ILIKE :keyword)
                  AND (CAST(:status AS VARCHAR) IS NULL OR status=:status)
                ORDER BY profile_code, version DESC LIMIT :size OFFSET :offset
                """, p, (r, n) -> new ProfileView(r.getLong("id"), r.getString("profile_code"),
                r.getInt("version"), r.getString("strategy"), r.getInt("max_results"),
                r.getDouble("minimum_score"), r.getLong("index_version_id"), r.getString("status"),
                List.of(), instant(r.getTimestamp("created_at"))));
        if (!rows.isEmpty()) {
            java.util.Map<Long, List<String>> scopes = new java.util.HashMap<>();
            jdbc.queryForList("""
                    SELECT profile_version_id, scope_code FROM knowledge_retrieval_profile_scope
                    WHERE tenant_code=:tenant AND profile_version_id IN (:ids) ORDER BY scope_code
                    """, params(tenant).addValue("ids", rows.stream().map(ProfileView::id).toList()))
                    .forEach(row -> scopes.computeIfAbsent(((Number) row.get("profile_version_id")).longValue(),
                            ignored -> new java.util.ArrayList<>()).add((String) row.get("scope_code")));
            rows = rows.stream().map(row -> new ProfileView(row.id(), row.profileCode(), row.version(),
                    row.strategy(), row.maxResults(), row.minimumScore(), row.indexVersionId(), row.status(),
                    scopes.getOrDefault(row.id(), List.of()), row.createdAt())).toList();
        }
        return new Page<>(total, page, size, rows);
    }

    @Override
    public List<IndexView> indexVersions(String tenant, String status) {
        return jdbc.query("""
                SELECT id, source_code, version, embedding_model, status, created_at
                FROM knowledge_index_version
                WHERE tenant_code=:tenant AND (CAST(:status AS VARCHAR) IS NULL OR status=:status)
                ORDER BY source_code, version DESC
                """, params(tenant).addValue("status", status), (r, n) -> new IndexView(
                r.getLong("id"), r.getString("source_code"), r.getInt("version"),
                r.getString("embedding_model"), r.getString("status"),
                instant(r.getTimestamp("created_at"))));
    }

    @Override
    @Transactional
    public ProfileView createProfile(String tenant, CreateProfile c) {
        requireText(c.profileCode(), "profileCode");
        requireText(c.strategy(), "strategy");
        io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest.StrategyHint strategy;
        try {
            strategy = io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest.StrategyHint.valueOf(c.strategy());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Unknown retrieval strategy");
        }
        if (strategy == io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest.StrategyHint.GRAPH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Graph retrieval is not available");
        }
        if (c.maxResults() < 1 || c.maxResults() > 50 || !Double.isFinite(c.minimumScore())
                || c.minimumScore() < 0 || c.minimumScore() > 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "invalid retrieval limits");
        }
        if (!indexExists(tenant, c.indexVersionId(), strategy)) {
            throw new BusinessException(ErrorCode.CONFLICT, "index version is not available for this tenant");
        }
        Integer next = jdbc.queryForObject("""
                INSERT INTO knowledge_profile_identity(tenant_code,profile_code,last_version)
                VALUES(:tenant,:code,1)
                ON CONFLICT(tenant_code,profile_code) DO UPDATE
                SET last_version=knowledge_profile_identity.last_version+1 RETURNING last_version
                """, params(tenant).addValue("code", c.profileCode()), Integer.class);
        long id = jdbc.queryForObject("""
                INSERT INTO knowledge_retrieval_profile_version
                  (tenant_code, profile_code, version, strategy, max_results, minimum_score, index_version_id, status)
                VALUES (:tenant,:code,:version,:strategy,:maxResults,:minimumScore,:indexVersion,'DRAFT') RETURNING id
                """, params(tenant).addValue("code", c.profileCode()).addValue("version", next)
                .addValue("strategy", c.strategy()).addValue("maxResults", c.maxResults())
                .addValue("minimumScore", c.minimumScore()).addValue("indexVersion", c.indexVersionId()), Long.class);
        insertScopes(tenant, id, c.knowledgeScopes());
        return findProfile(tenant, id);
    }

    @Override
    @Transactional
    public ProfileView publishProfile(String tenant, long id) {
        int updated = jdbc.update("""
                UPDATE knowledge_retrieval_profile_version SET status = 'PUBLISHED'
                WHERE id = :id AND tenant_code = :tenant AND status = 'DRAFT'
                """, params(tenant).addValue("id", id));
        if (updated != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "only a tenant draft profile can be published");
        }
        return findProfile(tenant, id);
    }

    @Override
    @Transactional
    public DocumentView createDocument(String tenant, CreateDocument c) {
        requireText(c.sourceCode(), "sourceCode");
        requireText(c.externalDocumentId(), "externalDocumentId");
        requireText(c.contentHash(), "contentHash");
        requireText(c.content(), "content");
        DocumentContent content = DocumentContent.from(c.content());
        if (c.version() < 1 || !content.sha256().equalsIgnoreCase(c.contentHash())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Invalid document version or content SHA-256");
        }
        jdbc.update("""
                INSERT INTO knowledge_source(tenant_code, source_code, name)
                VALUES(:tenant, :source, :name) ON CONFLICT (tenant_code, source_code) DO NOTHING
                """, params(tenant).addValue("source", c.sourceCode())
                .addValue("name", c.sourceName() == null ? c.sourceCode() : c.sourceName()));
        jdbc.queryForObject("""
                SELECT id FROM knowledge_source
                WHERE tenant_code = :tenant AND source_code = :source
                FOR UPDATE
                """, params(tenant).addValue("source", c.sourceCode()),
                Long.class);
        var identity = params(tenant).addValue("source", c.sourceCode())
                .addValue("externalId", c.externalDocumentId()).addValue("version", c.version());
        var existing = jdbc.query("""
                SELECT d.id, c.content, d.content_hash FROM knowledge_document_version d
                JOIN knowledge_document_content c ON c.document_version_id=d.id
                WHERE d.tenant_code=:tenant AND d.source_code=:source
                  AND d.external_document_id=:externalId AND d.version=:version
                """, identity, (r, n) -> new ExistingDocument(r.getLong("id"), r.getString("content"), r.getString("content_hash")));
        if (!existing.isEmpty() && !content.text().equals(existing.getFirst().content())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Document version is immutable; create a new version");
        }
        if (!existing.isEmpty() && !content.sha256().equals(existing.getFirst().hash())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Stored document fingerprint requires integrity review");
        }
        long documentId = existing.isEmpty() ? jdbc.queryForObject("""
                INSERT INTO knowledge_document_version(tenant_code,source_code,external_document_id,version,content_hash,status)
                VALUES(:tenant,:source,:externalId,:version,:hash,'READY')
                ON CONFLICT (tenant_code,source_code,external_document_id,version)
                DO NOTHING
                RETURNING id
                """, identity.addValue("hash", content.sha256()), Long.class) : existing.getFirst().id();
        jdbc.update("""
                INSERT INTO knowledge_document_content(document_version_id,tenant_code,source_code,content)
                VALUES(:id,:tenant,:source,:content) ON CONFLICT (document_version_id)
                DO NOTHING
                """, params(tenant).addValue("id", documentId).addValue("source", c.sourceCode()).addValue("content", content.text()));
        jdbc.update("""
                INSERT INTO knowledge_ingestion_job(tenant_code,source_code,document_hash,document_version_id,status)
                VALUES(:tenant,:source,:hash,:documentId,'QUEUED') ON CONFLICT (tenant_code,document_version_id)
                DO NOTHING
                """, params(tenant).addValue("source", c.sourceCode()).addValue("hash", content.sha256())
                .addValue("documentId", documentId));
        long job = jdbc.queryForObject("""
                SELECT id FROM knowledge_ingestion_job
                WHERE tenant_code=:tenant AND document_version_id=:documentId
                """, params(tenant).addValue("documentId", documentId), Long.class);
        return jdbc.queryForObject("""
                SELECT id, source_code, external_document_id, version, content_hash, status, created_at
                FROM knowledge_document_version WHERE id = :id AND tenant_code = :tenant
                """, params(tenant).addValue("id", documentId), (r, n) -> new DocumentView(
                r.getLong("id"), r.getString("source_code"), r.getString("external_document_id"),
                r.getInt("version"), r.getString("content_hash"), r.getString("status"), job,
                instant(r.getTimestamp("created_at"))));
    }

    @Override
    public Page<JobView> ingestionJobs(String tenant, int page, int size, String status) {
        int offset = (page - 1) * size;
        var p = params(tenant).addValue("offset", offset).addValue("size", size).addValue("status", status);
        long total = jdbc.queryForObject("""
                SELECT count(*) FROM knowledge_ingestion_job
                WHERE tenant_code = :tenant
                  AND (CAST(:status AS VARCHAR) IS NULL OR status = :status)
                """, p, Long.class);
        List<JobView> rows = jdbc.query("""
                SELECT id, source_code, document_hash, status, attempt, available_at, error_code, updated_at
                FROM knowledge_ingestion_job
                WHERE tenant_code=:tenant AND (CAST(:status AS VARCHAR) IS NULL OR status=:status)
                ORDER BY updated_at DESC, id DESC LIMIT :size OFFSET :offset
                """, p, (r,n) -> new JobView(r.getLong("id"), r.getString("source_code"),
                r.getString("document_hash"), r.getString("status"), r.getInt("attempt"),
                instant(r.getTimestamp("available_at")),
                r.getString("error_code"), instant(r.getTimestamp("updated_at"))));
        return new Page<>(total, page, size, rows);
    }

    @Override
    public List<ScopeGrantView> grants(String tenant, String principal) {
        return jdbc.query("""
                SELECT principal_id, scope_code, created_at
                FROM knowledge_principal_scope_grant
                WHERE tenant_code = :tenant AND principal_id = :principal
                ORDER BY scope_code
                """, params(tenant).addValue("principal", principal),
                (r, n) -> new ScopeGrantView(r.getString(1), r.getString(2),
                        instant(r.getTimestamp(3))));
    }

    @Override
    @Transactional
    public void grant(String tenant, GrantScope command) {
        jdbc.update("""
                INSERT INTO knowledge_principal_scope_grant(tenant_code, principal_id, scope_code)
                VALUES(:tenant, :principal, :scope) ON CONFLICT DO NOTHING
                """, params(tenant).addValue("principal", command.principalId())
                .addValue("scope", command.scopeCode()));
    }

    @Override
    @Transactional
    public void revoke(String tenant, GrantScope command) {
        jdbc.update("""
                DELETE FROM knowledge_principal_scope_grant
                WHERE tenant_code = :tenant AND principal_id = :principal AND scope_code = :scope
                """, params(tenant).addValue("principal", command.principalId())
                .addValue("scope", command.scopeCode()));
    }

    private ProfileView findProfile(String tenant, long id) {
        return jdbc.queryForObject("""
                SELECT id, profile_code, version, strategy, max_results, minimum_score,
                       index_version_id, status, created_at
                FROM knowledge_retrieval_profile_version
                WHERE id = :id AND tenant_code = :tenant
                """, params(tenant).addValue("id", id),
                (r, n) -> profile(r.getLong(1), tenant, r.getString(2), r.getInt(3),
                        r.getString(4), r.getInt(5), r.getDouble(6), r.getLong(7),
                        r.getString(8), instant(r.getTimestamp(9))));
    }

    private ProfileView profile(long id, String tenant, String code, int version,
                                String strategy, int max, double minimumScore,
                                long index, String status, Instant createdAt) {
        List<String> scopes = jdbc.query("""
                SELECT scope_code FROM knowledge_retrieval_profile_scope
                WHERE tenant_code = :tenant AND profile_version_id = :id ORDER BY scope_code
                """, params(tenant).addValue("id", id), (r, n) -> r.getString(1));
        return new ProfileView(id, code, version, strategy, max, minimumScore,
                index, status, scopes, createdAt);
    }

    private void insertScopes(String tenant, long id, List<String> scopes) {
        if (scopes == null) {
            return;
        }
        scopes.stream().filter(scope -> scope != null && !scope.isBlank()).distinct()
                .forEach(scope -> jdbc.update("""
                        INSERT INTO knowledge_retrieval_profile_scope(
                            profile_version_id, tenant_code, scope_code)
                        VALUES(:id, :tenant, :scope)
                        """, params(tenant).addValue("id", id).addValue("scope", scope.trim())));
    }

    private boolean indexExists(String tenant, long id,
            io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest.StrategyHint strategy) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM knowledge_index_version
                WHERE tenant_code = :tenant AND id = :id AND status IN ('READY', 'ACTIVE')
                  AND (:requiresVector = false OR embedding_model <> 'none')
                """, params(tenant).addValue("id", id)
                .addValue("requiresVector", strategy == io.github.illuseahashmap.knowledge.retrieval.domain
                        .RetrievalRequest.StrategyHint.VECTOR || strategy == io.github.illuseahashmap
                        .knowledge.retrieval.domain.RetrievalRequest.StrategyHint.HYBRID), Integer.class);
        return count != null && count > 0;
    }

    private Instant instant(Timestamp value) {
        return value == null ? Instant.EPOCH : value.toInstant();
    }

    private MapSqlParameterSource params(String tenant) {
        return new MapSqlParameterSource("tenant", tenant);
    }

    private void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, name + " must not be blank");
        }
    }
}
