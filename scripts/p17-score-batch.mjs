import {readFileSync} from 'node:fs';
import {resolve,join,basename} from 'node:path';
import {pathToFileURL} from 'node:url';
import {isDeepStrictEqual as equal} from 'node:util';
import {gold,score} from './p17-evaluation.mjs';

// Take an explicit batch, never "latest": parallel trials can share numeric DB ids.
export function scoreBatch(dir) {
  const batch=basename(resolve(dir)), read=p=>JSON.parse(readFileSync(join(dir,p),'utf8'));
  const rows=[], seenRuns=new Set(), seenProcesses=new Set();
  for(const {id} of gold.cases){
    try {
      const m=read(id+'/manifest.json'),t=read(id+'/trace.json'),raw=read(id+'.json');
      if(m.trialId!==batch+':'+id || m.caseId!==id || raw.case!==id || raw.source!=='SCRIPTED_TRANSPORT' ||
         String(raw.run)!==m.agentRunId || raw.process!==m.processInstanceId || raw.tenant!==m.tenant ||
         raw.principal!==m.principal || seenRuns.has(m.agentRunId) || seenProcesses.has(m.processInstanceId))
        throw Error('RUN_IDENTITY_MISMATCH');
      seenRuns.add(m.agentRunId);seenProcesses.add(m.processInstanceId);
      const result=score(m,t);
      const calls=t.audit?.data?.calls??[];
      const keys=new Set(raw.audit?.map(a=>a.key));
      const projected=new Set(calls.map(c=>c.id.slice(c.id.indexOf(':')+1)));
      const live=calls.filter(c=>gold.capabilities.records.includes(c.name))
        .map(({name,subject,receipt})=>({name,subject,receipt}));
      const checks={
        tools:equal(keys,projected) && calls.every(c=>c.sourceTrial===m.trialId) && equal(live,raw.externalCalls),
        outcome:equal(t.flow?.data?.active,raw.tasks) && equal(t.flow?.data?.history,raw.history),
        retrieval:(raw.readBack??[]).every(b=>b.trace===t.retrieval?.data?.traceId && b.sha256===gold.policySha256Lf)
      };
      for(const [axis,ok] of Object.entries(checks))if(!ok){
        if(result.grades[axis]==='PASS')result.grades[axis]='INDETERMINATE';
        result.observationError='RAW_AND_PROJECTED_OBSERVATIONS_DISAGREE';
        if(result.verdict==='PASS')result.verdict='INDETERMINATE';
      }
      rows.push({caseId:id,run:m.agentRunId,process:m.processInstanceId,...result});
    }catch(error){
      rows.push({caseId:id,verdict:'INDETERMINATE',reason:error.code??error.message});
    }
  }
  return {batch,source:'SCRIPTED_TRANSPORT',total:rows.length,passed:rows.filter(r=>r.verdict==='PASS').length,
    axes:Object.fromEntries(['retrieval','tools','outcome'].map(k=>[k,rows.filter(r=>r.grades?.[k]==='PASS').length])),rows};
}
if(process.argv[1] && import.meta.url===pathToFileURL(process.argv[1]).href){
  if(!process.argv[2])throw Error('Usage: node scripts/p17-score-batch.mjs <explicit-batch-directory>');
  const result=scoreBatch(process.argv[2]);console.log(JSON.stringify(result,null,2));
  process.exitCode=result.passed===result.total?0:1;
}
