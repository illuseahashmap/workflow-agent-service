package io.github.illuseahashmap.workflow.knowledge;

import io.github.illuseahashmap.knowledge.retrieval.domain.PolicyComparison;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import java.util.Locale;

/** Comparison is a retrieval of two scopes, not a point lookup in either approval table. */
final class ApplicabilitySearch {
    private ApplicabilitySearch() {}
    static void bind(MapSqlParameterSource params, PolicyComparison comparison) {
        for(var side:PolicyComparison.Side.values()){
            String value=(side==PolicyComparison.Side.SUBJECT?comparison.subject():comparison.reference()).toLowerCase(Locale.ROOT);
            String prefix=side.name().toLowerCase(Locale.ROOT);
            params.addValue(prefix,value).addValue(prefix+"Keys",NativeTableScope.literal(NativeTableScope.keys(value)));
            // This is an index prefilter on literal headings, not the table vocabulary (which
            // deliberately strips generic terms such as “审批”). Full heading confirmation follows.
            var initials=new java.util.LinkedHashSet<String>();
            int previous=-1;
            for(int point:value.codePoints().toArray()){
                if(previous>=0 && Character.isLetterOrDigit(previous) && Character.isLetterOrDigit(point))
                    initials.add(new String(new int[]{previous,point},0,2));
                previous=point;
            }
            params.addValue(prefix+"Prefixes",NativeTableScope.literal(java.util.List.copyOf(initials)));
        }
    }
    static final String SQL="""
        , sides(side,phrase,keys,prefixes) AS (VALUES
            ('SUBJECT',CAST(:subject AS text),CAST(:subjectKeys AS text[]),CAST(:subjectPrefixes AS text[])),
            ('REFERENCE',CAST(:reference AS text),CAST(:referenceKeys AS text[]),CAST(:referencePrefixes AS text[]))
        ), table_scopes AS (
            SELECT DISTINCT s.side,h.index_version_id ix,h.document_version_id doc,
                h.scope_title_ordinal title,h.scope_basis_ordinal basis,h.scope_note_ordinal note
            FROM sides s JOIN knowledge_scope_term t ON t.term=ANY(s.keys)
              AND t.tenant_code=:tenantCode AND t.source_code IN (:scopes)
              AND (NOT :frozen OR t.index_version_id IN (:indexVersionIds))
            JOIN eligible h ON h.index_version_id=t.index_version_id AND h.chunk_id=t.chunk_id
            WHERE h.table_scope_keys <@ s.keys
        ), table_notes AS (
            SELECT h.side,h.ix,h.doc,h.title,n.ordinal basis FROM table_scopes h JOIN eligible n
              ON n.index_version_id=h.ix AND n.document_version_id=h.doc AND n.ordinal=h.note
            WHERE n.content ~ '(仅适用|只适用|不适用|适用范围|仅限|限于)'
        ), headings AS MATERIALIZED (
            SELECT s.side,h.index_version_id ix,h.document_version_id doc,h.ordinal title,h.content heading
            FROM sides s JOIN eligible h ON knowledge_md_heading(h.content) IS NOT NULL
                AND left(knowledge_md_heading(h.content),2)=ANY(s.prefixes)
                AND strpos(s.phrase,knowledge_md_heading(h.content))>0
        ), blocks AS MATERIALIZED (
            SELECT d.document_version_id doc,(b.n-1)::int pos,b.item->>'text' body,
                sum(CASE WHEN coalesce((b.item->>'tableWidth')::int,0)>0 OR
                    b.item->>'text' ~ '(^[[:blank:]]*([#|>*+-]|[0-9]+[.)] )|[|]|\n[=-]+[[:space:]]*$)'
                    THEN 1 ELSE 0 END) OVER(PARTITION BY d.document_version_id ORDER BY b.n) section
            FROM (SELECT DISTINCT doc FROM headings) wanted
            JOIN knowledge_native_derivation d ON d.document_version_id=wanted.doc AND d.tenant_code=:tenantCode
                AND d.source_code IN (:scopes) AND d.recipe IN ('commonmark-gfm-v1','commonmark-gfm-v2')
            CROSS JOIN LATERAL jsonb_array_elements(d.manifest->'blocks') WITH ORDINALITY b(item,n)
        ), prose_scopes AS (
            SELECT h.side,h.ix,h.doc,h.title,p.pos basis FROM headings h
            JOIN blocks title ON title.doc=h.doc AND title.pos=h.title AND title.body=h.heading
            JOIN blocks p ON p.doc=h.doc AND p.section=title.section AND p.pos>title.pos
            JOIN eligible c ON c.document_version_id=h.doc AND c.index_version_id=h.ix AND c.ordinal=p.pos AND c.content=p.body
            WHERE p.body ~ '(适用|仅限|限于|不经|不走|按|计入|不包含)'
        ), anchors AS (
            SELECT side,ix,doc,title,basis FROM table_scopes
            UNION SELECT side,ix,doc,title,basis FROM table_notes
            UNION SELECT side,ix,doc,title,basis FROM prose_scopes
        ), positions AS (
            SELECT side,ix,doc,title pos,false supports FROM anchors
            UNION ALL SELECT side,ix,doc,basis,true FROM anchors WHERE basis IS NOT NULL
        ), distinct_hits AS (
            SELECT c.id,min(p.side) side,bool_or(p.supports) supports
            FROM positions p JOIN eligible c ON c.index_version_id=p.ix AND c.document_version_id=p.doc AND c.ordinal=p.pos
            GROUP BY c.id HAVING min(p.side)=max(p.side)
        ), scored AS (
            SELECT c.*,s.side,s.supports,%s AS ts_rank FROM distinct_hits s JOIN eligible c ON c.id=s.id
        ), fair AS (
            SELECT *,row_number() OVER(PARTITION BY side ORDER BY supports DESC,ts_rank DESC,
                source_code COLLATE "C",index_version_id,chunk_id COLLATE "C") slot FROM scored
        )
        SELECT *,CASE WHEN supports THEN side END AS supported_side FROM fair
        ORDER BY slot,supports DESC,side LIMIT :limit
        """;
}
