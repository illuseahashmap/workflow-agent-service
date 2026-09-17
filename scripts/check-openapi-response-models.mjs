import { readFileSync } from 'node:fs';

const contractPath = process.argv[2];
if (!contractPath) {
  console.error('Usage: node scripts/check-openapi-response-models.mjs <bundled-openapi.json>');
  process.exit(2);
}

const contract = JSON.parse(readFileSync(contractPath, 'utf8'));
const failures = [];
for (const [path, pathItem] of Object.entries(contract.paths ?? {})) {
  for (const method of ['get', 'post', 'put', 'patch', 'delete']) {
    const operation = pathItem[method];
    if (!operation) continue;
    for (const [status, response] of Object.entries(operation.responses ?? {})) {
      if (!status.startsWith('2')) continue;
      const json = response.content?.['application/json'];
      if (!json) continue;
      const schema = json.schema;
      if (!schema) {
        failures.push(`${method.toUpperCase()} ${path} ${status}: JSON response schema is missing`);
      } else if (schema.$ref?.endsWith('/ApiResponse')) {
        failures.push(`${method.toUpperCase()} ${path} ${status}: untyped ApiResponse is forbidden`);
      }
    }
  }
}

if (failures.length > 0) {
  console.error('OpenAPI successful responses must use endpoint-specific data models:');
  failures.forEach((failure) => console.error(`- ${failure}`));
  process.exit(1);
}
console.log('All successful JSON responses use endpoint-specific schemas.');
