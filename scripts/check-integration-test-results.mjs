import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

const expectedSuites = [
  'AgentCompletionEventStoreIntegrationTest',
  'AgentCompletionFlowableTransactionIntegrationTest',
  'AuthenticationHttpIntegrationTest',
  'PlatformMigrationIntegrationTest',
  'RedisProcessInstanceLockIntegrationTest',
];

function walk(directory, reports = []) {
  if (!existsSync(directory)) return reports;
  for (const entry of readdirSync(directory)) {
    const path = join(directory, entry);
    if (statSync(path).isDirectory()) walk(path, reports);
    else if (entry.startsWith('TEST-') && entry.endsWith('.xml')) reports.push(path);
  }
  return reports;
}

const reports = walk(process.cwd());
const failures = [];
for (const suite of expectedSuites) {
  const report = reports.find((path) => path.endsWith(`TEST-${suite}.xml`));
  if (!report) {
    failures.push(`${suite}: report is missing`);
    continue;
  }
  const xml = readFileSync(report, 'utf8');
  const header = xml.match(/<testsuite\b[^>]*>/)?.[0] ?? '';
  const tests = Number(header.match(/\btests="(\d+)"/)?.[1] ?? 0);
  const skipped = Number(header.match(/\bskipped="(\d+)"/)?.[1] ?? 0);
  if (tests === 0 || skipped > 0 || /<skipped\b/.test(xml)) {
    failures.push(`${suite}: tests=${tests}, skipped=${skipped}`);
  }
}

if (failures.length > 0) {
  console.error('Required infrastructure integration tests did not execute successfully:');
  failures.forEach((failure) => console.error(`- ${failure}`));
  process.exit(1);
}
console.log(`Verified ${expectedSuites.length} required infrastructure integration test suites.`);
