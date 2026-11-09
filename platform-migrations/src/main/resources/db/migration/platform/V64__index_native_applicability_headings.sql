-- A lookup prefix is never proof of applicability. The read path verifies the whole heading,
-- the immutable Markdown derivation, and the following prose before using either citation.
CREATE FUNCTION knowledge_md_heading(body text) RETURNS text
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
    SELECT lower(btrim(substring(body FROM '^#{1,6}[[:blank:]]+([^#|\r\n]{2,96})$')))
$$;
CREATE INDEX ix_knowledge_heading_prefix ON knowledge_chunk
    (tenant_code, source_code, left(knowledge_md_heading(content),2), index_version_id, document_version_id, ordinal)
    WHERE knowledge_md_heading(content) IS NOT NULL;
