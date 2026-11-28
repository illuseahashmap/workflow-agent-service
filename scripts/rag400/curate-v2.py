"""Auditable author review. Creates a new dataset; never overwrites v1 or old scores."""
import hashlib
import json
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / 'docs/fixtures/knowledge/rag400-v1'
DEST = BASE.with_name('rag400-v2')


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def save(p, value):
    p.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')


def rows(p):
    return [json.loads(x) for x in p.read_text(encoding='utf-8').splitlines() if x.strip()]


def lines(p, values):
    p.write_text(''.join(json.dumps(x, ensure_ascii=False) + '\n' for x in values), encoding='utf-8', newline='\n')


def main():
    DEST.mkdir(exist_ok=False)
    shutil.copytree(BASE / 'corpus', DEST / 'corpus')
    for name in ('.gitattributes', 'knowledge-snapshot.json', 'auth-fixture.json', 'distribution.json'):
        shutil.copyfile(BASE / name, DEST / name)
    (DEST / 'holdout').mkdir()
    grants = json.loads((BASE / 'auth-fixture.json').read_text(encoding='utf-8'))['grantedScopes']
    changes, reviews, hashes = [], {}, {}
    corrections = {
        'R400-ADM-e59e2543f7-1': '不会。停车月卡绑定车牌及办公地点，不随员工口头转让。',
        'R400-ADM-eba06c67c3-2': '不会。现场已发生的布置费用另核，不因会议取消自动归零。',
    }
    for split in ('dev', 'calibration', 'holdout-candidate'):
        src = BASE / 'holdout' if split == 'holdout-candidate' else BASE
        dst = DEST / 'holdout' if split == 'holdout-candidate' else DEST
        for suffix in ('dependencies.jsonl', 'groups.json', 'review.jsonl'):
            shutil.copyfile(src / f'{split}.{suffix}', dst / f'{split}.{suffix}')
        cases = rows(src / f'{split}.cases.jsonl')
        hashes[split] = sha(src / f'{split}.cases.jsonl')
        for c in cases:
            before = json.dumps(c, ensure_ascii=False, sort_keys=True)
            reasons = []
            if c['allowedKnowledgeScopes'] != grants:
                c['allowedKnowledgeScopes'] = grants
                reasons.append('Align gold scope declaration with fixed auth fixture; never route by gold evidence.')
            if c['query'].startswith('仅依据冻结合成资料作答。'):
                c['query'] = c['query'].removeprefix('仅依据冻结合成资料作答。')
                reasons.append('Remove benchmark instruction prefix, as already done for DEV/CALIBRATION v3.')
            if c['scenarioType'] == 'CONFLICT' and '题面约定：' not in c['query']:
                premise = '题面约定：两份条款对同一事项分别规定唯一执行值或唯一责任组，不能解释成可同时满足的下限或并行安排。'
                c['query'] += ' ' + premise
                c['factsGiven'].append(premise)
                reasons.append('Make mutually exclusive conflict premise explicit instead of assuming incompatible minima.')
            if c['caseId'] in corrections:
                claim = c['expectedClaims'][0]
                claim['displayText'] = corrections[c['caseId']]
                claim['attributes']['assertion'] = claim['displayText']
                reasons.append('Only require facts asked by this question; sibling-topic detail remains optional.')
            if c['scenarioType'] == 'INSUFFICIENT' and c['caseId'].endswith('-2') and c['caseId'] != 'R400-HR-8626a60af7-2':
                reviews[c['caseId']] = {
                    'acceptedActions': ['ABSTAIN', 'ANSWER'],
                    'rationale': 'Rule/boundary question: a supported negative explanation and explicit inability to confirm the business outcome are both admissible. Never approve the unverified outcome.',
                    'requiredMeaning': c['expectedAnswer'],
                }
            if reasons:
                changes.append({'caseId': c['caseId'], 'reasons': reasons,
                                'beforeSha256': hashlib.sha256(before.encode()).hexdigest(),
                                'afterSha256': hashlib.sha256(json.dumps(c, ensure_ascii=False, sort_keys=True).encode()).hexdigest()})
        lines(dst / f'{split}.cases.jsonl', cases)
        queries = rows(src / f'{split}.queries.jsonl')
        byid = {c['caseId']: c for c in cases}
        for q in queries:
            q['query'] = byid[q['caseId']]['query']
        lines(dst / f'{split}.queries.jsonl', queries)
        manifest = json.loads((src / f'{split}.manifest.json').read_text(encoding='utf-8'))
        manifest.update(version=4, caseFileSha256=sha(dst / f'{split}.cases.jsonl'),
                        annotationGuidelineVersion='rag400-author-review-v4')
        save(dst / f'{split}.manifest.json', manifest)
    save(DEST / 'holdout-commitment.json', {'status': 'PUBLISHED_NOT_BLIND', 'count': 120,
         'caseFileSha256': sha(DEST / 'holdout/holdout-candidate.cases.jsonl'),
         'note': 'New version after documented author review; original commitment remains unchanged in v1.'})
    save(DEST / 'gold-review.json', {'version': 2, 'independentReview': False,
         'reviewScope': 'All 400 structural/referential checks; targeted semantic review of all 60 insufficient cases, known overbroad claims and conflict premises. Not independent adjudication of all 400.',
         'sourceCaseHashes': hashes, 'snapshotSha256': sha(DEST / 'knowledge-snapshot.json'),
         'caseHashes': {s: sha((DEST / 'holdout' if s == 'holdout-candidate' else DEST) / f'{s}.cases.jsonl') for s in hashes},
         'changes': changes, 'overrides': reviews})
    print(f'Created {DEST}; {len(changes)} changed cases, {len(reviews)} disposition clarifications')


if __name__ == '__main__':
    main()
