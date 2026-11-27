-- Optional native lookup metadata. Existing active/frozen indexes retain their old rankings.
ALTER TABLE knowledge_chunk
    ADD COLUMN cny_interval numrange,
    ADD COLUMN interval_header_ordinal integer,
    ADD COLUMN qualifier_ordinal integer,
    ADD CONSTRAINT ck_native_interval_v1 CHECK (
        (cny_interval IS NULL AND interval_header_ordinal IS NULL AND qualifier_ordinal IS NULL) OR
        (cny_interval IS NOT NULL AND NOT isempty(cny_interval) AND interval_header_ordinal IS NOT NULL
          AND interval_header_ordinal >= 0 AND interval_header_ordinal < ordinal
          AND (qualifier_ordinal IS NULL OR qualifier_ordinal > ordinal)));
CREATE INDEX ix_knowledge_chunk_cny_interval ON knowledge_chunk USING gist(cny_interval)
    WHERE cny_interval IS NOT NULL;
