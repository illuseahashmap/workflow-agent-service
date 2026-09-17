package io.github.illuseahashmap.workflow.knowledge;

import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeManagementPort;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Tenant-scoped RAG control plane. Retrieval remains disabled until its runtime gate is enabled. */
@RestController
@RequestMapping("/knowledge")
public class KnowledgeManagementController {
    private final KnowledgeManagementPort management;
    private final TenantProvider tenants;

    public KnowledgeManagementController(KnowledgeManagementPort management, TenantProvider tenants) {
        this.management = management;
        this.tenants = tenants;
    }

    @GetMapping("/retrieval-profiles")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAnyAuthority('knowledge:profile:read', 'knowledge:profile:manage')")
    public ApiResponse<PageResult<KnowledgeManagementPort.ProfileView>> profiles(
            @RequestParam(defaultValue = "1") int pageNum, @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) String status) {
        var p = management.profiles(tenant(), boundedPage(pageNum), boundedSize(pageSize), keyword, status);
        return ApiResponse.ok(new PageResult<>(p.total(), p.pageNum(), p.pageSize(), p.records()));
    }

    @PostMapping("/retrieval-profiles")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAuthority('knowledge:profile:manage')")
    public ApiResponse<KnowledgeManagementPort.ProfileView> createProfile(@Valid @RequestBody KnowledgeManagementPort.CreateProfile command) {
        return ApiResponse.ok(management.createProfile(tenant(), command));
    }

    @PostMapping("/retrieval-profiles/{id}/publish")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAuthority('knowledge:profile:manage')")
    public ApiResponse<KnowledgeManagementPort.ProfileView> publishProfile(@PathVariable long id) {
        return ApiResponse.ok(management.publishProfile(tenant(), id));
    }

    @GetMapping("/index-versions")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAuthority('knowledge:profile:manage')")
    public ApiResponse<List<KnowledgeManagementPort.IndexView>> indexVersions(
            @RequestParam(defaultValue = "ACTIVE") String status) {
        return ApiResponse.ok(management.indexVersions(tenant(), status));
    }

    @PostMapping("/documents")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAuthority('knowledge:document:manage')")
    public ApiResponse<KnowledgeManagementPort.DocumentView> createDocument(@Valid @RequestBody KnowledgeManagementPort.CreateDocument command) {
        return ApiResponse.ok(management.createDocument(tenant(), command));
    }

    @GetMapping("/ingestion-jobs")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAuthority('knowledge:ingestion:read')")
    public ApiResponse<PageResult<KnowledgeManagementPort.JobView>> jobs(
            @RequestParam(defaultValue = "1") int pageNum, @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status) {
        var p = management.ingestionJobs(tenant(), boundedPage(pageNum), boundedSize(pageSize), status);
        return ApiResponse.ok(new PageResult<>(p.total(), p.pageNum(), p.pageSize(), p.records()));
    }

    @GetMapping("/scope-grants")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAuthority('knowledge:scope:manage')")
    public ApiResponse<List<KnowledgeManagementPort.ScopeGrantView>> grants(@RequestParam String principalId) {
        return ApiResponse.ok(management.grants(tenant(), principalId));
    }

    @PostMapping("/scope-grants")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAuthority('knowledge:scope:manage')")
    public ApiResponse<Void> grant(@Valid @RequestBody KnowledgeManagementPort.GrantScope command) {
        management.grant(tenant(), command); return ApiResponse.ok();
    }

    @DeleteMapping("/scope-grants")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAuthority('knowledge:scope:manage')")
    public ApiResponse<Void> revoke(@RequestParam String principalId, @RequestParam String scopeCode) {
        management.revoke(tenant(), new KnowledgeManagementPort.GrantScope(principalId, scopeCode)); return ApiResponse.ok();
    }

    private String tenant() { return tenants.current().tenantCode(); }
    private int boundedPage(int value) { return Math.max(1, value); }
    private int boundedSize(int value) { return Math.min(100, Math.max(1, value)); }
}
