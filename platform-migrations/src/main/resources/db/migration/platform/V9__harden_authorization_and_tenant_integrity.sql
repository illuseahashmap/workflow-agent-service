ALTER TABLE auth_permission
    ADD COLUMN IF NOT EXISTS scope VARCHAR(16);

DELETE FROM workflow_service_token_nonce
WHERE client_code = 'local-dev';

DELETE FROM workflow_service_client
WHERE client_code = 'local-dev'
  AND secret_key_ref = 'env:WORKFLOW_LOCAL_DEV_SECRET'
  AND secret_ciphertext IS NULL;

UPDATE auth_permission
SET scope = CASE WHEN permission_code = 'tenant:manage' THEN 'PLATFORM' ELSE 'TENANT' END
WHERE scope IS NULL;

ALTER TABLE auth_permission
    ALTER COLUMN scope SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_auth_permission_scope') THEN
        ALTER TABLE auth_permission
            ADD CONSTRAINT ck_auth_permission_scope CHECK (scope IN ('PLATFORM', 'TENANT'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_auth_user_enabled') THEN
        ALTER TABLE auth_user
            ADD CONSTRAINT ck_auth_user_enabled CHECK (enabled IN (0, 1));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_auth_role_enabled') THEN
        ALTER TABLE auth_role
            ADD CONSTRAINT ck_auth_role_enabled CHECK (enabled IN (0, 1));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_auth_user_tenant_enabled') THEN
        ALTER TABLE auth_user_tenant
            ADD CONSTRAINT ck_auth_user_tenant_enabled CHECK (enabled IN (0, 1));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_workflow_tenant_enabled') THEN
        ALTER TABLE workflow_tenant
            ADD CONSTRAINT ck_workflow_tenant_enabled CHECK (enabled IN (0, 1));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_workflow_assignment_rule_enabled') THEN
        ALTER TABLE workflow_node_assignment_rule
            ADD CONSTRAINT ck_workflow_assignment_rule_enabled CHECK (enabled IN (0, 1));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_workflow_service_client_enabled') THEN
        ALTER TABLE workflow_service_client
            ADD CONSTRAINT ck_workflow_service_client_enabled CHECK (enabled IN (0, 1));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_auth_menu_enabled') THEN
        ALTER TABLE auth_menu
            ADD CONSTRAINT ck_auth_menu_enabled CHECK (enabled IN (0, 1));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_workflow_assignment_rule_priority') THEN
        ALTER TABLE workflow_node_assignment_rule
            ADD CONSTRAINT ck_workflow_assignment_rule_priority CHECK (priority >= 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_workflow_assignment_target_sort') THEN
        ALTER TABLE workflow_assignment_target
            ADD CONSTRAINT ck_workflow_assignment_target_sort CHECK (sort_order >= 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_workflow_assignment_condition_sort') THEN
        ALTER TABLE workflow_node_assignment_rule_condition
            ADD CONSTRAINT ck_workflow_assignment_condition_sort CHECK (sort_order >= 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_auth_user_home_tenant') THEN
        ALTER TABLE auth_user
            ADD CONSTRAINT fk_auth_user_home_tenant
            FOREIGN KEY (tenant_code) REFERENCES workflow_tenant (tenant_code)
            ON UPDATE RESTRICT ON DELETE RESTRICT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_auth_membership_user') THEN
        ALTER TABLE auth_user_tenant
            ADD CONSTRAINT fk_auth_membership_user
            FOREIGN KEY (user_id) REFERENCES auth_user (user_id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_auth_membership_tenant') THEN
        ALTER TABLE auth_user_tenant
            ADD CONSTRAINT fk_auth_membership_tenant
            FOREIGN KEY (tenant_code) REFERENCES workflow_tenant (tenant_code)
            ON UPDATE RESTRICT ON DELETE RESTRICT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_auth_user_role_user') THEN
        ALTER TABLE auth_user_role
            ADD CONSTRAINT fk_auth_user_role_user
            FOREIGN KEY (user_id) REFERENCES auth_user (user_id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_auth_user_role_role') THEN
        ALTER TABLE auth_user_role
            ADD CONSTRAINT fk_auth_user_role_role
            FOREIGN KEY (tenant_code, role_code) REFERENCES auth_role (tenant_code, role_code)
            ON UPDATE RESTRICT ON DELETE RESTRICT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_auth_role_permission_role') THEN
        ALTER TABLE auth_role_permission
            ADD CONSTRAINT fk_auth_role_permission_role
            FOREIGN KEY (tenant_code, role_code) REFERENCES auth_role (tenant_code, role_code)
            ON UPDATE RESTRICT ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_auth_role_permission_permission') THEN
        ALTER TABLE auth_role_permission
            ADD CONSTRAINT fk_auth_role_permission_permission
            FOREIGN KEY (permission_code) REFERENCES auth_permission (permission_code)
            ON UPDATE RESTRICT ON DELETE RESTRICT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_auth_menu_permission') THEN
        ALTER TABLE auth_menu
            ADD CONSTRAINT fk_auth_menu_permission
            FOREIGN KEY (permission_code) REFERENCES auth_permission (permission_code)
            ON UPDATE RESTRICT ON DELETE RESTRICT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_auth_menu_parent') THEN
        ALTER TABLE auth_menu
            ADD CONSTRAINT fk_auth_menu_parent
            FOREIGN KEY (parent_code) REFERENCES auth_menu (menu_code)
            ON UPDATE RESTRICT ON DELETE RESTRICT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_workflow_active_version_tenant') THEN
        ALTER TABLE workflow_active_version
            ADD CONSTRAINT fk_workflow_active_version_tenant
            FOREIGN KEY (tenant_id) REFERENCES workflow_tenant (tenant_id)
            ON UPDATE RESTRICT ON DELETE RESTRICT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_workflow_assignment_rule_tenant') THEN
        ALTER TABLE workflow_node_assignment_rule
            ADD CONSTRAINT fk_workflow_assignment_rule_tenant
            FOREIGN KEY (tenant_id) REFERENCES workflow_tenant (tenant_id)
            ON UPDATE RESTRICT ON DELETE RESTRICT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_workflow_service_nonce_client') THEN
        ALTER TABLE workflow_service_token_nonce
            ADD CONSTRAINT fk_workflow_service_nonce_client
            FOREIGN KEY (client_code) REFERENCES workflow_service_client (client_code)
            ON UPDATE RESTRICT ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_workflow_assignment_rule_id_tenant') THEN
        ALTER TABLE workflow_node_assignment_rule
            ADD CONSTRAINT uk_workflow_assignment_rule_id_tenant UNIQUE (id, tenant_id);
    END IF;
END $$;

ALTER TABLE workflow_assignment_target
    DROP CONSTRAINT IF EXISTS fk_workflow_assignment_target_rule;
ALTER TABLE workflow_assignment_target
    ADD CONSTRAINT fk_workflow_assignment_target_rule_tenant
    FOREIGN KEY (rule_id, tenant_id)
    REFERENCES workflow_node_assignment_rule (id, tenant_id)
    ON UPDATE RESTRICT ON DELETE CASCADE;

ALTER TABLE workflow_node_assignment_rule_condition
    DROP CONSTRAINT IF EXISTS fk_workflow_assignment_condition_rule;
ALTER TABLE workflow_node_assignment_rule_condition
    ADD CONSTRAINT fk_workflow_assignment_condition_rule_tenant
    FOREIGN KEY (rule_id, tenant_id)
    REFERENCES workflow_node_assignment_rule (id, tenant_id)
    ON UPDATE RESTRICT ON DELETE CASCADE;

CREATE OR REPLACE FUNCTION enforce_tenant_role_permission_scope()
RETURNS TRIGGER AS $$
DECLARE
    permission_scope VARCHAR(16);
BEGIN
    SELECT scope INTO permission_scope
    FROM auth_permission
    WHERE permission_code = NEW.permission_code;

    IF NEW.tenant_code <> '*' AND permission_scope <> 'TENANT' THEN
        RAISE EXCEPTION 'Platform permission % cannot be assigned to tenant role %.%',
            NEW.permission_code, NEW.tenant_code, NEW.role_code;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_auth_role_permission_scope ON auth_role_permission;
CREATE TRIGGER trg_auth_role_permission_scope
BEFORE INSERT OR UPDATE ON auth_role_permission
FOR EACH ROW EXECUTE FUNCTION enforce_tenant_role_permission_scope();

CREATE OR REPLACE FUNCTION enforce_auth_role_tenant()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.tenant_code <> '*'
       AND NOT EXISTS (
           SELECT 1 FROM workflow_tenant tenant
           WHERE tenant.tenant_code = NEW.tenant_code
       ) THEN
        RAISE EXCEPTION 'Tenant % does not exist for role %', NEW.tenant_code, NEW.role_code;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_auth_role_tenant ON auth_role;
CREATE TRIGGER trg_auth_role_tenant
BEFORE INSERT OR UPDATE OF tenant_code ON auth_role
FOR EACH ROW EXECUTE FUNCTION enforce_auth_role_tenant();

CREATE OR REPLACE FUNCTION prevent_workflow_tenant_identity_change()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.tenant_id <> OLD.tenant_id OR NEW.tenant_code <> OLD.tenant_code THEN
        RAISE EXCEPTION 'tenant_id and tenant_code are immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_workflow_tenant_identity_immutable ON workflow_tenant;
CREATE TRIGGER trg_workflow_tenant_identity_immutable
BEFORE UPDATE ON workflow_tenant
FOR EACH ROW EXECUTE FUNCTION prevent_workflow_tenant_identity_change();
