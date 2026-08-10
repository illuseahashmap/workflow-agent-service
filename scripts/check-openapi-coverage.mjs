import fs from 'node:fs';
import path from 'node:path';

const [contractPath, sourceRoot = '.'] = process.argv.slice(2);
if (!contractPath) {
  console.error('Usage: node scripts/check-openapi-coverage.mjs <bundled-openapi.json> [source-root]');
  process.exit(2);
}

const contract = JSON.parse(fs.readFileSync(contractPath, 'utf8'));
const documented = new Set();
for (const [route, definition] of Object.entries(contract.paths ?? {})) {
  for (const method of Object.keys(definition)) {
    if (/^(get|post|put|patch|delete|options|head|trace)$/i.test(method)) {
      documented.add(`${method.toLowerCase()} ${route}`);
    }
  }
}

const missing = [];
for (const file of javaFiles(sourceRoot)) {
  const source = fs.readFileSync(file, 'utf8');
  if (!source.includes('@RestController')) {
    continue;
  }
  const classMapping = source.match(/@RequestMapping\s*\(\s*"([^"]*)"\s*\)/)?.[1] ?? '';
  const mappingPattern = /@(Get|Post|Put|Patch|Delete)Mapping\s*(?:\(([^)]*)\))?/g;
  for (const match of source.matchAll(mappingPattern)) {
    const method = match[1].toLowerCase();
    const argumentsText = match[2] ?? '';
    const methodPath = argumentsText.match(/(?:value\s*=\s*)?"([^"]*)"/)?.[1] ?? '';
    const route = normalize(`${classMapping}/${methodPath}`);
    if (!documented.has(`${method} ${route}`)) {
      missing.push(`${method.toUpperCase()} ${route} (${path.relative(sourceRoot, file)})`);
    }
  }
}

if (missing.length > 0) {
  console.error('OpenAPI coverage check failed. Undocumented REST endpoints:');
  for (const endpoint of missing) {
    console.error(`- ${endpoint}`);
  }
  process.exit(1);
}
console.log('OpenAPI coverage check passed.');

function normalize(value) {
  const route = `/${value}`.replaceAll(/\/+/g, '/');
  return route.length > 1 && route.endsWith('/') ? route.slice(0, -1) : route;
}

function* javaFiles(directory) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const fullPath = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      if (!['target', '.git', 'node_modules'].includes(entry.name)) {
        yield* javaFiles(fullPath);
      }
    } else if (entry.isFile() && entry.name.endsWith('.java') && !fullPath.includes(`${path.sep}test${path.sep}`)) {
      yield fullPath;
    }
  }
}
