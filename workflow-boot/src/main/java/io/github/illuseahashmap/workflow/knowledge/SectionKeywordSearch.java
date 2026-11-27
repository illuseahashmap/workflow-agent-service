package io.github.illuseahashmap.workflow.knowledge;

/** Bounded prose expansion. All seeds, headings and bodies come from the same eligible snapshot. */
final class SectionKeywordSearch {
    private SectionKeywordSearch() {}

    static final String SQL =
            """
            , prose_matches AS MATERIALIZED (
                SELECT c.id,c.index_version_id ix,c.document_version_id doc,c.ordinal,c.content,
                       (%s) / sqrt(count(*) OVER (PARTITION BY c.content_fingerprint)) AS relevance
                FROM eligible c
                WHERE (SELECT n FROM decision)=0
                  AND (c.search_vector @@ plainto_tsquery('simple',:query)%s)
            ), prose_seeds AS MATERIALIZED (
                SELECT * FROM prose_matches
                ORDER BY relevance DESC,ix,doc,ordinal,id LIMIT :seedLimit
            ), prose_sections AS MATERIALIZED (
                SELECT seed.ix,seed.doc,coalesce(h.ordinal,seed.ordinal) start_ordinal,
                       coalesce(h.ordinal,seed.ordinal) + CASE WHEN h.ordinal IS NULL THEN 1 ELSE 0 END fallback_end,
                       h.ordinal IS NOT NULL has_heading,max(seed.relevance) relevance
                FROM prose_seeds seed
                LEFT JOIN LATERAL (
                    SELECT c.ordinal FROM eligible c
                    WHERE c.index_version_id=seed.ix AND c.document_version_id=seed.doc
                      AND c.ordinal<=seed.ordinal AND c.content ~ '^#{1,6}[[:blank:]][^\r\n]*$'
                    ORDER BY c.ordinal DESC LIMIT 1
                ) h ON true
                GROUP BY seed.ix,seed.doc,coalesce(h.ordinal,seed.ordinal),h.ordinal
            ), prose_bodies AS (
                SELECT body.id,section.relevance / (1.0 + 0.35 * (body.position-1)) p
                FROM prose_sections section
                LEFT JOIN LATERAL (
                    SELECT min(c.ordinal) boundary FROM eligible c
                    WHERE section.has_heading AND c.index_version_id=section.ix
                      AND c.document_version_id=section.doc AND c.ordinal>section.start_ordinal
                      AND c.content ~ '^#{1,6}[[:blank:]][^\r\n]*$'
                ) next_heading ON true
                CROSS JOIN LATERAL (
                    SELECT c.id,row_number() OVER (
                        ORDER BY coalesce(m.relevance,0) DESC,c.ordinal,c.id) position
                    FROM eligible c LEFT JOIN prose_matches m ON m.id=c.id
                    WHERE c.index_version_id=section.ix AND c.document_version_id=section.doc
                      AND c.ordinal>=section.start_ordinal
                      AND c.ordinal<CASE WHEN section.has_heading THEN coalesce(next_heading.boundary,2147483647)
                                         ELSE section.fallback_end END
                      AND c.content !~ '^#{1,6}[[:blank:]][^\r\n]*$'
                    ORDER BY coalesce(m.relevance,0) DESC,c.ordinal,c.id LIMIT :sectionBodyLimit
                ) body
            )
            """;
}
