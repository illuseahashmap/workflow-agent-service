import { createHash } from 'node:crypto'
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

// Validates the first 10 × 4 unpublished fixture drafts; published corpus versions
// must use their own immutable-version and approval checks rather than this draft gate.
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const corpusRoot = join(repoRoot, 'docs', 'fixtures', 'knowledge', 'domains')
const blueprintPath = join(repoRoot, 'docs', 'architecture', 'rag-ten-domain-corpus-design.md')
const domainPrefixes = new Map([
  ['procurement', 'PROC'],
  ['finance', 'FIN'],
  ['hr', 'HR'],
  ['legal', 'LEG'],
  ['sales', 'SAL'],
  ['customer-support', 'SUP'],
  ['it-operations', 'OPS'],
  ['information-security', 'SEC'],
  ['project-delivery', 'DEL'],
  ['administration', 'ADM'],
])
const kinds = ['POL', 'SOP', 'FAQ', 'CHG']
const errors = []
const read = (path) => readFileSync(path, 'utf8')

function checkLinks(path, body) {
  for (const match of body.matchAll(/\]\(([^)]+)\)/g)) {
    const target = match[1].split('#')[0]
    if (!target || /^(?:https?:|mailto:)/i.test(target)) continue
    const linkedPath = resolve(dirname(path), decodeURIComponent(target))
    if (!existsSync(linkedPath)) errors.push(`Broken link: ${path} -> ${target}`)
  }
}

let total = 0
for (const [domain, prefix] of domainPrefixes) {
  const folder = join(corpusRoot, domain)
  if (!existsSync(folder)) {
    errors.push(`Missing domain: ${domain}`)
    continue
  }
  const markdownFiles = readdirSync(folder).filter((name) => name.endsWith('.md')).sort()
  const expected = kinds.map((kind) => `${prefix}-${kind}-01.md`).sort()
  if (markdownFiles.join('|') !== expected.join('|')) {
    errors.push(`Document set mismatch in ${domain}: ${markdownFiles.join(', ')}`)
  }
  for (const name of markdownFiles) {
    total++
    const path = join(folder, name)
    const body = read(path)
    if (!body.startsWith(`# ${name.slice(0, -3)}`)) errors.push(`Title/code mismatch: ${path}`)
    if (!body.includes('DRAFT')) errors.push(`Missing DRAFT status: ${path}`)
    if (!body.includes('approvedAt')) errors.push(`Missing approvedAt: ${path}`)
    if (!body.includes('knowledgeScope')) errors.push(`Missing knowledgeScope: ${path}`)
    if (!body.includes('supersedes')) errors.push(`Missing supersedes: ${path}`)
    if (!/(?:effectiveFrom|生效区间|生效日期)/.test(body)) errors.push(`Missing effective time: ${path}`)
    if (!/^## §1 /m.test(body)) errors.push(`Missing stable section numbering: ${path}`)
    if (/[ \t]+$/m.test(body)) errors.push(`Trailing whitespace: ${path}`)
    checkLinks(path, body)
  }
}

if (total !== 40) errors.push(`Expected 40 domain documents, found ${total}`)
const blueprint = read(blueprintPath)
checkLinks(blueprintPath, blueprint)
for (const [, prefix] of domainPrefixes) {
  for (const kind of kinds) {
    const code = `${prefix}-${kind}-01`
    if (!blueprint.includes(`[` + '`' + code + '`' + `](`)) {
      errors.push(`Blueprint missing document link: ${code}`)
    }
  }
}

const policyPath = join(corpusRoot, 'procurement', 'PROC-POL-01.md')
const svgPath = join(corpusRoot, 'procurement', 'assets', 'PROC-POL-01', 'PROC-POL-01-FIG-01.svg')
const svg = read(svgPath)
const svgHash = createHash('sha256').update(readFileSync(svgPath)).digest('hex')
if (!read(policyPath).includes(svgHash)) errors.push('PROC-POL-01 SVG hash mismatch')
if (/<script\b|\b(?:href|xlink:href)=["']https?:/i.test(svg)) {
  errors.push('PROC-POL-01 SVG contains script or remote asset')
}
if (!statSync(svgPath).isFile()) errors.push('PROC-POL-01 figure is not a file')

for (const [name, path, anchors] of [
  ['procurement', policyPath, ['2,000', '20,000']],
  ['finance', join(corpusRoot, 'finance', 'FIN-POL-01.md'), ['30 个自然日', '500', '700', '350']],
  ['HR', join(corpusRoot, 'hr', 'HR-POL-01.md'), ['3 个工作日', '5 个工作日', '10 个工作日']],
  ['security', join(corpusRoot, 'information-security', 'SEC-POL-01.md'), ['30 天', '90 天', '30 分钟']],
]) {
  const body = read(path)
  for (const anchor of anchors) {
    if (!body.includes(anchor)) errors.push(`Handbook anchor missing in ${name}: ${anchor}`)
  }
}

if (errors.length) {
  for (const error of errors) process.stderr.write(`${error}\n`)
  process.exitCode = 1
} else {
  process.stdout.write(`Knowledge corpus check passed: ${total} drafts, 10 domains, links and SVG hash valid.\n`)
}
