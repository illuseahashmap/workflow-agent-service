import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import test from 'node:test';
import assert from 'node:assert/strict';

const require = createRequire(import.meta.url);
const Ajv = require('ajv/dist/2020').default;
const locator = JSON.parse(readFileSync(new URL('../docs/architecture/schema/rag-evidence-locator.schema.json', import.meta.url)));
const cases = JSON.parse(readFileSync(new URL('../docs/architecture/schema/rag-evaluation-case.schema.json', import.meta.url)));
const ajv = new Ajv({ strict: false, allErrors: true });
ajv.addSchema(locator);
// The fragment deliberately contains no date-time fields: this is the source-anchor contract, not a dataset lock validator.
const anchor = ajv.compile(cases.$defs.evidenceAnchor);
const hash = 'a'.repeat(64);
const region = { pageIndex: 1, x0: 0, y0: 0.1, x1: 1, y1: 0.8 };
const base = { evidenceId: 'proc-approval-cell', documentCode: 'PROC-POL-01', documentVersion: '1', contentHash: hash };

function validate(value) {
  if (!anchor(value)) return false;
  return (value.locator.pageRegions ?? []).every(r => r.x0 < r.x1 && r.y0 < r.y1);
}

test('text, multi-page table and figure anchors use disjoint typed source locators', () => {
  assert.equal(validate({ ...base, locator: { schemaVersion: 1, kind: 'TEXT_SECTION', sectionId: '3.1' } }), true);
  assert.equal(validate({ ...base, locator: { schemaVersion: 1, kind: 'TABLE_CELLS', artifactHash: hash,
    elementId: 'table-1', pageRegions: [region, { ...region, pageIndex: 2 }], rowIds: ['r1'], columnIds: ['c1'] } }), true);
  assert.equal(validate({ ...base, locator: { schemaVersion: 1, kind: 'FIGURE_REGION', artifactHash: hash,
    elementId: 'figure-1', pageRegions: [region], assetHash: hash } }), true);
});

test('rejects unsupported versions, missing cells, forged hashes, invalid regions and vendor/path payloads', () => {
  const pdf = { schemaVersion: 1, kind: 'PDF_REGION', artifactHash: hash, elementId: 'p1', pageRegions: [region] };
  for (const changed of [{ schemaVersion: 2 }, { artifactHash: 'missing' }, { kind: 'TABLE_CELLS' },
    { schemaVersion: 1.9 }, { schemaVersion: '1' }, { schemaVersion: null },
    { pageRegions: [{ x1: 1, y1: 1 }] }, { pageRegions: [{ ...region, x0: null }] },
    { pageRegions: [{ ...region, x0: '0' }] }, { pageRegions: [{ ...region, pageIndex: 0.5 }] },
    { pageRegions: [{ ...region, pageIndex: -1 }] }, { pageRegions: [{ ...region, x1: -1 }] },
    { pageRegions: [{ ...region, x0: 0.9, x1: 0.1 }] }, { filePath: 'C:/secret.pdf' }, { rowIds: ['r1'] }]) {
    assert.equal(validate({ ...base, locator: { ...pdf, ...changed } }), false, JSON.stringify(changed));
  }
  assert.equal(validate({ ...base, sectionId: '3.1' }), false, 'v2 section-only anchors must not masquerade as v3');
});

test('judgments carry the same structured locator contract and bounded relevance grade', () => {
  const judgment = ajv.compile(cases.$defs.judgment);
  const value = { ...base, grade: 2, locator: { schemaVersion: 1, kind: 'TEXT_SECTION', sectionId: '3.1' } };
  assert.equal(judgment(value), true);
  assert.equal(judgment({ ...value, grade: 3 }), false);
});

test('dataset snapshot freezes multiple source indexes instead of a single live pointer', () => {
  const dataset = JSON.parse(readFileSync(new URL('../docs/architecture/schema/rag-evaluation-dataset.schema.json', import.meta.url)));
  assert.equal(dataset.properties.schemaVersion.const, 2);
  assert.equal(cases.properties.schemaVersion.const, 3);
  assert.ok(dataset.required.includes('indexBindings'));
  assert.equal(dataset.properties.indexVersionId, undefined);
  const bindings = ajv.compile(dataset.properties.indexBindings);
  assert.equal(bindings([{ sourceCode: 'procurement', indexVersionId: '17' }, { sourceCode: 'finance', indexVersionId: '23' }]), true);
  assert.equal(bindings([{ sourceCode: 'procurement' }]), false);
  assert.equal(bindings([]), false);
});
