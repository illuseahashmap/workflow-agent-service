package io.github.illuseahashmap.workflow.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.knowledge.catalog.domain.DocumentContent;
import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeManagementPort;
import io.github.illuseahashmap.workflow.shared.exception.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomic intake. Published chunks and native locations are tied to the same immutable original. */
@Service
public class NativeEvidenceStore {
    public record Intake(String format,String sha256,String status,Long documentVersionId,int blocks,List<String> exclusions) {}
    public record Preview(String originalSha256,String format,String recipe,NativeLocator anchor,String excerpt) {}
    public record Coverage(Intake original,String recipe,List<NativeLocator> locations) {}
    private final NamedParameterJdbcTemplate jdbc;
    private final KnowledgeManagementPort management;
    private final ObjectMapper json;
    public NativeEvidenceStore(NamedParameterJdbcTemplate jdbc, KnowledgeManagementPort management,ObjectMapper json) {
        this.jdbc=jdbc; this.management=management; this.json=json;
    }
    @Transactional
    public Intake ingest(String tenant,String scope,String externalId,int version,String format,byte[] bytes) {
        if (scope==null || scope.isBlank() || scope.length()>128 || externalId==null || externalId.isBlank()
                || externalId.length()>512 || version<1) throw new BusinessException(ErrorCode.BAD_REQUEST,"Invalid document identity");
        NativeDocumentParser.Manifest m;
        try { m=NativeDocumentParser.parse(format,bytes); }
        catch(IllegalArgumentException e) {throw new BusinessException(ErrorCode.BAD_REQUEST,"Invalid or unsafe original file");}
        String originalHash;
        try { originalHash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
        var p=new MapSqlParameterSource("tenant",tenant).addValue("scope",scope).addValue("external",externalId)
                .addValue("version",version).addValue("format",format).addValue("sha",originalHash);
        jdbc.update("INSERT INTO knowledge_source(tenant_code,source_code,name) VALUES(:tenant,:scope,:scope) ON CONFLICT DO NOTHING",p);
        jdbc.queryForObject("SELECT id FROM knowledge_source WHERE tenant_code=:tenant AND source_code=:scope FOR UPDATE",p,Long.class);
        var existing=jdbc.query("""
                SELECT format,original_sha256,document_version_id,manifest::text FROM knowledge_native_original
                WHERE tenant_code=:tenant AND source_code=:scope AND external_document_id=:external AND version=:version
                """,p,(r,n)->{
                    if(!r.getString(1).equals(format)||!r.getString(2).equals(originalHash)) throw conflict();
                    try {
                        var manifest=json.readValue(r.getString(4),NativeDocumentParser.Manifest.class);
                        return intake(format,originalHash,r.getObject(3,Long.class),manifest);
                    } catch(JsonProcessingException e){throw new IllegalStateException("Invalid stored manifest",e);}
                });
        if (!existing.isEmpty()) return existing.getFirst();
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM knowledge_document_version WHERE tenant_code=:tenant AND source_code=:scope
                  AND external_document_id=:external AND version=:version)
                """,p,Boolean.class))) throw conflict();
        Long id=null;
        if (!m.blocks().isEmpty()) {
            String text=String.join("\n",m.blocks().stream().map(NativeDocumentParser.Block::text).toList());
            var c=DocumentContent.from(text);
            id=management.createDocument(tenant,new KnowledgeManagementPort.CreateDocument(scope,scope,externalId,version,c.sha256(),c.text())).id();
        }
        try {
            jdbc.update("""
                INSERT INTO knowledge_original_file(tenant_code,source_code,external_document_id,version,format,original_sha256,original_bytes)
                VALUES(:tenant,:scope,:external,:version,:format,:sha,:bytes)
                """,p.addValue("bytes",bytes));
            jdbc.update("""
                INSERT INTO knowledge_native_derivation(tenant_code,source_code,external_document_id,version,document_version_id,recipe,manifest)
                VALUES(:tenant,:scope,:external,:version,:doc,:recipe,CAST(:manifest AS jsonb))
                """,p.addValue("doc",id).addValue("recipe",m.recipe()).addValue("manifest",json.writeValueAsString(m)));
        } catch(JsonProcessingException e){throw new IllegalStateException("Invalid parser manifest",e);}
        return intake(format,originalHash,id,m);
    }
    private static Intake intake(String fmt,String sha,Long id,NativeDocumentParser.Manifest m){
        return new Intake(fmt,sha,m.status(),id,m.blocks().size(),List.copyOf(m.exclusions()));
    }
    private static BusinessException conflict(){return new BusinessException(ErrorCode.CONFLICT,"Original version already exists; submit a new version");}

    public Coverage coverage(String tenant,String scope,String external,int version) {
        var rows=jdbc.query("""
                SELECT format,original_sha256,document_version_id,manifest::text FROM knowledge_native_original
                WHERE tenant_code=:tenant AND source_code=:scope AND external_document_id=:external AND version=:version
                """, Map.of("tenant",tenant,"scope",scope,"external",external,"version",version),(r,n)->{
            try {
                var m=json.readValue(r.getString(4),NativeDocumentParser.Manifest.class);
                return new Coverage(intake(r.getString(1),r.getString(2),r.getObject(3,Long.class),m),
                        m.recipe(),m.blocks().stream().map(NativeDocumentParser.Block::anchor).toList());
            } catch(JsonProcessingException e){throw new IllegalStateException("Invalid stored manifest",e);}
        });
        return rows.stream().findFirst().orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND,"Original unavailable"));
    }

    /** Read only a previously cited block; recheck grants, version, frozen index and visibility in one statement. */
    public Preview read(String tenant,String principal,String trace,String chunk) {
        var rows=jdbc.query("""
                SELECT o.original_sha256,o.format,o.recipe,o.manifest->'blocks'->c.ordinal->'anchor' AS anchor,c.content
                FROM knowledge_retrieval_trace t
                JOIN knowledge_chunk c ON c.tenant_code=t.tenant_code AND c.chunk_id=:chunk
                JOIN knowledge_native_original o ON o.tenant_code=c.tenant_code AND o.source_code=c.source_code
                    AND o.document_version_id=c.document_version_id
                JOIN knowledge_document_version d ON d.id=o.document_version_id AND d.status IN ('READY','ACTIVE')
                JOIN knowledge_index_version i ON i.id=c.index_version_id AND i.status IN ('ACTIVE','RETIRED')
                JOIN knowledge_retrieval_profile_version p ON p.id=t.profile_version_id AND p.tenant_code=t.tenant_code AND p.status='PUBLISHED'
                JOIN knowledge_profile_index_binding member ON member.tenant_code=t.tenant_code AND member.profile_version_id=p.id
                    AND member.index_version_id=c.index_version_id AND member.source_code=c.source_code
                JOIN knowledge_source s ON s.tenant_code=c.tenant_code AND s.source_code=c.source_code AND s.enabled
                JOIN knowledge_principal_scope_grant g ON g.tenant_code=s.tenant_code AND g.scope_code=s.source_code AND g.principal_id=:principal
                WHERE t.trace_id=:trace AND t.tenant_code=:tenant AND t.status='SUCCESS'
                  AND t.index_bindings @> jsonb_build_array(jsonb_build_object('sourceCode',c.source_code,'indexVersionId',i.id))
                  AND t.evidence_references @> jsonb_build_array(jsonb_build_object('sourceType','CHUNK','sourceId',d.external_document_id,
                      'sourceVersion',d.version::text,'locator',c.chunk_id))
                  AND o.manifest->'blocks'->c.ordinal->>'text'=c.content
                """,Map.of("tenant",tenant,"principal",principal,"trace",trace,"chunk",chunk),(r,n)->{
            try {return new Preview(r.getString(1),r.getString(2),r.getString(3),
                    json.readValue(r.getString(4),NativeLocator.class),r.getString(5));}
            catch(JsonProcessingException e){throw new IllegalStateException("Invalid stored anchor",e);}
        });
        if(rows.size()!=1) throw new BusinessException(ErrorCode.NOT_FOUND,"Citation unavailable");
        return rows.getFirst();
    }
}
