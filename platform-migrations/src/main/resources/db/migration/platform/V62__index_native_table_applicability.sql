-- Only rebuilt candidates get native scope metadata. Keep original chunks and citations immutable.
ALTER TABLE knowledge_chunk
  ADD COLUMN table_scope_keys text[],
  ADD COLUMN scope_title_ordinal integer,
  ADD COLUMN scope_basis_ordinal integer,
  ADD COLUMN scope_note_ordinal integer,
  ADD COLUMN range_row_label text,
  ADD CONSTRAINT ck_native_scope_directory CHECK (table_scope_keys IS NULL OR
      (cardinality(table_scope_keys) BETWEEN 1 AND 256 AND scope_title_ordinal BETWEEN 0 AND ordinal
       AND scope_title_ordinal IS NOT NULL
       AND (scope_basis_ordinal IS NULL OR scope_basis_ordinal BETWEEN 0 AND ordinal)
       AND (scope_note_ordinal IS NULL OR scope_note_ordinal > ordinal)));
CREATE INDEX ix_native_scope_keys ON knowledge_chunk USING gin(table_scope_keys) WHERE table_scope_keys IS NOT NULL;
CREATE INDEX ix_native_member ON knowledge_chunk(tenant_code,index_version_id,document_version_id,interval_header_ordinal)
  WHERE cny_interval IS NOT NULL;
