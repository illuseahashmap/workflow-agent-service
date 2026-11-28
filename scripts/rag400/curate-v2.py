"""Auditable author review. Creates a new dataset; never overwrites v1 or old scores."""
import hashlib
import json
from pathlib import Path
import shutil
import importlib.util

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
    spec=importlib.util.spec_from_file_location('natural_questions',Path(__file__).with_name('natural-questions.py'))
    natural=importlib.util.module_from_spec(spec);spec.loader.exec_module(natural)
    changes, reviews, hashes = [], {}, {}
    corrections = {
        'R400-ADM-e59e2543f7-1': '不会。停车月卡绑定车牌及办公地点，不随员工口头转让。',
        'R400-ADM-eba06c67c3-2': '不会。现场已发生的布置费用另核，不因会议取消自动归零。',
        'R400-ADM-6bc888946a-2': '借用记录通过设备序列号区别同型号投影仪。',
        'R400-ADM-d6f9b06511-1': '不可以。服务商接单不证明修复完成。',
        'R400-SUP-283d005035-1': '不能。后补附件不证明首次提交时资料已完整。',
        'R400-FIN-b85a7a8e3a-2': '分摊依据至少需留存项目和比例。',
        'R400-OPS-cb402a0bda-1': '不能。没有观察窗口数据时不能宣布灰度验证成功。',
        'R400-OPS-cb402a0bda-2': '符合。灰度异常达到发布单明确的退出条件时停止扩大范围并按已批方案处理。',
        'R400-PROC-cc2ddf46f5-2': '可以。供应商关闭续订回执已核验，可以标记自动续订已取消。',
        'R400-SAL-1be8d714ce-1': '不等于。渠道报备记录机会归属，不直接证明最终签约权。',
        'R400-ADM-20afcfc30d-2': '符合。共享柜分配到期后先通知领回物品，再按受控清理程序处理。',
        'R400-HR-b5187ef300-1': '不能。面试通过不等于录用通知已签发。',
        'R400-HR-b5187ef300-2': '不能。面试反馈只记录岗位相关评价，不写无关家庭和医疗信息。',
        'R400-SEC-3811d46399-2': '材料密级标签由数据所有者确认，不能由上传者随意降级。',
        'R400-PROC-5a40aa537d-1': '供应商仅更名且登记主体编号未变，需补更名证明。',
        'R400-DEL-26453c7b04-2': '缺陷修复提交后须按原复现条件复验。',
        'R400-DEL-0512c34530-2': '符合。迁移验收需核对记录总量与关键业务抽样，条数相同不证明字段映射正确。',
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
            revised_question=natural.naturalize(c)
            if revised_question!=c['query']:
                c['query']=revised_question
                reasons.append('Remove authoring topic prefix; resolve missing referents using existing question context; retain factual constraints.')
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
         'remoteReview': {'sourceCommit':'3fb92ebe12d2feaeae6d0a98d7de0c583f964d70',
                         'flaggedCases':20,'narrowedRequiredClaims':list(corrections),
                         'alreadyCoveredByActionClarification':['R400-SEC-11f87a1347-2','R400-SAL-098db76d14-2'],
                         'retained':{'R400-OPS-5ff50a6004-1':'Question explicitly asks related restrictions; sourceSections includes affected-path verification. Missing retrieval is not a reason to erase the gold obligation.'}},
         'changes': changes, 'overrides': reviews})
    print(f'Created {DEST}; {len(changes)} changed cases, {len(reviews)} disposition clarifications')


if __name__ == '__main__':
    main()
