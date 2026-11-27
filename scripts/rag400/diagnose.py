"""Corpus audit and DEV-only lexical smoke test, NOT production RAG acceptance."""
import argparse
import collections
import hashlib
import json
import math
from pathlib import Path
import re


def tokens(text):
    terms = re.findall(r'[a-z0-9]+', text.lower())
    for phrase in re.findall(r'[\u4e00-\u9fff]+', text):
        terms.extend(phrase[i:i+2] for i in range(len(phrase)-1))
        if len(phrase) == 1: terms.append(phrase)
    return terms


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--public', type=Path, required=True)
    p.add_argument('--original', type=Path, required=True)
    args = p.parse_args()
    original = []
    for path in sorted(args.original.glob('*/*.md')):
        raw = path.read_bytes(); text = raw.decode('utf-8')
        original.append({'path': str(path).replace('\\', '/'), 'sha256': hashlib.sha256(raw).hexdigest(),
                         'characters': len(text), 'headings': len(re.findall(r'^#{2,3} ', text, re.M)),
                         'kind': path.stem.split('-')[1], 'domain': path.parent.name,
                         'status': 'DRAFT', 'assessment': '候选说明不作独立规则；正文与FAQ存在重述，不能按段落数推导独立题数。'})
    save(args.public / 'original-corpus-audit.json', {'documents': original, 'count': len(original),
        'conclusion': '原40篇可支持小型回归，但不足以直接认证200个互不泄漏且有充分金标的场景组。保留旧材料，新补30份独立命名的合成语料。',
        'basis': ['10份CHG为候选/发布审查说明，不是10套独立现行规则。',
                  'POL与FAQ多次解释同一事实，规则家族应归并。',
                  '多数没有独立冲突材料、权限夹具及完整多跳证据。',
                  '已有试点被反复用于修复，不能再充作新盲测。'],
        'notClaimed': '没有声称已证明原材料最多只能编多少题；本次为覆盖与可复核性审查，不是生产检索评测。'})
    snapshot = json.loads((args.public / 'knowledge-snapshot.json').read_text(encoding='utf-8'))
    auth = json.loads((args.public / 'auth-fixture.json').read_text(encoding='utf-8'))
    items = []
    for doc in snapshot['documents']:
        if doc['scope'] not in auth['grantedScopes']: continue
        text = (args.public / doc['path']).read_text(encoding='utf-8')
        for section in text.split('\n## ')[1:]:
            header, body = section.split('\n\n', 1)
            sid = header.split(' ')[0]
            items.append({'id': doc['documentCode'] + ':' + sid, 'text': header.split(' ', 1)[1] + '\n' + body})
    counts = [collections.Counter(tokens(d['text'])) for d in items]
    df = collections.Counter(t for c in counts for t in c)
    lengths = [sum(c.values()) for c in counts]
    avg = sum(lengths) / len(lengths)
    cases = [json.loads(line) for line in (args.public / 'dev.cases.jsonl').read_text(encoding='utf-8').splitlines()]
    outcomes = []
    for c in cases:
        # Common synthetic preamble is not treated as a relevance signal.
        query = c['query'].replace('仅依据冻结合成资料作答。', '')
        terms = set(tokens(query))
        scores = []
        for i, frequency in enumerate(counts):
            score = 0.0
            for t in terms:
                f = frequency[t]
                if f:
                    idf = math.log(1 + (len(items)-df[t]+0.5)/(df[t]+0.5))
                    score += idf*f*2.2/(f+1.2*(0.25+0.75*lengths[i]/avg))
            scores.append((score, items[i]['id']))
        ranked = [item for score, item in sorted(scores, key=lambda row: (-row[0], row[1]))[:10] if score > 0]
        record = {'caseId': c['caseId'], 'scenario': c['scenarioType'], 'level': c['difficulty']['level'],
                  'rankedEvidence': ranked, 'coverage': {}}
        for k in (3, 5, 10):
            covered = sum(bool(set(g) & set(ranked[:k])) for g in c['requiredEvidenceGroups'])
            record['coverage'][str(k)] = {'groups': covered, 'required': len(c['requiredEvidenceGroups']),
                                         'complete': covered == len(c['requiredEvidenceGroups'])}
        outcomes.append(record)
    (args.public / 'dev-lexical-diagnostic.jsonl').write_text(''.join(json.dumps(o, ensure_ascii=False)+'\n' for o in outcomes),
                                                           encoding='utf-8', newline='\n')
    summary = {'status': 'LOCAL_LEXICAL_DIAGNOSTIC_ONLY', 'split': 'DEV', 'queries': len(cases),
               'retrievableSections': len(items), 'algorithm': 'Chinese bigram + ASCII term BM25; k1=1.2,b=0.75',
               'chunks': 'source sections; not production chunker', 'modelCalled': False, 'holdoutScored': False,
               'productionProfile': 'NOT_RUN', 'permissionEnforcement': 'fixture simulation, not backend acceptance',
               'resultMeaning': '全部必要组被召回；不是答案正确率，不是完整qrels的Recall。', 'byK': {}, 'byDifficulty': {}}
    for k in ('3', '5', '10'):
        summary['byK'][k] = {'complete': sum(o['coverage'][k]['complete'] for o in outcomes), 'total': len(outcomes)}
    for lev in ('EASY', 'MEDIUM', 'HARD'):
        selected = [o for o in outcomes if o['level'] == lev]
        summary['byDifficulty'][lev] = {'top5Complete': sum(o['coverage']['5']['complete'] for o in selected), 'total': len(selected)}
    save(args.public / 'dev-lexical-summary.json', summary)
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == '__main__': main()
