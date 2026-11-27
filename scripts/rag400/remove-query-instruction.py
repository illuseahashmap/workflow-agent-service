"""Public-only v3 correction: benchmark policy belongs in factsGiven, not retrieval query."""
import hashlib,json,shutil
from pathlib import Path
P=Path(__file__).resolve().parents[2]/'docs/fixtures/knowledge/rag400-v1'
backup=Path('C:/Users/email/Documents/Codex/RAG-Evaluation/rag400-project-run-20261127/public-v2-before-query-fix')
backup.mkdir(parents=True,exist_ok=True)
def read(p):return json.loads(p.read_text(encoding='utf8'))
def rows(p):return [json.loads(s) for s in p.read_text(encoding='utf8').splitlines() if s]
def save(p,x):p.write_text(json.dumps(x,ensure_ascii=False,indent=2)+'\n',encoding='utf8',newline='\n')
def lines(p,x):p.write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in x),encoding='utf8',newline='\n')
log=[]
if read(P/'dev.manifest.json')['version'] >= 3:
    raise SystemExit('Query revision already applied; preserve the original change record.')
for split in ('dev','calibration'):
    for suffix in ('cases.jsonl','queries.jsonl','manifest.json'):
        src=P/f'{split}.{suffix}'
        if not (backup/src.name).exists():shutil.copyfile(src,backup/src.name)
    cases=rows(P/f'{split}.cases.jsonl')
    for c in cases:
        old=c['query'];c['query']=old.removeprefix('仅依据冻结合成资料作答。')
        if old!=c['query']:log.append({'caseId':c['caseId'],'removedPrefix':'仅依据冻结合成资料作答。'})
    lines(P/f'{split}.cases.jsonl',cases)
    byid={c['caseId']:c for c in cases};qs=rows(P/f'{split}.queries.jsonl')
    for q in qs:q['query']=byid[q['caseId']]['query']
    lines(P/f'{split}.queries.jsonl',qs)
    m=read(P/f'{split}.manifest.json');m['version']=3;m['annotationGuidelineVersion']='rag400-public-review-v3'
    m['caseFileSha256']=hashlib.sha256((P/f'{split}.cases.jsonl').read_bytes()).hexdigest()
    save(P/f'{split}.manifest.json',m)
save(P/'query-input-review-v3.json',{'changes':log,'holdout':'NOT_READ_NOT_MODIFIED',
    'reason':'Remove benchmark-only instruction from search text; fixture boundary retained in factsGiven. Real adapter uses first 16 lexical terms.',
    'retrieverChanged':False,'corpusChanged':False,'firstRunRetained':True})
print(json.dumps({'correctedQueries':len(log),'holdout':'NOT_READ_NOT_MODIFIED'}))
