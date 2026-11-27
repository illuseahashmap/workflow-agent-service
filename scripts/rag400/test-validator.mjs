import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve, dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { schemas, validateCase } from './validate.mjs'

const base = resolve(dirname(fileURLToPath(import.meta.url)), '../../docs/fixtures/knowledge/rag400-v1')
const parse = path => JSON.parse(readFileSync(path, 'utf8'))
const docs = new Map(parse(join(base, 'knowledge-snapshot.json')).documents.map(d => [d.documentCode, d]))
const sections = new Map()
for (const d of docs.values()) {
  for (const part of readFileSync(join(base, d.path), 'utf8').split('\n## ').slice(1)) {
    const position = part.indexOf('\n\n')
    sections.set(`${d.documentCode}:${part.slice(0, position).split(' ')[0]}`, part.slice(position + 2).trim())
  }
}
const cases = readFileSync(join(base, 'dev.cases.jsonl'), 'utf8').trim().split('\n').map(JSON.parse)
const check = schemas().case
const validate = c => validateCase(c, docs, sections, check)
const clone = predicate => structuredClone(cases.find(predicate ?? (() => true)))

test('all public development cases satisfy semantic reference checks', () => cases.forEach(validate))
test('a forged document digest is rejected', () => {
  const c = clone(); c.relevanceJudgments[0].contentHash = 'f'.repeat(64)
  assert.throws(() => validate(c), /source hash/)
})
test('a nonexistent source section is rejected', () => {
  const c = clone(); c.relevanceJudgments[0].locator.sectionId = 'missing'
  assert.throws(() => validate(c), /missing anchor/)
})
test('a required evidence group cannot cite an unknown anchor', () => {
  const c = clone(); c.requiredEvidenceGroups[0] = ['unknown']
  assert.throws(() => validate(c), /invalid required group/)
})
test('an answer cannot silently require forbidden evidence', () => {
  const c = clone(c => c.scenarioType === 'FORBIDDEN')
  c.allowedKnowledgeScopes.push(docs.get(c.forbiddenEvidence[0].documentCode).scope)
  assert.throws(() => validate(c), /forbidden scope allowed/)
})
test('claims cannot cite an out-of-range dependency', () => {
  const c = clone(c => c.expectedClaims.length > 0); c.expectedClaims[0].requiresGroups = [999]
  assert.throws(() => validate(c), /claim dependency/)
})
test('a single-section case cannot be relabeled as cross-domain', () => {
  const c = clone(c => c.difficulty.retrievalScope === 'SINGLE_SECTION')
  c.difficulty.retrievalScope = 'CROSS_DOMAIN'
  assert.throws(() => validate(c), /derived scope/)
})
test('difficulty cannot be raised without changing evidence or reasoning', () => {
  const c = clone(c => c.difficulty.level === 'EASY'); c.difficulty.level = 'HARD'
  assert.throws(() => validate(c), /derived difficulty/)
})
test('impossible calendar dates are rejected', () => {
  const c = clone(); c.queryTime = '2026-02-30T00:00:00Z'
  assert.throws(() => validate(c), /case schema/)
})
test('drafts cannot masquerade as independently reviewed', () => {
  const c = clone(); c.annotation.reviewer = 'invented reviewer'
  assert.throws(() => validate(c), /false independent review/)
})
