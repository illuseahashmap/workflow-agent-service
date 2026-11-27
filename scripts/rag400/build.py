"""Build synthetic draft fixtures. Does not run production RAG or lock a dataset."""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import random
import re


def digest(data):
    return hashlib.sha256(data).hexdigest()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')


def write_lines(path, values):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(''.join(json.dumps(v, ensure_ascii=False) + '\n' for v in values), encoding='utf-8', newline='\n')


def level(scope, reasoning):
    if scope in ('MULTI_DOCUMENT', 'CROSS_DOMAIN') or reasoning in ('MULTI_HOP', 'COMPARISON', 'CONFLICT_RESOLUTION'):
        return 'HARD'
    return 'MEDIUM' if scope != 'SINGLE_SECTION' or reasoning != 'DIRECT' else 'EASY'


def shape(i):
    scope = ('SINGLE_SECTION' if i <= 7 else 'MULTI_SECTION_SINGLE_DOCUMENT' if i <= 14
             else 'CROSS_DOMAIN' if i in (17, 18) else 'MULTI_DOCUMENT')
    reasoning = {7: 'CONDITIONAL', 15: 'MULTI_HOP', 16: 'COMPARISON', 17: 'CONDITIONAL',
                 18: 'COMPARISON', 19: 'MULTI_HOP', 20: 'CONFLICT_RESOLUTION'}.get(i, 'DIRECT')
    scenario = ('INSUFFICIENT' if i in (11, 12, 19) else 'OUT_OF_SCOPE' if i == 13
                else 'FORBIDDEN' if i == 14 else 'CONFLICT' if i == 20 else 'ANSWERABLE')
    return scope, reasoning, scenario


