UPDATE agent_provider
SET provider_name = '学习用 Mock', updated_at = CURRENT_TIMESTAMP
WHERE provider_code = 'mock_learning'
  AND provider_name LIKE '%Mock';
