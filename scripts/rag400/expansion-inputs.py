"""Query-only auxiliary topics. Never reads gold, corpus, retrieval output, or case metadata."""
import hashlib
import json
from pathlib import Path
import time
import httpx

PROMPT = ('只从用户问题提取一个简短的检索主题短语，可用自然同义表达。不得回答问题、添加事实或推断规则。'
          '不含数字、日期、金额、否定、条件、比较、必须/可以等判定词；原问题会完整保留在主检索通道。'
          '只输出 JSON 对象 {"auxiliaryQuery":"主题短语"}。')


def prepare(queries_path, output, config=None, fixture=False):
    queries = [json.loads(x) for x in queries_path.read_text(encoding='utf-8').splitlines() if x.strip()]
    cfg = json.loads(config.read_text(encoding='utf-8')) if config else {}
    if not fixture and (not cfg.get('apiKey') or not cfg.get('model') or not cfg.get('baseUrl','').startswith('https://')):
        raise ValueError('Expansion requires HTTPS model configuration')
    identity = {'queriesSha256': hashlib.sha256(queries_path.read_bytes()).hexdigest(), 'prompt': PROMPT,
                'model': cfg.get('model','FIXTURE-NOT-QUALITY'), 'baseUrl': cfg.get('baseUrl',''),
                'fixtureOnly': fixture, 'maxTokens': 4096}
    output.mkdir(parents=True, exist_ok=True)
    manifest = output / 'manifest.json'
    if manifest.exists() and json.loads(manifest.read_text(encoding='utf-8')) != identity:
        raise ValueError('Expansion inputs changed; new batch required')
    manifest.write_text(json.dumps(identity,ensure_ascii=False,indent=2),encoding='utf-8')
    target = output / 'queries.jsonl'
    existing = [json.loads(x) for x in target.read_text(encoding='utf-8').splitlines()] if target.exists() else []
    done = {x['caseId'] for x in existing}
    if len(done)!=len(existing) or not done <= {q['caseId'] for q in queries}:
        raise ValueError('Invalid expansion continuation')
    stopped = any(x.get('httpStatus') in (401,402,403,429) for x in existing)
    with target.open('a',encoding='utf-8',newline='\n') as out:
        for q in queries:
            if q['caseId'] in done:
                continue
            start=time.monotonic()
            record={'caseId':q['caseId'], 'querySha256':hashlib.sha256(q['query'].encode()).hexdigest()}
            if fixture:
                record.update(status='SUCCESS', auxiliaryQuery='业务办理规则')
            elif stopped:
                record.update(status='NOT_RUN', reason='provider_blocked')
            else:
                try:
                    with httpx.Client(timeout=90,follow_redirects=False) as client:
                        response=client.post(cfg['baseUrl'].rstrip('/')+'/chat/completions',
                            headers={'Authorization':'Bearer '+cfg['apiKey']}, json={'model':cfg['model'],
                            'max_tokens':4096,'response_format':{'type':'json_object'},
                            'messages':[{'role':'system','content':PROMPT},{'role':'user','content':q['query']}]})
                    record['httpStatus']=response.status_code
                    stopped=response.status_code in (401,402,403,429)
                    if response.is_success:
                        body=response.json();choice=body['choices'][0]
                        record.update(usage=body.get('usage',{}),finishReason=choice.get('finish_reason'))
                        if choice.get('finish_reason')!='stop':
                            record['status']='INCOMPLETE'
                        else:
                            value=json.loads(choice['message']['content'])
                            if set(value)!={'auxiliaryQuery'} or not isinstance(value['auxiliaryQuery'],str) or not 1<=len(value['auxiliaryQuery'].strip())<=100:
                                raise ValueError('Invalid auxiliary output')
                            record.update(status='SUCCESS',auxiliaryQuery=value['auxiliaryQuery'].strip())
                    else: record['status']='HTTP_ERROR'
                except (httpx.HTTPError,ValueError,KeyError,TypeError,IndexError) as exc:
                    record.update(status='ERROR',errorType=type(exc).__name__)
            record['latencySeconds']=round(time.monotonic()-start,3)
            line=json.dumps(record,ensure_ascii=False)
            if cfg.get('apiKey'):line=line.replace(cfg['apiKey'],'[REDACTED]')
            out.write(line+'\n');out.flush()
    return target
