package io.github.illuseahashmap.workflow.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.knowledge.ingestion.domain.Chunk;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.*;

/** Build from the already bounded, immutable derivation, once per document (not per query).
 * Skip legacy text; never attach hints to an ordinal from another chunking recipe. */
final class NativeAmountProjectionStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    static Map<String,NativeAmountIndex.Entry> forSnapshot(NamedParameterJdbcTemplate jdbc,
            MapSqlParameterSource scope,List<Chunk> chunks) {
        var byDoc=chunks.stream().collect(Collectors.groupingBy(c->c.documentVersion().id()));
        var result=new HashMap<String,NativeAmountIndex.Entry>();
        var documents=List.copyOf(byDoc.keySet());
        // Bound JDBC buffering of binary originals; avoid a lookup per ordinary text document.
        for(int start=0;start<documents.size();start+=8) {
            jdbc.query("""
                    SELECT manifest::text,document_version_id,format, CASE WHEN recipe IN
                        ('commonmark-gfm-v1','xssf-visible-rows-v1','word-body-rows-v1') THEN original_bytes END AS legacy_bytes
                    FROM knowledge_native_original
                    WHERE tenant_code=:tenantCode AND source_code=:sourceCode AND document_version_id IN (:docs)
                    """,new MapSqlParameterSource(scope.getValues()).addValue("docs",documents.subList(start,Math.min(start+8,documents.size()))), rs->{
                NativeDocumentParser.Manifest manifest;
                try { manifest=JSON.readValue(rs.getString(1),NativeDocumentParser.Manifest.class); }
                catch(com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("Invalid native derivation",ex); }
                byte[] legacy=rs.getBytes("legacy_bytes");
                if(legacy!=null) {
                    // Rebuild only. The old derivation and published index stay immutable. Do not
                    // attach a new physical width unless every cited position and excerpt agrees.
                    var derived=NativeDocumentParser.parse(rs.getString("format"),legacy);
                    if(!samePositions(manifest,derived)) return;
                    manifest=derived;
                }
                var rows=byDoc.get(rs.getLong("document_version_id"));
                if(manifest.blocks().size()!=rows.size()) return;
                for(int i=0;i<rows.size();i++) {
                    if(rows.get(i).ordinal()!=i || !rows.get(i).text().equals(manifest.blocks().get(i).text())) return;
                }
                NativeAmountIndex.project(manifest).forEach((i,entry)->result.put(rows.get(i).chunkId(),entry));
            });
        }
        return result;
    }
    static boolean samePositions(NativeDocumentParser.Manifest before, NativeDocumentParser.Manifest after) {
        if(!before.status().equals("PARSED") || !after.status().equals("PARSED")
                || before.blocks().size()!=after.blocks().size() || !before.exclusions().equals(after.exclusions())) return false;
        for(int i=0;i<before.blocks().size();i++) {
            var a=before.blocks().get(i); var b=after.blocks().get(i);
            if(!a.anchor().equals(b.anchor()) || !a.text().equals(b.text())) return false;
        }
        return true;
    }
    private NativeAmountProjectionStore() {}
}
