package io.github.illuseahashmap.knowledge.retrieval.application.port;

import java.time.Instant;
import java.util.List;

/** Application boundary for tenant-scoped RAG configuration and ingestion control. */
public interface KnowledgeManagementPort {
    Page<ProfileView> profiles(String tenantCode, int pageNum, int pageSize, String keyword, String status);
    List<IndexView> indexVersions(String tenantCode, String status);
    ProfileView createProfile(String tenantCode, CreateProfile command);
    ProfileView publishProfile(String tenantCode, long profileVersionId);
    DocumentView createDocument(String tenantCode, CreateDocument command);
    Page<JobView> ingestionJobs(String tenantCode, int pageNum, int pageSize, String status);
    List<ScopeGrantView> grants(String tenantCode, String principalId);
    void grant(String tenantCode, GrantScope command);
    void revoke(String tenantCode, GrantScope command);

    record CreateProfile(String profileCode, String strategy, int maxResults, double minimumScore,
                         long indexVersionId, List<String> knowledgeScopes) {}
    record CreateDocument(String sourceCode, String sourceName, String externalDocumentId,
                          int version, String contentHash, String content) {}
    record GrantScope(String principalId, String scopeCode) {}
    record Page<T>(long total, int pageNum, int pageSize, List<T> records) {}
    record ProfileView(long id, String profileCode, int version, String strategy, int maxResults,
                       double minimumScore, long indexVersionId, String status,
                       List<String> knowledgeScopes, Instant createdAt) {}
    record IndexView(long id, String sourceCode, int version, String embeddingModel,
                     String status, Instant createdAt) {}
    record DocumentView(long id, String sourceCode, String externalDocumentId, int version,
                        String contentHash, String status, long ingestionJobId, Instant createdAt) {}
    record JobView(long id, String sourceCode, String documentHash, String status, int attempt,
                   Instant availableAt, String errorCode, Instant updatedAt) {}
    record ScopeGrantView(String principalId, String scopeCode, Instant createdAt) {}
}