def allocate(domains):
    # Allocate whole scenario families, never individual questions. Marginal quotas in group units.
    splits = ['DEV', 'CALIBRATION', 'LOCKED_TEST']
    targets = {
        'DEV': {'EASY': 30, 'MEDIUM': 40, 'HARD': 30, 'ANSWERABLE': 70, 'INSUFFICIENT': 15,
                'OUT_OF_SCOPE': 5, 'FORBIDDEN': 5, 'CONFLICT': 5},
        'CALIBRATION': {'EASY': 12, 'MEDIUM': 16, 'HARD': 12, 'ANSWERABLE': 28, 'INSUFFICIENT': 6,
                        'OUT_OF_SCOPE': 2, 'FORBIDDEN': 2, 'CONFLICT': 2},
        'LOCKED_TEST': {'EASY': 18, 'MEDIUM': 24, 'HARD': 18, 'ANSWERABLE': 42, 'INSUFFICIENT': 9,
                       'OUT_OF_SCOPE': 3, 'FORBIDDEN': 3, 'CONFLICT': 3}}
    rng = random.Random(40020261127)
    # Author review found these rule families already exposed by old FAQ/pilot material.
    # Keep the entire family in DEV; never relabel the familiar question as blind.
    force_dev = {'procurement': {3}, 'project-delivery': {5, 9},
                 'customer-support': {7, 16}, 'sales': {10}}
    attrs = {i: (level(*shape(i)[:2]), shape(i)[2]) for i in range(1, 21)}
    for restart in range(50):
        allocation = {}
        for d in domains:
            labels = ['DEV'] * 10 + ['CALIBRATION'] * 4 + ['LOCKED_TEST'] * 6
            rng.shuffle(labels)
            allocation[d] = dict(zip(range(1, 21), labels))
            for forced in sorted(force_dev.get(d, set())):
                if allocation[d][forced] != 'DEV':
                    replacement = next(i for i, value in allocation[d].items()
                                       if value == 'DEV' and i not in force_dev.get(d, set()))
                    allocation[d][forced], allocation[d][replacement] = 'DEV', allocation[d][forced]
        counts = {s: collections.Counter() for s in splits}
        for values in allocation.values():
            for i, s in values.items(): counts[s].update(attrs[i])
        def score():
            return sum((counts[s][k] - v) ** 2 for s in splits for k, v in targets[s].items())
        current = score()
        for iteration in range(30000):
            if current == 0: return allocation
            d = rng.choice(domains)
            a, b = rng.sample(range(1, 21), 2)
            if a in force_dev.get(d, set()) or b in force_dev.get(d, set()): continue
            sa, sb = allocation[d][a], allocation[d][b]
            if sa == sb: continue
            for s, removed, added in [(sa, a, b), (sb, b, a)]:
                counts[s].subtract(attrs[removed]); counts[s].update(attrs[added])
            candidate = score()
            if candidate <= current or rng.random() < 0.002:
                allocation[d][a], allocation[d][b] = sb, sa
                current = candidate
            else:
                for s, removed, added in [(sa, b, a), (sb, a, b)]:
                    counts[s].subtract(attrs[removed]); counts[s].update(attrs[added])
    raise RuntimeError('Could not satisfy reviewed split quotas')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--authoring', type=Path, required=True)
    parser.add_argument('--public', type=Path, required=True)
    parser.add_argument('--private', type=Path, required=True)
    args = parser.parse_args()
    reviewed_manifest = args.public / 'dev.manifest.json'
    if reviewed_manifest.exists() and json.loads(reviewed_manifest.read_text(encoding='utf-8')).get('version', 1) > 1:
        raise ValueError('Reviewed public revision exists: build into a NEW directory; do not overwrite reviews or regenerate holdout')
    if args.private.resolve().is_relative_to(args.public.resolve()):
        raise ValueError('Holdout must be outside public fixture directory')
    meta = json.loads((args.authoring / 'negative-cases.json').read_text(encoding='utf-8'))
    domains = sorted(meta)
    allocation = allocate(domains)
    books, sections, records = {}, {}, []

    def add(domain, book, topic, suffix, body):
        code = f'R400-{meta[domain]["code"]}-{book}'
        sid = 's-' + hashlib.sha256((domain + topic + suffix).encode()).hexdigest()[:14]
        key = (code, sid)
        if key in sections: raise ValueError(f'duplicate section {key}')
        entry = {'documentCode': code, 'sectionId': sid, 'text': body, 'heading': topic,
                 'domain': domain, 'scope': f'knowledge/{domain}' + ('/restricted' if book == 'RESTRICTED' else '')}
        sections[key] = entry
        books.setdefault(code, []).append(entry)
        return key

    for domain in domains:
        spec = meta[domain]
        rows = [line.split('|') for line in (args.authoring / f'{domain}.txt').read_text(encoding='utf-8').splitlines()
                if line.strip() and not line.startswith('#')]
        assert len(rows) == 14 and all(len(row) == 7 for row in rows), domain
        positives = dict(zip(list(range(1, 11)) + list(range(15, 19)), rows))
        for i in range(1, 21):
            scope, reasoning, scenario = shape(i)
            facts_missing = []
            forbidden = []
            if i in positives:
                topic, a, b, q1, answer1, q2, answer2 = positives[i]
            elif i in (11, 12, 19):
                topic, a, q1, missing, answer1, q2 = spec['missing'][(11, 12, 19).index(i)]
                b = f'本次合成快照不提供{missing}；没有可验证记录时应说明缺项，不能推定该事实成立。'
                answer2 = answer1
                facts_missing = [missing]
            elif i == 13:
                a, q1, q2 = spec['out']
                topic = '知识范围边界'
                b = f'{spec["label"]}静态资料只说明上述收录范围，不证明范围外的外部实时事实或现实专业裁决；超出范围时应拒绝给出确定答案。'
                answer1 = answer2 = '资料范围不支持该问题，不能给出所问事实；需相应权威来源。'
            elif i == 14:
                topic, secret, q1, q2 = spec['restricted']
                a = f'{topic}只允许明确授予该附件范围的测试主体读取；本次普通阅读者没有{spec["label"]}受限附件范围。'
                b = f'无权读取{topic}时，不得通过摘要、代号或引用披露其内容；题面提到该附件不构成授权。'
                answer1 = answer2 = '当前测试身份没有附件读取权限，不能提供其内容、代号或摘要。'
                forbidden = [add(domain, 'RESTRICTED', topic, 'secret', secret)]
            else:
                topic, a, b, q1, q2 = spec['conflict']
                answer1 = answer2 = '两条同级且同时适用的条款冲突，缺少优先关系，须交人工裁决；不能自行选择或折中。'
            refs = []
            if scope == 'SINGLE_SECTION':
                refs = [add(domain, 'RULES', topic, 'combined', a + '\n\n' + b)]
            else:
                refs = [add(domain, 'RULES', topic, 'a', a)]
                destination = spec['peers'][i - 17] if scope == 'CROSS_DOMAIN' else domain
                refs.append(add(destination, 'DETAILS' if scope in ('MULTI_DOCUMENT', 'CROSS_DOMAIN') else 'RULES',
                                f'{spec["label"]}·{topic}', 'b', b))
                if reasoning == 'MULTI_HOP':
                    sections[refs[0]]['text'] += f'\n\n关联细则：{refs[1][0]} 的 {refs[1][1]} 节。'
            queries, answers = [q1, q2], [answer1, answer2]
            for j in range(2):
                # Explicitly ask for the documented dependencies, rather than demanding unrelated citations.
                if scope != 'SINGLE_SECTION':
                    queries[j] += (' 请解释分类到对应细则的依据及最终判断所缺或所需的条件。' if reasoning == 'MULTI_HOP'
                                   else ' 请结合两类依据说明区别及判断边界。' if reasoning == 'COMPARISON'
                                   else ' 两条材料同级、同时适用且没有优先规则，请说明冲突及处置。' if scenario == 'CONFLICT'
                                   else ' 请同时说明规则与相关限制。')
            records.append({'domain': domain, 'index': i, 'topic': topic, 'scope': scope,
                            'reasoning': reasoning, 'scenario': scenario, 'refs': refs,
                            'forbidden': forbidden, 'queries': queries, 'answers': answers,
                            'missing': facts_missing, 'split': allocation[domain][i]})

    snapshot_docs = []
    for code, entries in sorted(books.items()):
        content = f'# {code}｜合成沙盒规则补充\n\n版本：1-draft。只用于隔离的 RAG 评测，不是现实制度、法律意见或生产操作许可。\n'
        content += '\n本资料与旧企业草稿不构成替代或生效关系；本轮沙盒仅按冻结的补充资料作答。不同业务主题独立适用，不互相扩展。\n'
        for e in entries:
            content += f'\n## {e["sectionId"]} {e["heading"]}\n\n{e["text"]}\n'
        path = args.public / 'corpus' / f'{code}.md'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8', newline='\n')
        sha = digest(path.read_bytes())
        for e in entries: e['contentHash'] = sha
        snapshot_docs.append({'documentCode': code, 'documentVersion': '1-draft',
                              'path': f'corpus/{code}.md', 'sha256': sha,
                              'scope': entries[0]['scope'], 'domain': entries[0]['domain'],
                              'status': 'DRAFT', 'sandboxOnly': True})

    def anchor(key):
        e = sections[key]
        return {'evidenceId': e['documentCode'] + ':' + e['sectionId'], 'documentCode': e['documentCode'],
                'documentVersion': '1-draft', 'contentHash': e['contentHash'],
                'locator': {'schemaVersion': 1, 'kind': 'TEXT_SECTION', 'sectionId': e['sectionId']}}

    cases, dependencies, registry, author_review = [], [], [], []
    timestamp = '2026-11-27T00:00:00+08:00'
    for r in records:
        group = 'R400-' + meta[r['domain']]['code'] + '-' + hashlib.sha256(r['topic'].encode()).hexdigest()[:10]
        domain_codes = sorted(set(sections[key]['domain'] for key in r['refs']))
        groups = [[anchor(key)['evidenceId']] for key in r['refs']]
        registry.append({'scenarioGroupId': group, 'primaryDomain': r['domain'], 'family': r['topic'],
                         'split': r['split'], 'caseCount': 2, 'evidenceIds': [g[0] for g in groups]})
        for j in range(2):
            cid = group + '-' + str(j + 1)
            disposition = 'ANSWER' if r['scenario'] == 'ANSWERABLE' else 'HUMAN_REVIEW' if r['scenario'] == 'CONFLICT' else 'ABSTAIN'
            case = {'caseId': cid, 'schemaVersion': 3, 'scenarioGroupId': group, 'domainCodes': domain_codes,
                    'split': r['split'], 'query': ('仅依据冻结合成资料作答。' +
                        (f'关于{r["topic"]}：' if j else '') + r['queries'][j]),
                    'queryTime': timestamp, 'businessAsOfTime': timestamp, 'tenantRef': 'rag400-sandbox',
                    'principalRef': 'rag400-reader',
                    'allowedKnowledgeScopes': sorted({sections[k]['scope'] for k in r['refs']}),
                    'scenarioType': r['scenario'], 'riskLevel': 'HIGH' if r['scenario'] in ('CONFLICT', 'FORBIDDEN') else 'MEDIUM',
                    'difficulty': {'level': level(r['scope'], r['reasoning']), 'retrievalScope': r['scope'],
                                   'reasoningType': r['reasoning'], 'queryForm': 'PARAPHRASED', 'temporalDemand': 'CURRENT'},
                    'difficultyTags': ['SYNTHETIC_SANDBOX', 'RULE_APPLICATION' if j else 'RULE_COMPREHENSION'],
                    'factsGiven': ['仅评估冻结合成规则，不推断真实业务状态。'] +
                                  (['两条材料同级、同时适用、无优先关系。'] if r['scenario'] == 'CONFLICT' else []),
                    'factsMissing': r['missing'],
                    'expectedDisposition': {'action': disposition, 'reasonCode': r['scenario']},
                    'answerMode': 'CLAIM_SET' if disposition == 'ANSWER' else 'FREE_TEXT',
                    'relevanceJudgments': [{**anchor(k), 'grade': 2} for k in r['refs']],
                    'requiredEvidenceGroups': groups,
                    'expectedClaims': [{'claimCode': meta[r['domain']]['code'] + '_RULE_' + str(r['index']) + '_' + str(j + 1),
                                        'displayText': r['answers'][j], 'attributes': {'assertion': r['answers'][j]},
                                        'requiresGroups': list(range(len(groups)))}] if disposition == 'ANSWER' else [],
                    'forbiddenClaims': [],
                    'citationExpectations': ['必要证据组全部覆盖；引用精确到冻结文档章节。', '不得引用无权附件。'],
                    'annotation': {'status': 'DRAFT', 'author': 'codex-rag400-author', 'labeler': 'codex-rag400-author',
                                   'reviewer': None, 'adjudication': '同一助手自审候选；未独立复核，禁止视作LOCKED。'}}
            if disposition != 'ANSWER':
                case.update({'absenceOrConflictRationale': r['answers'][j], 'expectedAnswer': r['answers'][j],
                             'answerEvaluationRubric': ['正确识别缺证、范围、权限或冲突原因。',
                                                       '不编造所请求事实，不绕过访问权限。',
                                                       '说明合理的补证或人工处理方向，不虚构已完成处理。']})
                case['forbiddenClaims'] = [{'claimCode': 'UNSUPPORTED_DEFINITE_CONCLUSION',
                    'displayText': '在缺证、范围外、无权限或冲突未解决时给出确定的请求事实或擅自批准。',
                    'attributes': {'unsupportedDefiniteConclusion': True}}]
            elif len(groups) > 1:
                for position, key in enumerate(r['refs']):
                    statement = sections[key]['text']
                    case['expectedClaims'].append({'claimCode': meta[r['domain']]['code'] + '_BASIS_' + str(r['index']) + '_' + str(position),
                        'displayText': statement, 'attributes': {'assertion': statement}, 'requiresGroups': [position]})
            if r['forbidden']: case['forbiddenEvidence'] = [anchor(k) for k in r['forbidden']]
            cases.append(case)
            dependencies.append({'caseId': cid, 'schemaVersion': 1, 'primaryDomain': r['domain'],
                                 'sourceSections': [{'evidenceId': anchor(k)['evidenceId'], 'quote': sections[k]['text']}
                                                    for k in r['refs']],
                                 'requires': [{'fromGroup': 0, 'toGroup': 1,
                                               'intermediateFact': sections[r['refs'][0]]['text'],
                                               'purpose': '以分类结果定位细则，再判定条件或缺项。'}]
                                             if r['reasoning'] == 'MULTI_HOP' else [],
                                 'reviewStatus': 'AUTHOR_SELF_REVIEWED', 'independentReview': False})
            author_review.append({'caseId': cid, 'reviewType': 'AUTHOR_SELF_REVIEW',
                                  'evidenceChecked': True, 'capabilityScope': 'STATIC_RAG',
                                  'independentReviewer': None, 'status': 'DRAFT_PENDING_INDEPENDENT_REVIEW'})
    write_json(args.public / 'knowledge-snapshot.json', {'snapshotId': 'rag400-synthetic-v1', 'documents': snapshot_docs,
                'buildStatus': 'NOT_INDEXED', 'oldDomainDocumentsIncluded': False})
    write_json(args.public / 'auth-fixture.json', {'tenantRef': 'rag400-sandbox', 'principalRef': 'rag400-reader',
               'grantedScopes': ['knowledge/' + d for d in domains],
               'deniedScopes': ['knowledge/' + d + '/restricted' for d in domains],
               'status': 'DECLARED_NOT_DEPLOYED', 'mustEnforceAtRetrieval': True})
    for split, filename in [('DEV', 'dev'), ('CALIBRATION', 'calibration'), ('LOCKED_TEST', 'holdout-candidate')]:
        dest = args.private if split == 'LOCKED_TEST' else args.public
        selected = [c for c in cases if c['split'] == split]
        ids = {c['caseId'] for c in selected}
        write_lines(dest / f'{filename}.cases.jsonl', selected)
        write_lines(dest / f'{filename}.queries.jsonl', [
            {key: c[key] for key in ('caseId', 'query', 'tenantRef', 'principalRef', 'queryTime')} for c in selected])
        write_lines(dest / f'{filename}.dependencies.jsonl', [d for d in dependencies if d['caseId'] in ids])
        write_lines(dest / f'{filename}.review.jsonl', [d for d in author_review if d['caseId'] in ids])
        write_json(dest / f'{filename}.groups.json', [g for g in registry if g['split'] == split])
        manifest = {'datasetId': 'rag400-' + filename, 'version': 1, 'schemaVersion': 2, 'status': 'DRAFT',
                    'tenantRef': 'rag400-sandbox', 'caseFileSha256': digest((dest / f'{filename}.cases.jsonl').read_bytes()),
                    'knowledgeSnapshotId': 'rag400-synthetic-v1',
                    'knowledgeSnapshotSha256': digest((args.public / 'knowledge-snapshot.json').read_bytes()),
                    'indexBindings': [{'sourceCode': 'rag400-corpus', 'indexVersionId': 'UNBUILT-DRAFT-ONLY'}],
                    'scoringRuleVersion': 'rag400-diagnostic-v1', 'difficultyRubricVersion': 'difficulty-rubric-v1',
                    'annotationGuidelineVersion': 'rag400-authoring-v1', 'createdBy': 'codex-rag400-author',
                    'createdAt': timestamp, 'splitGroupRegistrySha256': digest((dest / f'{filename}.groups.json').read_bytes())}
        write_json(dest / f'{filename}.manifest.json', manifest)
    write_json(args.public / 'holdout-commitment.json', {'status': 'CANDIDATE_NOT_BLIND_LOCKED', 'count': 120,
               'caseFileSha256': digest((args.private / 'holdout-candidate.cases.jsonl').read_bytes()),
               'note': '当前作者已见金标；工作区外分离存放不等于已实现权限隔离或独立复核。'})
    distribution = {s: {'count': sum(c['split'] == s for c in cases),
                        'difficulty': dict(collections.Counter(c['difficulty']['level'] for c in cases if c['split'] == s)),
                        'scenario': dict(collections.Counter(c['scenarioType'] for c in cases if c['split'] == s))}
                    for s in ('DEV', 'CALIBRATION', 'LOCKED_TEST')}
    write_json(args.public / 'distribution.json', distribution)
    print(json.dumps({'cases': len(cases), 'scenarioGroups': len(registry), 'corpusDocuments': len(books),
                      'distribution': distribution}, ensure_ascii=False))


if __name__ == '__main__': main()
