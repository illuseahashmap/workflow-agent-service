import { readFileSync, writeFileSync, existsSync } from 'node:fs'
import { resolve, dirname, join } from 'node:path'
import { createHash } from 'node:crypto'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

const require = createRequire(import.meta.url)
const Ajv = require('ajv/dist/2020').default
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const json = (path) => JSON.parse(readFileSync(path, 'utf8'))
const lines = (path) => readFileSync(path, 'utf8').trim().split('\n').filter(Boolean).map(JSON.parse)
const sha = (path) => createHash('sha256').update(readFileSync(path)).digest('hex')
const ensure = (condition, message) => { if (!condition) throw new Error(message) }

export function schemas() {
  const ajv = new Ajv({ strict: false, allErrors: true, validateFormats: true })
  ajv.addFormat('date-time', { type: 'string', validate: value => {
    if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(value)
      || !Number.isFinite(Date.parse(value))) return false
    const [year, month, day] = value.slice(0, 10).split('-').map(Number)
    return month >= 1 && month <= 12 && day >= 1 && day <= new Date(Date.UTC(year, month, 0)).getUTCDate()
      && Number(value.slice(11, 13)) <= 23 && Number(value.slice(14, 16)) <= 59 && Number(value.slice(17, 19)) <= 59
  } })
  ajv.addSchema(json(join(root, 'docs/architecture/schema/rag-evidence-locator.schema.json')))
  return {
    case: ajv.compile(json(join(root, 'docs/architecture/schema/rag-evaluation-case.schema.json'))),
    manifest: ajv.compile(json(join(root, 'docs/architecture/schema/rag-evaluation-dataset.schema.json'))),
    dependencies: ajv.compile(json(join(root, 'scripts/rag400/dependencies.schema.json'))),
  }
}

export function validateCase(c, documentMap, sectionMap, check) {
  ensure(check(c), `case schema ${c.caseId}: ${JSON.stringify(check.errors)}`)
  ensure(c.annotation.status === 'DRAFT' && c.annotation.reviewer === null, `false independent review ${c.caseId}`)
  const ids = new Set()
  for (const e of [...c.relevanceJudgments, ...(c.forbiddenEvidence ?? [])]) {
    const doc = documentMap.get(e.documentCode)
    ensure(doc && doc.sha256 === e.contentHash && doc.documentVersion === e.documentVersion, `source hash ${c.caseId}`)
    ensure(sectionMap.has(`${e.documentCode}:${e.locator.sectionId}`), `missing anchor ${c.caseId}`)
    ensure(!ids.has(e.evidenceId), `duplicate evidence ${c.caseId}`)
    ids.add(e.evidenceId)
  }
  const required = c.requiredEvidenceGroups.flat()
  for (const id of required) {
    const evidence = c.relevanceJudgments.find(e => e.evidenceId === id)
    ensure(evidence && evidence.grade === 2, `invalid required group ${c.caseId}`)
    ensure(c.allowedKnowledgeScopes.includes(documentMap.get(evidence.documentCode).scope), `unauthorized gold ${c.caseId}`)
  }
  for (const e of c.forbiddenEvidence ?? [])
    ensure(!c.allowedKnowledgeScopes.includes(documentMap.get(e.documentCode).scope), `forbidden scope allowed ${c.caseId}`)
  const selected = c.relevanceJudgments.filter(e => required.includes(e.evidenceId))
  const docCount = new Set(selected.map(e => e.documentCode)).size
  const domainCount = new Set(selected.map(e => documentMap.get(e.documentCode).domain)).size
  const scope = domainCount > 1 ? 'CROSS_DOMAIN' : docCount > 1 ? 'MULTI_DOCUMENT'
    : selected.length > 1 ? 'MULTI_SECTION_SINGLE_DOCUMENT' : 'SINGLE_SECTION'
  ensure(scope === c.difficulty.retrievalScope, `derived scope ${c.caseId}`)
  const d = c.difficulty
  const hard = ['MULTI_DOCUMENT', 'CROSS_DOMAIN'].includes(scope)
    || ['MULTI_HOP', 'CONFLICT_RESOLUTION'].includes(d.reasoningType)
    || ['HISTORICAL', 'CROSS_PERIOD'].includes(d.temporalDemand)
  const deviations = Number(scope !== 'SINGLE_SECTION') + Number(d.reasoningType !== 'DIRECT')
    + Number(!['EXACT', 'PARAPHRASED'].includes(d.queryForm)) + Number(d.temporalDemand !== 'CURRENT')
  const expectedLevel = hard || deviations >= 2 ? 'HARD' : deviations === 0 ? 'EASY' : 'MEDIUM'
  ensure(expectedLevel === d.level, `derived difficulty ${c.caseId}`)
  for (const claim of c.expectedClaims)
    for (const index of claim.requiresGroups)
      ensure(index < c.requiredEvidenceGroups.length, `claim dependency ${c.caseId}`)
  ensure(c.scenarioType !== 'INSUFFICIENT' || c.factsMissing.length > 0, `missing fact label ${c.caseId}`)
  ensure(c.scenarioType !== 'FORBIDDEN' || c.forbiddenEvidence?.length > 0, `missing forbidden gold ${c.caseId}`)
}

