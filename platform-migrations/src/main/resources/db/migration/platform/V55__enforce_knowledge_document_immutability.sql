-- Identity and published source content are immutable; lifecycle status may still change.
CREATE FUNCTION reject_knowledge_document_rewrite() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'knowledge_document_version' THEN
        IF ROW(NEW.id, NEW.tenant_code, NEW.source_code, NEW.external_document_id,
               NEW.version, NEW.content_hash, NEW.created_at)
           IS DISTINCT FROM ROW(OLD.id, OLD.tenant_code, OLD.source_code, OLD.external_document_id,
               OLD.version, OLD.content_hash, OLD.created_at) THEN
            RAISE EXCEPTION 'Knowledge document versions are immutable' USING ERRCODE = '23514';
        END IF;
    ELSIF NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Knowledge document content is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER knowledge_document_version_immutable
    BEFORE UPDATE ON knowledge_document_version
    FOR EACH ROW EXECUTE FUNCTION reject_knowledge_document_rewrite();

CREATE TRIGGER knowledge_document_content_immutable
    BEFORE UPDATE ON knowledge_document_content
    FOR EACH ROW EXECUTE FUNCTION reject_knowledge_document_rewrite();
