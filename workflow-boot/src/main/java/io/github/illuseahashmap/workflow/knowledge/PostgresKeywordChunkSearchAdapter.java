package io.github.illuseahashmap.workflow.knowledge;

import io.github.illuseahashmap.knowledge.catalog.domain.DocumentVersion;
import io.github.illuseahashmap.knowledge.ingestion.domain.Chunk;
import io.github.illuseahashmap.knowledge.retrieval.application.port.ChunkSearchPort;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest;
import io.github.illuseahashmap.knowledge.retrieval.domain.PolicyComparison;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PostgreSQL adapter for the provider-neutral keyword search port. */
@Component
public final class PostgresKeywordChunkSearchAdapter implements ChunkSearchPort {
    private static final int MAX_FALLBACK_TERMS = 16;
    private static final Pattern QUERY_TOKEN = Pattern.compile("[\\p{IsHan}]+|[\\p{L}\\p{N}]+(?:[-_.][\\p{L}\\p{N}]+)*");
    // Both lexical seeds and structural companions pass through exactly the same security and
    // version boundary. NOT MATERIALIZED lets PostgreSQL use the GIN/GiST indexes independently.
    private static final String ELIGIBLE = """
            WITH eligible AS NOT MATERIALIZED (
                SELECT c.*, d.external_document_id, d.version AS document_version, d.content_hash,
                       d.status AS document_status, d.created_at AS document_created_at
                FROM knowledge_chunk c
                JOIN knowledge_document_version d ON d.id=c.document_version_id AND d.tenant_code=c.tenant_code AND d.source_code=c.source_code
                JOIN knowledge_source s ON s.tenant_code=c.tenant_code AND s.source_code=c.source_code AND s.enabled
                JOIN knowledge_index_version iv ON iv.id=c.index_version_id AND iv.tenant_code=c.tenant_code
                    AND iv.source_code=c.source_code
                WHERE c.tenant_code=:tenantCode AND c.source_code IN (:scopes)
                  AND d.status IN ('READY','ACTIVE')
                  AND ((:frozen=false AND iv.status='ACTIVE') OR
                       (iv.id IN (:indexVersionIds) AND iv.status IN ('ACTIVE','RETIRED')))
            )
            """;
    // dirs / best: decide applicability without using a price. matching: only then inspect
    // normalized intervals. positions: spend the caller's existing slots on citable originals.
    // A position is (index, document, ordinal), never a corpus-wide ordinal.
    private static final String SCOPED = """
            , scope_hits AS MATERIALIZED (
                SELECT DISTINCT ON (t.index_version_id,t.chunk_id)
                       t.index_version_id,t.chunk_id,t.term AS matched_name
                FROM knowledge_scope_term t
                WHERE t.tenant_code=:tenantCode AND t.source_code IN (:scopes)
                  AND (NOT :frozen OR t.index_version_id IN (:indexVersionIds))
                  AND t.term=ANY(CAST(:keys AS text[]))
                ORDER BY t.index_version_id,t.chunk_id,length(t.term) DESC,t.term
            ), dirs AS MATERIALIZED (
                SELECT h.index_version_id ix,h.document_version_id doc,h.ordinal hdr,h.scope_title_ordinal title,
                       h.scope_basis_ordinal basis,h.scope_note_ordinal note,m.matched_name
                FROM scope_hits m JOIN eligible h ON h.index_version_id=m.index_version_id AND h.chunk_id=m.chunk_id
            ), names AS MATERIALIZED (
                SELECT DISTINCT matched_name FROM dirs
            ), best AS MATERIALIZED (
                SELECT d.* FROM dirs d JOIN names n USING (matched_name) WHERE NOT EXISTS (
                    SELECT 1 FROM names specific WHERE length(specific.matched_name)>length(n.matched_name)
                      AND strpos(specific.matched_name,n.matched_name)>0)
            ), decision AS (
                SELECT count(*) n FROM best
            ), lex AS MATERIALIZED (
                SELECT c.id,c.index_version_id ix,c.document_version_id doc,c.ordinal,c.interval_header_ordinal member,
                       %s AS score,c.source_code,c.chunk_id
                FROM eligible c WHERE (c.search_vector @@ plainto_tsquery('simple',:query)%s)
                  AND (NOT :monetary OR NOT EXISTS(SELECT 1 FROM best) OR EXISTS(
                      SELECT 1 FROM best b WHERE b.ix=c.index_version_id AND b.doc=c.document_version_id
                          AND (b.hdr=c.interval_header_ordinal OR c.ordinal IN (b.title,b.basis,b.note,b.hdr)
                              OR EXISTS (SELECT 1 FROM eligible r WHERE r.index_version_id=b.ix AND r.document_version_id=b.doc
                                  AND r.interval_header_ordinal=b.hdr AND r.qualifier_ordinal=c.ordinal))))
                ORDER BY score DESC,c.ordinal,c.source_code COLLATE "C",ix,c.chunk_id COLLATE "C" LIMIT :limit
            ), winner AS (
                SELECT * FROM lex ORDER BY score DESC,ordinal,source_code COLLATE "C",ix,chunk_id COLLATE "C" LIMIT 1
            ), matching AS MATERIALIZED (
                SELECT r.ordinal,r.qualifier_ordinal,r.range_row_label,b.*,
                       (char_length(r.range_row_label)>=2 AND strpos(:query,r.range_row_label)>0 AND :asserted) AS named
                FROM best b JOIN eligible r ON r.index_version_id=b.ix AND r.document_version_id=b.doc
                    AND r.interval_header_ordinal=b.hdr AND r.cny_interval IS NOT NULL
                WHERE (r.cny_interval @> CAST(:point AS numeric) OR (CAST(:point AS numeric) IS NULL AND EXISTS
                      (SELECT 1 FROM winner w WHERE w.ix=b.ix AND w.doc=b.doc AND w.ordinal IN (r.ordinal,r.qualifier_ordinal))))
            ), selected AS MATERIALIZED (
                SELECT * FROM matching WHERE named OR (SELECT n FROM decision)>1
                    OR (SELECT count(*) FROM matching WHERE named)<>1
            ), positions AS (
                SELECT ix,doc,title pos, CASE WHEN (SELECT n FROM decision)>1 THEN 85 ELSE 45 END p FROM best
                UNION ALL SELECT ix,doc,basis,48 FROM best
                UNION ALL SELECT ix,doc,note,CASE WHEN (SELECT count(*) FROM matching)>1 THEN 101 ELSE 44 END FROM best
                UNION ALL SELECT ix,doc,ordinal,CASE WHEN (SELECT n FROM decision)>1 THEN
                    91 - least(5,row_number() OVER (PARTITION BY ix,doc,hdr ORDER BY ordinal)) ELSE 90 END FROM selected
                UNION ALL SELECT ix,doc,hdr,80 FROM selected
                UNION ALL SELECT ix,doc,qualifier_ordinal,79 FROM selected
            ), promoted AS (
                SELECT c.id,max(s.p) p FROM positions s JOIN eligible c
                  ON c.index_version_id=s.ix AND c.document_version_id=s.doc AND c.ordinal=s.pos
                WHERE (SELECT n FROM decision)>1 OR EXISTS(SELECT 1 FROM selected)
                   OR CAST(:point AS numeric) IS NOT NULL
                GROUP BY c.id
            ), candidates AS (
                SELECT id,p FROM promoted UNION ALL SELECT l.id,0 FROM lex l
                WHERE (SELECT n FROM decision)<=1
                    AND NOT (CAST(:point AS numeric) IS NOT NULL AND (SELECT n FROM decision)>0)
                    AND (l.member IS NULL OR EXISTS (SELECT 1 FROM best WHERE doc=l.doc AND ix=l.ix AND hdr=l.member)
                         OR (CAST(:point AS numeric) IS NULL AND (SELECT n FROM decision)=0))
            )
            SELECT c.*, %s AS ts_rank FROM (SELECT id,max(p) p FROM candidates GROUP BY id) a JOIN eligible c ON c.id=a.id
            ORDER BY a.p DESC,ts_rank DESC,c.ordinal,c.source_code COLLATE "C",c.index_version_id,c.chunk_id COLLATE "C" LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PostgresKeywordChunkSearchAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<SearchHit> search(String tenantCode, RetrievalRequest request, int limit) {
        if (tenantCode == null || tenantCode.isBlank() || request.knowledgeScopes().isEmpty()) {
            return List.of();
        }
        List<String> fallbackTerms = fallbackTerms(request.query());
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantCode", tenantCode)
                .addValue("query", request.query())
                .addValue("scopes", request.knowledgeScopes())
                .addValue("indexVersionId", request.indexVersionId())
                .addValue("frozen", !request.frozenIndexIds().isEmpty())
                .addValue("indexVersionIds", request.frozenIndexIds().isEmpty() ? List.of(-1L) : request.frozenIndexIds())
                .addValue("limit", Math.max(1, Math.min(limit, request.maxResults())));
        List<String> lexicalScores = new ArrayList<>();
        List<String> lexicalPredicates = new ArrayList<>();
        for (int index = 0; index < fallbackTerms.size(); index++) {
            String parameter = "fallback" + index;
            parameters.addValue(parameter, "%" + fallbackTerms.get(index).toLowerCase(Locale.ROOT) + "%");
            lexicalScores.add("CASE WHEN lower(c.content) LIKE :" + parameter + " THEN 1.0 ELSE 0.0 END");
            lexicalPredicates.add("lower(c.content) LIKE :" + parameter);
        }
        String lexicalScore = lexicalScores.isEmpty()
                ? "0.0"
                : "(" + String.join(" + ", lexicalScores) + ") / " + lexicalScores.size() + ".0";
        String lexicalPredicate = lexicalPredicates.isEmpty()
                ? ""
                : " OR " + String.join(" OR ", lexicalPredicates);
        String rank="GREATEST(ts_rank_cd(c.search_vector,plainto_tsquery('simple',:query)), "+lexicalScore+")";
        var comparison=PolicyComparison.parse(request.query());
        if(comparison.isPresent()) ApplicabilitySearch.bind(parameters,comparison.get());
        else parameters.addValue("point",MoneyLookup.point(request.query()).orElse(null))
                .addValue("monetary",MoneyLookup.mentionsAmount(request.query()))
                .addValue("keys",NativeTableScope.literal(NativeTableScope.queryKeys(request.query())))
                .addValue("asserted",NativeTableScope.assertsCategory(request.query()));
        String sql = ELIGIBLE + (comparison.isPresent()?ApplicabilitySearch.SQL.formatted(rank):SCOPED.formatted(rank,lexicalPredicate,rank));
        return jdbcTemplate.query(sql, parameters, (row, rowNumber) -> {
            DocumentVersion document = new DocumentVersion(
                    row.getLong("document_version_id"), tenantCode, row.getString("source_code"),
                    row.getString("external_document_id"), row.getInt("document_version"),
                    row.getString("content_hash"), row.getString("document_status"),
                    instant(row.getTimestamp("document_created_at")));
            Chunk chunk = new Chunk(row.getString("chunk_id"), document, row.getInt("ordinal"),
                    row.getString("content"), row.getString("content_fingerprint"));
            String side=comparison.isPresent()?row.getString("supported_side"):null;
            return new SearchHit(chunk,row.getDouble("ts_rank"),side==null?null:
                    PolicyComparison.Side.valueOf(side));
        });
    }

    /**
     * PostgreSQL's built-in simple dictionary does not segment CJK text. Keep the provider-neutral
     * port usable without a database extension by adding a bounded, parameterized lexical fallback.
     * A dedicated tokenizer or vector backend can replace this adapter without changing the domain.
     */
    static List<String> fallbackTerms(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        Matcher matcher = QUERY_TOKEN.matcher(query);
        while (matcher.find() && terms.size() < MAX_FALLBACK_TERMS) {
            String token = matcher.group();
            if (isHan(token)) {
                if (token.length() == 1) {
                    terms.add(token);
                } else {
                    for (int index = 0; index < token.length() - 1 && terms.size() < MAX_FALLBACK_TERMS; index++) {
                        terms.add(token.substring(index, index + 2));
                    }
                }
            } else if (token.length() >= 2) {
                terms.add(token.toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(terms);
    }

    private static boolean isHan(String value) {
        return value.codePoints().allMatch(codePoint -> Character.UnicodeScript.of(codePoint)
                == Character.UnicodeScript.HAN);
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? Instant.EPOCH : timestamp.toInstant();
    }
}