export function audit(publicDir, privateDir, publicOnly = false) {
  const check = schemas()
  const snapshotPath = join(publicDir, 'knowledge-snapshot.json')
  const snapshot = json(snapshotPath)
  const documentMap = new Map(snapshot.documents.map(d => [d.documentCode, d]))
  const sectionMap = new Map()
  for (const doc of snapshot.documents) {
    const path = resolve(publicDir, doc.path)
    ensure(path.startsWith(resolve(publicDir) + '/'.replace('/', process.platform === 'win32' ? '\\' : '/')), 'source path escapes fixture')
    ensure(sha(path) === doc.sha256, `document checksum ${doc.documentCode}`)
    const text = readFileSync(path, 'utf8')
    for (const part of text.split('\n## ').slice(1)) {
      const boundary = part.indexOf('\n\n')
      const id = part.slice(0, boundary).split(' ')[0]
      sectionMap.set(`${doc.documentCode}:${id}`, part.slice(boundary + 2).trim())
    }
  }
  const caseIds = new Set(), groupSplit = new Map(), evidenceSplit = new Map(), querySet = new Set()
  const contentSplits = new Map()
  const counts = {}, levels = {}, scenarios = {}, perDomain = {}, all = []
  for (const [name, directory, expectedSplit, count] of [
    ['dev', publicDir, 'DEV', 200], ['calibration', publicDir, 'CALIBRATION', 80],
    ...(!publicOnly ? [['holdout-candidate', privateDir, 'LOCKED_TEST', 120]] : []),
  ]) {
    const cpath = join(directory, `${name}.cases.jsonl`)
    const cases = lines(cpath)
    const manifest = json(join(directory, `${name}.manifest.json`))
    ensure(check.manifest(manifest), `manifest schema ${name}: ${JSON.stringify(check.manifest.errors)}`)
    ensure(manifest.status === 'DRAFT', 'not independently locked')
    ensure(manifest.caseFileSha256 === sha(cpath), `case checksum ${name}`)
    ensure(manifest.knowledgeSnapshotSha256 === sha(snapshotPath), `snapshot checksum ${name}`)
    ensure(manifest.splitGroupRegistrySha256 === sha(join(directory, `${name}.groups.json`)), `group checksum ${name}`)
    ensure(cases.length === count, `split count ${name}`)
    const dependencies = new Map(lines(join(directory, `${name}.dependencies.jsonl`)).map(d => [d.caseId, d]))
    const queries = new Map(lines(join(directory, `${name}.queries.jsonl`)).map(q => [q.caseId, q]))
    ensure(queries.size === cases.length, 'query export size mismatch')
    const grants = json(join(publicDir, 'auth-fixture.json')).grantedScopes
    const groups = new Map(json(join(directory, `${name}.groups.json`)).map(g => [g.scenarioGroupId, g]))
    counts[expectedSplit] = cases.length
    levels[expectedSplit] = {}; scenarios[expectedSplit] = {}
    for (const c of cases) {
      validateCase(c, documentMap, sectionMap, check.case)
      ensure(queries.get(c.caseId)?.query === c.query, `query export differs ${c.caseId}`)
      if (manifest.version >= 2) ensure(JSON.stringify([...c.allowedKnowledgeScopes].sort()) === JSON.stringify([...grants].sort()), `gold-derived scope routing ${c.caseId}`)
      if (manifest.version >= 3) ensure(!c.query.startsWith('仅依据冻结合成资料作答'), `benchmark instruction in query ${c.caseId}`)
      ensure(c.split === expectedSplit, `wrong file split ${c.caseId}`)
      ensure(!caseIds.has(c.caseId), 'duplicate case ID'); caseIds.add(c.caseId)
      ensure(!querySet.has(c.query), 'exact duplicate query'); querySet.add(c.query)
      ensure(!groupSplit.has(c.scenarioGroupId) || groupSplit.get(c.scenarioGroupId) === c.split, 'group split leakage')
      groupSplit.set(c.scenarioGroupId, c.split)
      const group = groups.get(c.scenarioGroupId)
      ensure(group && group.split === c.split, 'group registry mismatch')
      perDomain[group.primaryDomain] = (perDomain[group.primaryDomain] ?? 0) + 1
      for (const e of c.requiredEvidenceGroups.flat()) {
        ensure(!evidenceSplit.has(e) || evidenceSplit.get(e) === c.split, 'exact gold anchor split leakage')
        evidenceSplit.set(e, c.split)
      }
      const dependency = dependencies.get(c.caseId)
      ensure(check.dependencies(dependency), `dependency schema ${c.caseId}`)
      ensure(dependency && dependency.independentReview === false, 'dependency review missing')
      for (const e of dependency.sourceSections)
        ensure(sectionMap.get(e.evidenceId) === e.quote, `quote mismatch ${c.caseId}`)
      for (const e of dependency.sourceSections) {
        if (!contentSplits.has(e.quote)) contentSplits.set(e.quote, new Set())
        contentSplits.get(e.quote).add(c.split)
      }
      if (c.difficulty.reasoningType === 'MULTI_HOP') {
        ensure(dependency.requires.length > 0, 'missing multi-hop edge')
        for (const edge of dependency.requires) {
          ensure(edge.fromGroup < edge.toGroup && edge.toGroup < c.requiredEvidenceGroups.length, 'invalid hop DAG')
          ensure(dependency.sourceSections.some(e => e.quote === edge.intermediateFact), 'ungrounded intermediate fact')
          const target = c.requiredEvidenceGroups[edge.toGroup][0].split(':')
          ensure(edge.intermediateFact.includes(target[0]) && edge.intermediateFact.includes(target[1]), 'unresolvable multi-hop pointer')
        }
      }
      levels[c.split][c.difficulty.level] = (levels[c.split][c.difficulty.level] ?? 0) + 1
      scenarios[c.split][c.scenarioType] = (scenarios[c.split][c.scenarioType] ?? 0) + 1
      all.push(c)
    }
    for (const g of groups.values()) ensure(cases.filter(c => c.scenarioGroupId === g.scenarioGroupId).length === 2, 'pair incomplete')
  }
  for (const count of Object.values(perDomain)) ensure(count === (publicOnly ? 28 : 40), 'domain count')
  ensure(groupSplit.size === (publicOnly ? 140 : 200) && all.length === (publicOnly ? 280 : 400), 'total groups/cases')
  const distribution = json(join(publicDir, 'distribution.json'))
  for (const split of Object.keys(distribution)) {
    if (publicOnly && split === 'LOCKED_TEST') continue
    for (const [key, value] of Object.entries(distribution[split].difficulty)) ensure(levels[split][key] === value, 'difficulty quota')
    for (const [key, value] of Object.entries(distribution[split].scenario)) ensure(scenarios[split][key] === value, 'scenario quota')
  }
  if (!publicOnly) ensure(sha(join(privateDir, 'holdout-candidate.cases.jsonl')) === json(join(publicDir, 'holdout-commitment.json')).caseFileSha256, 'holdout commitment')
  ensure(!existsSync(join(publicDir, 'holdout-candidate.cases.jsonl')), 'holdout questions published in fixture')
  return { status: 'STRUCTURAL_AND_REFERENTIAL_PASS', holdoutRead: !publicOnly, cases: all.length, groups: groupSplit.size,
    documents: snapshot.documents.length, sections: sectionMap.size, counts, levels, scenarios, perDomain,
    multiHopCases: all.filter(c => c.difficulty.reasoningType === 'MULTI_HOP').length,
    exactQueryDuplicates: 0, sharedGoldAnchorsAcrossSplits: 0,
    sharedEvidenceTextsAcrossSplits: [...contentSplits.values()].filter(s => s.size > 1).length,
    independentReview: 'NOT_DONE', semanticBlindness: 'NOT_CERTIFIED', productionRag: 'NOT_ASSESSED_BY_VALIDATOR',
    limits: ['Self-authored synthetic data; schema success does not certify semantic correctness.',
      'Common authorization/refusal mechanics and authoring templates occur across splits; not unseen-template evaluation.',
      'All cases use current-time paraphrased questions; noisy/multi-turn/time-boundary strata remain uncovered.',
      'Gold qrels cover required evidence only; full-corpus relevance labels are incomplete.',
      'Declared permission fixture must be enforced by a real retrieval backend before permission scores are valid.'] }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [publicDir, privateDir] = process.argv.slice(2)
  ensure(publicDir && privateDir, 'Usage: node validate.mjs PUBLIC_DIR PRIVATE_DIR')
  const publicOnly = privateDir === '--public-only'
  const result = audit(resolve(publicDir), publicOnly ? null : resolve(privateDir), publicOnly)
  if (!process.argv.includes('--no-write'))
    writeFileSync(join(publicDir, publicOnly ? 'public-validation-report.json' : 'validation-report.json'), JSON.stringify(result, null, 2) + '\n')
  console.log(JSON.stringify(result))
}
