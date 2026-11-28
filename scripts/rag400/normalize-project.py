"""Map real native MD line ranges to section anchors without compressing retrieval ranks.

A heading alone never counts. Full section body coverage is a deliberately conservative
primary metric; any body overlap is separately reported, never called answer correctness.
Uses selected split cases only for scoring AFTER the project retrieval run.
"""
import argparse,hashlib,json,collections,math,importlib.util
from pathlib import Path

def read(p):return json.loads(p.read_text(encoding='utf-8'))
def rows(p):return [json.loads(s) for s in p.read_text(encoding='utf-8-sig').splitlines() if s]
def save(p,x):p.write_text(json.dumps(x,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
def normalize(raw, documents, bodies, snapshot_hash):
    observations=[];partial=[]
    for r in raw:
        if r['snapshotSha256']!=snapshot_hash:raise ValueError('wrong snapshot')
        covered=collections.defaultdict(dict)
        for h in r['hits']:
            doc=documents[h['documentCode']]
            if h['databaseVersion']!=1:raise ValueError('unexpected native version')
            if h['scope']!=doc['scope']:raise ValueError('source scope mismatch')
            # Verify returned text against actual original range; headings alone do not count.
            original=doc['_lines']
            expected='\n'.join(original[h['startLine']-1:h['endLine']])
            if expected.strip()!=h['text'].strip():raise ValueError('native excerpt mismatch')
            for sid,needed in bodies[h['documentCode']].items():
                for n in needed.intersection(range(h['startLine'],h['endLine']+1)):
                    covered[(h['documentCode'],sid)].setdefault(n,h['rank'])
        full=[];anybody=[]
        for (code,sid),line_ranks in covered.items():
            d=documents[code]
            anchor={'documentCode':code,'documentVersion':d['documentVersion'],'contentHash':d['sha256'],'sectionId':sid}
            anybody.append({**anchor,'rank':min(line_ranks.values())})
            if set(line_ranks)==bodies[code][sid]:full.append({**anchor,'rank':max(line_ranks.values())})
        envelope={k:r[k] for k in ('caseId','status','snapshotSha256')}
        observations.append({**envelope,'evidence':sorted(full,key=lambda e:e['rank'])})
        partial.append({**envelope,'evidence':sorted(anybody,key=lambda e:e['rank'])})
    return observations,partial

def main():
    p=argparse.ArgumentParser();p.add_argument('--public',type=Path,required=True);p.add_argument('--run',type=Path,required=True)
    p.add_argument('--split',choices=['dev','calibration','holdout-candidate'],default='dev')
    a=p.parse_args();dataset_dir=a.public/'holdout' if a.split=='holdout-candidate' else a.public;snap=a.public/'knowledge-snapshot.json';digest=hashlib.sha256(snap.read_bytes()).hexdigest()
    documents={d['documentCode']:d for d in read(snap)['documents']}; bodies={}
    for code,d in documents.items():
        data=(a.public/d['path']).read_bytes()
        if hashlib.sha256(data).hexdigest()!=d['sha256']:raise ValueError('source changed')
        d['_lines']=data.decode('utf-8').splitlines();sections={};current=None
        for n,line in enumerate(d['_lines'],1):
            if line.startswith('## '):current=line.split()[1];sections[current]=set()
            elif current and line.strip():sections[current].add(n)
        bodies[code]=sections
    raw=rows(a.run/'project-raw.jsonl');cases=rows(dataset_dir/f'{a.split}.cases.jsonl')
    full,partial=normalize(raw,documents,bodies,digest)
    spec=importlib.util.spec_from_file_location('scoring',Path(__file__).with_name('score-retrieval.py'))
    scoring=importlib.util.module_from_spec(spec);spec.loader.exec_module(scoring)
    for filename,obs in [('observations',full),('body-overlap-observations',partial)]:
        (a.run/f'{filename}.jsonl').write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in obs),encoding='utf-8',newline='\n')
    result=scoring.score(cases,full,digest); overlap=scoring.score(cases,partial,digest)
    save(a.run/'score.json',result)
    byid={c['caseId']:c for c in cases}; facets={}
    for key,getter in [('difficulty',lambda c:c['difficulty']['level']),('scenario',lambda c:c['scenarioType'])]:
        facets[key]={}
        for r in result['results']:
            value=getter(byid[r['caseId']]); counter=facets[key].setdefault(value,{'count':0,'completeAt10':0})
            counter['count']+=1;counter['completeAt10']+=int(r.get('requiredGroupsComplete',{}).get('10',False))
    allowed=set(read(a.public/'auth-fixture.json')['grantedScopes'])
    leaks=[r['caseId'] for r in raw if any(h['scope'] not in allowed for h in r['hits'])]
    times=sorted(r['latencyMs'] for r in raw)
    summary={k:v for k,v in result.items() if k!='results'}
    summary.update({'metric':'all nonblank section body lines covered within original chunk TopK',
        'anyBodyOverlapAtK_upperBoundNotCompleteEvidence':overlap['completeRequiredGroupsAtK'],
        'unauthorizedScopeHits':leaks,'retrievalStatuses':dict(collections.Counter(r['retrievalStatus'] for r in raw)),
        'latencyMs':{'p50':times[math.ceil(len(times)*.50)-1],'p95':times[math.ceil(len(times)*.95)-1]},
        'latencyProtocol':'single pass, sequential, includes first query cold effects; no production SLA claim',
        'facets':facets,'split':a.split,'formerHoldoutRead':a.split=='holdout-candidate','answerGeneration':'NOT_RUN',
        'profile':'project native Markdown ingestion + KeywordRetriever + PostgresKeywordChunkSearchAdapter, K=10; no vector/LLM',
        'permissionBoundary':'actual SQL scope filtering, fixed fixture grants; HTTP/principal authorization not exercised'})
    save(a.run/'summary.json',summary);print(json.dumps(summary,ensure_ascii=False))

if __name__=='__main__':main()
