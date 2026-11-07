package io.github.illuseahashmap.workflow.knowledge;

import java.io.IOException;
import java.util.Locale;
import io.github.illuseahashmap.workflow.shared.context.*;
import io.github.illuseahashmap.workflow.shared.exception.*;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/knowledge")
public class NativeEvidenceController {
    private final NativeEvidenceStore store;
    private final TenantProvider tenants;
    private final CurrentPrincipalProvider principals;
    public NativeEvidenceController(NativeEvidenceStore store,TenantProvider tenants,CurrentPrincipalProvider principals) {
        this.store=store;this.tenants=tenants;this.principals=principals;
    }

    @PostMapping(value="/originals", consumes="multipart/form-data")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAuthority('knowledge:document:manage')")
    public ApiResponse<NativeEvidenceStore.Intake> upload(@RequestParam String sourceCode,@RequestParam String externalDocumentId,
            @RequestParam int version,@RequestParam MultipartFile file) throws IOException {
        String name=file.getOriginalFilename();
        if(name==null || !name.contains(".") || file.getSize()>NativeDocumentParser.MAX_BYTES)
            throw new BusinessException(ErrorCode.BAD_REQUEST,"Invalid original file");
        String format=name.substring(name.lastIndexOf('.')+1).toUpperCase(Locale.ROOT);
        String type=file.getContentType();
        if(type!=null) type=type.split(";",2)[0].trim().toLowerCase(Locale.ROOT);
        if(type!=null && !type.equals("application/octet-stream") && !switch(format) {
            case "MD" -> type.equals("text/markdown") || type.equals("text/plain");
            case "XLSX" -> type.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            case "DOCX" -> type.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            case "PDF" -> type.equals("application/pdf");
            default -> false;
        }) throw new BusinessException(ErrorCode.BAD_REQUEST,"Declared media type does not match the original");
        try(var in=file.getInputStream()) {
            return ApiResponse.ok(store.ingest(tenants.current().tenantCode(),sourceCode,externalDocumentId,version,
                    format,in.readNBytes(NativeDocumentParser.MAX_BYTES+1)));
        }
    }
    @GetMapping("/originals/coverage")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAuthority('knowledge:document:manage')")
    public ApiResponse<NativeEvidenceStore.Coverage> coverage(@RequestParam String sourceCode,
            @RequestParam String externalDocumentId,@RequestParam int version) {
        return ApiResponse.ok(store.coverage(tenants.current().tenantCode(),sourceCode,externalDocumentId,version));
    }

    @GetMapping("/citations/preview")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<NativeEvidenceStore.Preview> preview(@RequestParam String traceId,@RequestParam String chunkId) {
        return ApiResponse.ok(store.read(tenants.current().tenantCode(),principals.current().principalId(),traceId,chunkId));
    }
}
