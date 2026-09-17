package io.github.illuseahashmap.workflow.knowledge;

import io.github.illuseahashmap.knowledge.catalog.domain.DocumentVersion;
import io.github.illuseahashmap.knowledge.ingestion.domain.Chunk;
import io.github.illuseahashmap.knowledge.retrieval.application.port.ChunkSearchPort;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest;
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
    private static final String SEARCH_SQL = """
            SELECT c.chunk_id, c.source_code, c.ordinal, c.content, c.content_fingerprint,
                   d.id AS document_version_id, d.external_document_id, d.version AS document_version,
                   d.content_hash, d.status AS document_status, d.created_at AS document_created_at,
                   GREATEST(c.fts_rank, c.lexical_rank) AS ts_rank
            FROM (
                SELECT c.*, ts_rank_cd(c.search_vector, plainto_tsquery('simple', :query)) AS fts_rank,
                       %s AS lexical_rank
                FROM knowledge_chunk c
                WHERE c.tenant_code = :tenantCode
                  AND c.source_code IN (:scopes)
                  AND (c.search_vector @@ plainto_tsquery('simple', :query)%s)
                  AND c.index_version_id IN (
                      SELECT iv.id FROM knowledge_index_version iv
                      WHERE iv.tenant_code = :tenantCode
                        AND iv.status = 'ACTIVE'
                  )
            ) c
            JOIN knowledge_document_version d ON d.id = c.document_version_id
            WHERE d.tenant_code = :tenantCode
              AND d.status IN ('READY', 'ACTIVE')
            ORDER BY GREATEST(c.fts_rank, c.lexical_rank) DESC, c.ordinal ASC
            LIMIT :limit
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
        String sql = SEARCH_SQL.formatted(lexicalScore, lexicalPredicate);
        return jdbcTemplate.query(sql, parameters, (row, rowNumber) -> {
            DocumentVersion document = new DocumentVersion(
                    row.getLong("document_version_id"), tenantCode, row.getString("source_code"),
                    row.getString("external_document_id"), row.getInt("document_version"),
                    row.getString("content_hash"), row.getString("document_status"),
                    instant(row.getTimestamp("document_created_at")));
            Chunk chunk = new Chunk(row.getString("chunk_id"), document, row.getInt("ordinal"),
                    row.getString("content"), row.getString("content_fingerprint"));
            return new SearchHit(chunk, row.getDouble("ts_rank"));
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
