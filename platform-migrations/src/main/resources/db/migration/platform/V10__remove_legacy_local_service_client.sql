DELETE FROM workflow_service_token_nonce
WHERE client_code = 'local-dev';

DELETE FROM workflow_service_client
WHERE client_code = 'local-dev'
  AND description = 'Default local development client'
  AND secret_ciphertext IS NULL;
