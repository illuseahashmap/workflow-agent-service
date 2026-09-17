package io.github.illuseahashmap.workflow.knowledge;

import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeManagementPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** PostgreSQL adapter; all statements are tenant-scoped and profile publication is atomic. */
@Component
public class PostgresKnowledgeManagementAdapter implements KnowledgeManagementPort {
    private final NamedParameterJdbcTemplate jdbc;

    public PostgresKnowledgeManagementAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

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
                """, p, (r, n) -> profile(r.getLong("id"), tenant, r.getString("profile_code"),
                r.getInt("version"), r.getString("strategy"), r.getInt("max_results"),
                r.getDouble("minimum_score"), r.getLong("index_version_id"), r.getString("status"),
                instant(r.getTimestamp("created_at"))));
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
        if (c.maxResults() < 1 || c.maxResults() > 50 || c.minimumScore() < 0) throw new IllegalArgumentException("invalid retrieval limits");
        if (!indexExists(tenant, c.indexVersionId())) throw new IllegalArgumentException("index version is not available for this tenant");
        Integer next = jdbc.queryForObject("SELECT COALESCE(MAX(version),0)+1 FROM knowledge_retrieval_profile_version WHERE tenant_code=:tenant AND profile_code=:code", params(tenant).addValue("code", c.profileCode()), Integer.class);
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
        int updated = jdbc.update("UPDATE knowledge_retrieval_profile_version SET status='PUBLISHED' WHERE id=:id AND tenant_code=:tenant AND status='DRAFT'", params(tenant).addValue("id", id));
        if (updated != 1) throw new IllegalArgumentException("only a tenant draft profile can be published");
        return findProfile(tenant, id);
    }

    @Override
    @Transactional
    public DocumentView createDocument(String tenant, CreateDocument c) {
        requireText(c.sourceCode(), "sourceCode"); requireText(c.externalDocumentId(), "externalDocumentId");
        requireText(c.contentHash(), "contentHash"); requireText(c.content(), "content");
        Long sourceId = jdbc.query("SELECT id FROM knowledge_source WHERE tenant_code=:tenant AND source_code=:source", params(tenant).addValue("source", c.sourceCode()), r -> r.next() ? r.getLong(1) : null);
        if (sourceId == null) jdbc.update("INSERT INTO knowledge_source(tenant_code,source_code,name) VALUES(:tenant,:source,:name)", params(tenant).addValue("source", c.sourceCode()).addValue("name", c.sourceName() == null ? c.sourceCode() : c.sourceName()));
        long documentId = jdbc.queryForObject("""
                INSERT INTO knowledge_document_version(tenant_code,source_code,external_document_id,version,content_hash,status)
                VALUES(:tenant,:source,:externalId,:version,:hash,'READY')
                ON CONFLICT (tenant_code,source_code,external_document_id,version)
                DO UPDATE SET content_hash=EXCLUDED.content_hash, status='READY'
                RETURNING id
                """, params(tenant).addValue("source", c.sourceCode()).addValue("externalId", c.externalDocumentId())
                .addValue("version", c.version()).addValue("hash", c.contentHash()), Long.class);
        jdbc.update("""
                INSERT INTO knowledge_document_content(document_version_id,tenant_code,source_code,content)
                VALUES(:id,:tenant,:source,:content) ON CONFLICT (document_version_id)
                DO UPDATE SET content=EXCLUDED.content
                """, params(tenant).addValue("id", documentId).addValue("source", c.sourceCode()).addValue("content", c.content()));
        long job = jdbc.queryForObject("""
                INSERT INTO knowledge_ingestion_job(tenant_code,source_code,document_hash,status)
                VALUES(:tenant,:source,:hash,'QUEUED') ON CONFLICT (tenant_code,source_code,document_hash)
                DO UPDATE SET status='QUEUED', available_at=CURRENT_TIMESTAMP, error_code=NULL, updated_at=CURRENT_TIMESTAMP
                RETURNING id
                """, params(tenant).addValue("source", c.sourceCode()).addValue("hash", c.contentHash()), Long.class);
        return jdbc.queryForObject("SELECT id,source_code,external_document_id,version,content_hash,status,created_at FROM knowledge_document_version WHERE id=:id AND tenant_code=:tenant", params(tenant).addValue("id", documentId), (r,n) -> new DocumentView(r.getLong("id"),r.getString("source_code"),r.getString("external_document_id"),r.getInt("version"),r.getString("content_hash"),r.getString("status"),job,instant(r.getTimestamp("created_at"))));
    }

    @Override public Page<JobView> ingestionJobs(String tenant, int page, int size, String status) {
        int offset = (page - 1) * size;
        var p = params(tenant).addValue("offset", offset).addValue("size", size).addValue("status", status);
        long total = jdbc.queryForObject("SELECT count(*) FROM knowledge_ingestion_job WHERE tenant_code=:tenant AND (CAST(:status AS VARCHAR) IS NULL OR status=:status)", p, Long.class);
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
    @Override public List<ScopeGrantView> grants(String tenant, String principal) { return jdbc.query("SELECT principal_id,scope_code,created_at FROM knowledge_principal_scope_grant WHERE tenant_code=:tenant AND principal_id=:principal ORDER BY scope_code", params(tenant).addValue("principal",principal),(r,n)->new ScopeGrantView(r.getString(1),r.getString(2),instant(r.getTimestamp(3)))); }
    @Override @Transactional public void grant(String tenant, GrantScope c) { jdbc.update("INSERT INTO knowledge_principal_scope_grant(tenant_code,principal_id,scope_code) VALUES(:tenant,:principal,:scope) ON CONFLICT DO NOTHING", params(tenant).addValue("principal",c.principalId()).addValue("scope",c.scopeCode())); }
    @Override @Transactional public void revoke(String tenant, GrantScope c) { jdbc.update("DELETE FROM knowledge_principal_scope_grant WHERE tenant_code=:tenant AND principal_id=:principal AND scope_code=:scope", params(tenant).addValue("principal",c.principalId()).addValue("scope",c.scopeCode())); }

    private ProfileView findProfile(String tenant, long id) { return jdbc.queryForObject("SELECT id,profile_code,version,strategy,max_results,minimum_score,index_version_id,status,created_at FROM knowledge_retrieval_profile_version WHERE id=:id AND tenant_code=:tenant",params(tenant).addValue("id",id),(r,n)->profile(r.getLong(1),tenant,r.getString(2),r.getInt(3),r.getString(4),r.getInt(5),r.getDouble(6),r.getLong(7),r.getString(8),instant(r.getTimestamp(9)))); }
    private ProfileView profile(long id,String tenant,String code,int version,String strategy,int max,double min,long index,String status,Instant at){ return new ProfileView(id,code,version,strategy,max,min,index,status,jdbc.query("SELECT scope_code FROM knowledge_retrieval_profile_scope WHERE tenant_code=:tenant AND profile_version_id=:id ORDER BY scope_code",params(tenant).addValue("id",id),(r,n)->r.getString(1)),at); }
    private void insertScopes(String tenant,long id,List<String> scopes){ if(scopes==null)return; scopes.stream().filter(s->s!=null&&!s.isBlank()).distinct().forEach(s->jdbc.update("INSERT INTO knowledge_retrieval_profile_scope(profile_version_id,tenant_code,scope_code) VALUES(:id,:tenant,:scope)",params(tenant).addValue("id",id).addValue("scope",s.trim()))); }
    private boolean indexExists(String tenant,long id){ Integer n=jdbc.queryForObject("SELECT count(*) FROM knowledge_index_version WHERE tenant_code=:tenant AND id=:id AND status IN ('READY','ACTIVE')",params(tenant).addValue("id",id),Integer.class); return n!=null&&n>0; }
    private Instant instant(Timestamp value) { return value == null ? Instant.EPOCH : value.toInstant(); }
    private MapSqlParameterSource params(String tenant){return new MapSqlParameterSource("tenant",tenant);}
    private void requireText(String v,String name){if(v==null||v.isBlank())throw new IllegalArgumentException(name+" must not be blank");}
}
