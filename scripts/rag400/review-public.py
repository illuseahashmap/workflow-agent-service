"""Reviewed v2 corrections for public candidates ONLY. No private path is opened."""
import hashlib
import json
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[2]
P = ROOT / 'docs/fixtures/knowledge/rag400-v1'
BACKUP = Path('C:/Users/email/Documents/Codex/RAG-Evaluation/rag400-project-run-20261127/public-before-review')
def read(p): return json.loads(p.read_text(encoding='utf-8'))
def rows(p): return [json.loads(s) for s in p.read_text(encoding='utf-8').splitlines() if s]
def save(p,x): p.write_text(json.dumps(x,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
def lines(p,x): p.write_text(''.join(json.dumps(v,ensure_ascii=False)+'\n' for v in x),encoding='utf-8',newline='\n')
def digest(p): return hashlib.sha256(p.read_bytes()).hexdigest()

# Explicitly reviewed answer corrections: avoid answering the sibling question instead.
answers = {
 'R400-SUP-049e8914bd-2':'不能把客户沉默默认为确认解决；需核对合同关闭条件及通知证据。',
 'R400-FIN-e21b247981-2':'不能未经确认按人数平均分；须取得各受益项目的已确认分摊比例。',
 'R400-FIN-81ec8f5737-2':'不能确认尚未核销；未提供台账不是核销额为零的证据，需查询已核销记录。',
 'R400-SUP-93226653c5-1':'应查许可证支持清单，清单不要求客户提供私钥；核验产品版本、离线请求摘要和授权状态。',
 'R400-PROC-fc6f91dadc-2':'采购验收确认安装服务交付；财务核对收款主体与合同主体一致，或存在经审核的代收依据。',
 'R400-OPS-6269eaead2-2':'分别记录客户影响与可见沟通、技术恢复验证，并相互关联；关联和恢复仍不自动关闭客户工单。',
 'R400-OPS-a4670a8450-2':'不能把工单创建时刻替代确认的影响起点；还需恢复验证时间才能计算故障时长。',
 'R400-PROC-12d97b6efa-2':'不能保证送到使用现场；必须明确约定交付地点及交付方式。',
 'R400-SAL-098db76d14-2':'不能用草稿创建日代替正式签发日；须核对正式签发记录及有效截止日。',
 'R400-SEC-6bc23f207b-1':'本合成库不包含真实管理员密码，不能提供或猜测；需要访问时应走授权凭据管理流程，不要求披露密码。',
}
queries = {
 'R400-PROC-e5567edc06-2':'仅依据冻结合成资料作答。关于报价税口径：甲乙报价分别为100和105，均未说明税口径，仅凭这两个数字能认定甲净价更低吗？请同时说明规则与相关限制。',
 'R400-ADM-7bd79e543f-2':'仅依据冻结合成资料作答。关于包车费用：只知道起步价100元，未给公里数和等待计费，能按100元确认整个行程预算足够吗？请同时说明规则与相关限制。',
 'R400-FIN-e21b247981-2':'仅依据冻结合成资料作答。关于分摊比例：两个项目共享900元费用，没有已确认比例，能直接按人数平均分吗？请同时说明规则与相关限制。',
}

def main():
    if read(P/'dev.manifest.json')['version'] >= 2:
        raise SystemExit('Public review already applied; do not overwrite later review logs or query revisions.')
    BACKUP.mkdir(parents=True,exist_ok=True)
    auth=read(P/'auth-fixture.json')['grantedScopes']
    changes=[]
    before_protected={p.name:digest(p) for p in [P/'knowledge-snapshot.json',P/'holdout-commitment.json',*sorted((P/'corpus').glob('*.md'))]}
    for split in ('dev','calibration'):
        for suffix in ('cases.jsonl','queries.jsonl','manifest.json','review.jsonl'):
            src=P/f'{split}.{suffix}'
            if not (BACKUP/src.name).exists(): shutil.copyfile(src,BACKUP/src.name)
        dep={r['caseId']:r for r in rows(P/f'{split}.dependencies.jsonl')}
        cases=rows(P/f'{split}.cases.jsonl')
        for c in cases:
            before=json.dumps(c,ensure_ascii=False,sort_keys=True)
            c['allowedKnowledgeScopes']=auth.copy()
            if c['caseId'] in queries: c['query']=queries[c['caseId']]
            if c['caseId'] in answers:
                value=answers[c['caseId']]
                if c['expectedClaims']:
                    c['expectedClaims'][0]['displayText']=value
                    c['expectedClaims'][0]['attributes']['assertion']=value
                else: c['expectedAnswer']=value
            if c['scenarioType']=='CONFLICT':
                # Two minima or nonexclusive assignments can be jointly satisfied: state the intended exclusive case.
                premise='题面约定：两份条款对同一事项分别规定唯一执行值或唯一责任组，不能解释成可同时满足的下限或并行安排。'
                if premise not in c['query']: c['query']+=' '+premise
                if premise not in c['factsGiven']: c['factsGiven'].append(premise)
            if c['expectedClaims'] and len(c['expectedClaims'][0]['displayText'])<=6:
                claim=c['expectedClaims'][0]
                # Preserve the conclusion; add its cited rule so a bare yes/no cannot be the complete rubric.
                quote='；'.join(s['quote'].split('\n\n关联细则：')[0].replace('\n\n',' ') for s in dep[c['caseId']]['sourceSections'])
                value=claim['displayText']+'依据：'+quote
                claim['displayText']=value; claim['attributes']['assertion']=value
            after=json.dumps(c,ensure_ascii=False,sort_keys=True)
            if before!=after:changes.append({'caseId':c['caseId'],'beforeSha256':hashlib.sha256(before.encode()).hexdigest(),'afterSha256':hashlib.sha256(after.encode()).hexdigest()})
        lines(P/f'{split}.cases.jsonl',cases)
        query_rows=rows(P/f'{split}.queries.jsonl'); byid={c['caseId']:c for c in cases}
        for q in query_rows:q['query']=byid[q['caseId']]['query']
        lines(P/f'{split}.queries.jsonl',query_rows)
        manifest=read(P/f'{split}.manifest.json');manifest['version']=2
        manifest['caseFileSha256']=digest(P/f'{split}.cases.jsonl')
        manifest['annotationGuidelineVersion']='rag400-public-review-v2'
        save(P/f'{split}.manifest.json',manifest)
    assert all(digest(P/('corpus/'+name if name.endswith('.md') else name))==v for name,v in before_protected.items())
    save(P/'public-review-v2.json',{'reviewedCases':280,'reviewType':'AUTHOR_SELF_REVIEW','holdout':'NOT_READ_NOT_MODIFIED',
        'protectedPublicFiles':before_protected,'changes':changes,'scopeCorrection':'Fixed ten granted public scopes; never gold-derived source routing',
        'caseVersion':2,'independentReview':False,'originalPublicBackup':str(BACKUP)})
    print(json.dumps({'reviewed':280,'changed':len(changes),'holdout':'NOT_READ_NOT_MODIFIED'}))

if __name__=='__main__':main()
