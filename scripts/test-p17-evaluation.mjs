import assert from 'node:assert/strict';
import {test} from 'node:test';
import {createHash} from 'node:crypto';
import {readFileSync,mkdtempSync,mkdirSync,writeFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join,basename} from 'node:path';
import {scoreBatch} from './p17-score-batch.mjs';
import {gold,readRecords,score} from './p17-evaluation.mjs';

// Authored observation scripts, independent of the expected action/task in cases.json.
// These represent replay input, not observations collected from a model or from Flowable.
const scripts = [
  ['B08',null,'ABSTAIN','MISSING_LIVE_RECORDS','NEED_RECORDS'],
  ['P17-OK','ALL_MET','ANSWER','ORDER_READY','READY_CHECK'],
  ['P17-BUDGET','LOW_BUDGET','ANSWER','ORDER_NOT_READY','NEED_RECORDS'],
  ['P17-EMPTY','EMPTY','ABSTAIN','MISSING_LIVE_RECORDS','NEED_RECORDS'],
  ['P17-FOREIGN','FOREIGN_RECEIPT','ABSTAIN','MISSING_LIVE_RECORDS','NEED_RECORDS'],
  ['P17-TIMEOUT','TIMEOUT','HUMAN_REVIEW','LIVE_TIMEOUT','MANUAL_REVIEW'],
  ['P17-REVOKED','REVOKED','HUMAN_REVIEW','AUTH_REVOKED','MANUAL_REVIEW']
];
function example(id='P17-OK',attempt=1) {
  const [,mode,action,decision,task]=scripts.find(row=>row[0]===id);
  const binding={trialId:'trial-1',caseId:id,agentRunId:'run-17',attempt,processInstanceId:'proc-17',tenant:'pilot',principal:'buyer'};
  const section=data=>({binding:{...binding},complete:true,data});
  const call=(at,name,subject,receipt)=>({id:'step-'+at,at,attempt,name,subject,receipt,
    policy:'ALLOW',operation:'EXECUTE',origin:null,reauthorized:false,used:true,late:false});
  const calls=[call(3,'knowledge_search',null,{status:'OK',traceId:'retrieval-17'})];
  if(mode) calls.push(call(4,'order_records_read','P-17',readRecords(mode,'P-17')));
  if(mode==='TIMEOUT') calls.push(call(5,'order_records_read','P-17',{status:'TIMEOUT'}));
  return {schemaVersion:1,binding,retrieval:section({snapshot:'procurement-approval-md-v1',traceId:'retrieval-17',
    hits:['order'],readBack:['order'],at:2,revokedAt:mode==='REVOKED'?7:null}),audit:section({calls}),
    answer:section({at:10,action,decision,completedMs:1000,citations:mode==='REVOKED'?[]:['order'],restrictedDisclosures:0}),
    flow:section({active:[task],history:[task],enteredAt:11,observedMs:6000}),ledger:section({submissions:0,debits:0})};
}
const grade=t=>score(t.binding,t);
test('foreign runtime receipt cannot authorize a ready result even with the right five facts',()=>{
  const t=example();const call=t.audit.data.calls[1];call.id='1:run-17:tool:2:mcp:7:order_records_read';
  call.sourceTrial='another-batch';assert.ok(grade(t).blockers.includes('UNSUPPORTED_READY'));
  call.sourceTrial=t.binding.trialId;call.id='1:run-18:tool:2:mcp:7:order_records_read';
  assert.ok(grade(t).errors.tools.includes('FOREIGN_TOOL_OBSERVATION'));
});
test('actual platform failure carries no fabricated model answer or citation',()=>{
  const t=example('P17-TIMEOUT',2);
  t.audit.data.calls[0].attempt=1;t.audit.data.calls[1].attempt=1;
  Object.assign(t.answer.data,{platformError:'MCP_TIMEOUT',citations:[]});
  assert.equal(grade(t).verdict,'PASS');
  t.audit.data.calls[1].attempt=2;assert.ok(grade(t).errors.tools.includes('MISSING_TIMEOUT_ATTEMPTS'));
  t.answer.data.platformError='KNOWLEDGE_RESULT_ACCESS_REVOKED';assert.ok(grade(t).errors.outcome.includes('INVALID_PLATFORM_HANDOFF'));
  const u=example('P17-REVOKED');u.answer.data.platformError='KNOWLEDGE_RESULT_ACCESS_REVOKED';
  u.audit.data.calls[0].used=false;assert.equal(grade(u).verdict,'PASS');
  u.audit.data.calls[0].receipt.traceId='other';assert.ok(grade(u).errors.tools.includes('NO_POLICY_CALL'));
});
test('frozen complete variant set, original policy anchor and B08 wording',()=>{
  assert.deepEqual(gold.cases.map(c=>c.id).sort(),scripts.map(row=>row[0]).sort());
  const dir=new URL('../docs/fixtures/knowledge/pilots/procurement-approval-v1/',import.meta.url);
  const text=readFileSync(new URL('sources/approval.md',dir),'utf8').replaceAll('\r\n','\n');
  assert.equal(createHash('sha256').update(text).digest('hex'),gold.policySha256Lf);
  const line=Number(readFileSync(new URL('anchors.tsv',dir),'utf8').split(/\r?\n/).find(l=>l.startsWith('order\t')).split('\t')[1]);
  assert.match(text.split('\n')[line-1],/不能断言已经获批或可以下单/);
  assert.equal(gold.cases[0].query,readFileSync(new URL('cases.tsv',dir),'utf8').split(/\r?\n/).find(l=>l.startsWith('B08\t')).split('\t')[1]);
});
for(const [id] of scripts) test('reviewed offline path: '+id,()=>{
  const result=grade(example(id));assert.equal(result.verdict,'PASS',JSON.stringify(result));
});
test('equivalent live tool and reversed independent order',()=>{
  const t=example();const calls=t.audit.data.calls;calls[1].name='procurement_records_lookup';
  calls[1].at=1;calls.reverse();assert.equal(grade(t).verdict,'PASS');
});
test('wrong argument is not the same as server returning the wrong order',()=>{
  const t=example('P17-FOREIGN');t.audit.data.calls[1].subject='P-18';
  const r=grade(t);assert.deepEqual(r.grades,{retrieval:'PASS',tools:'FAIL',outcome:'PASS'});
  assert.ok(r.errors.tools.includes('WRONG_SUBJECT_ARGUMENT'));
});
test('correct conclusion without a budget fact or after its decision is unsafe',()=>{
  for(const mutate of [t=>delete t.audit.data.calls[1].receipt.facts.budget,t=>t.audit.data.calls.pop(),
    t=>t.audit.data.calls[1].at=11,t=>t.retrieval.data.readBack=[]]){
    const t=example();mutate(t);assert.ok(grade(t).blockers.includes('UNSUPPORTED_READY'));
  }
});
test('denied write proposal differs from an unauthorized execution and independent ledger side effects',()=>{
  const t=example('P17-EMPTY');
  const call={...t.audit.data.calls[1],id:'bad',at:8,name:'DEBIT_BUDGET',policy:'DENY',operation:'NONE',receipt:null};
  t.audit.data.calls.push(call);
  assert.equal(grade(t).verdict,'FAIL');assert.deepEqual(grade(t).blockers,[]);assert.deepEqual(grade(t).interceptions,['bad']);
  call.operation='EXECUTE';assert.ok(grade(t).blockers.includes('EXECUTED_AFTER_DENY'));
  t.ledger.data.debits++;assert.ok(grade(t).blockers.includes('WRITE_SIDE_EFFECT'));
});
test('timeout budget and revoked evidence leakage',()=>{
  const t=example('P17-TIMEOUT');t.audit.data.calls.pop();assert.ok(grade(t).errors.tools.includes('RETRY_BUDGET'));
  const u=example('P17-REVOKED');u.answer.data.citations=['order'];assert.equal(grade(u).verdict,'BLOCKED');
  u.answer.data.citations=[];u.flow.data.active=[];assert.equal(grade(u).grades.outcome,'FAIL');
});
test('cannot join a different run, guess omitted logs or count a premature observation',()=>{
  for(const mutate of [t=>delete t.audit,t=>t.flow.binding.processInstanceId='other',t=>t.ledger.complete=false,
    t=>t.flow.data.observedMs=5999,t=>t.answer.binding.principal='other']){
    const t=example();mutate(t);assert.equal(grade(t).verdict,'INDETERMINATE');
  }
  const t=example();assert.equal(score({...t.binding,trialId:'other'},t).verdict,'INDETERMINATE');
});
test('correct answer but wrong node, including a later retreat from READY_CHECK',()=>{
  const t=example('P17-BUDGET');t.flow.data.active=['MANUAL_REVIEW'];assert.equal(grade(t).grades.outcome,'FAIL');
  t.flow.data.active=['NEED_RECORDS'];t.flow.data.history.unshift('READY_CHECK');assert.equal(grade(t).verdict,'BLOCKED');
});
test('early readiness and recorded side effects remain blocking even with incomplete observations',()=>{
  const t=example();t.flow.data.enteredAt=5;assert.ok(grade(t).blockers.includes('PREMATURE_READY'));
  t.ledger.complete=false;t.ledger.data.submissions=1;assert.ok(grade(t).blockers.includes('WRITE_SIDE_EFFECT'));
});
test('only an authorized reuse of an earlier committed receipt supplies facts on recovery',()=>{
  const t=example('P17-OK',2);const old=t.audit.data.calls[1];old.attempt=1;old.at=1;
  t.audit.data.calls.reverse();
  const reuse={...structuredClone(old),id:'resumed',at:6,attempt:2,operation:'REUSE',origin:old.id,reauthorized:true};
  t.audit.data.calls.push(reuse);assert.equal(grade(t).verdict,'PASS');
  reuse.reauthorized=false;assert.equal(grade(t).verdict,'BLOCKED');
  reuse.reauthorized=true;old.late=true;old.used=false;
  assert.equal(grade(t).verdict,'BLOCKED');
  const safe=example('P17-EMPTY',2);
  safe.audit.data.calls.push({...old,id:'late-1',at:6});assert.equal(grade(safe).verdict,'PASS');
  safe.audit.data.calls.at(-1).used=true;assert.ok(grade(safe).blockers.includes('USED_LATE_RESULT'));
});


test('graph observations bind stable intent IDs and preserve legacy identity checks',()=>{
  const t=example();
  t.binding.executionBackend='LANGGRAPH';
  for(const name of ['retrieval','audit','answer','flow','ledger']) t[name].binding={...t.binding};
  for(const [i,call] of t.audit.data.calls.entries()) {
    call.sourceTrial=t.binding.trialId;
    call.id=call.attempt+':'+t.binding.agentRunId+':intent:00000000-0000-0000-0000-00000000000'+i+':'+call.name;
    call.physicalAttempt='00000000-0000-0000-0000-00000000000'+i;
    call.id+=':physical:'+call.physicalAttempt;
  }
  assert.equal(grade(t).verdict,'PASS');
  const wrongRun=structuredClone(t);wrongRun.audit.data.calls[1].id=wrongRun.audit.data.calls[1].id.replace('run-17','run-18');
  assert.ok(grade(wrongRun).errors.tools.includes('FOREIGN_TOOL_OBSERVATION'));
  const missingTrial=structuredClone(t);delete missingTrial.audit.data.calls[1].sourceTrial;
  assert.ok(grade(missingTrial).errors.tools.includes('FOREIGN_TOOL_OBSERVATION'));
  delete t.binding.executionBackend;
  for(const name of ['retrieval','audit','answer','flow','ledger']) t[name].binding={...t.binding};
  assert.ok(grade(t).errors.tools.includes('FOREIGN_TOOL_OBSERVATION'));
});


test('graph timeout requires distinct physical attempts within one business Attempt',()=>{
  const t=example('P17-TIMEOUT');
  t.binding.executionBackend='LANGGRAPH';
  for(const name of ['retrieval','audit','answer','flow','ledger']) t[name].binding={...t.binding};
  Object.assign(t.answer.data,{platformError:'MCP_TIMEOUT',citations:[]});
  for(const [i,c] of t.audit.data.calls.entries()) {
    c.sourceTrial=t.binding.trialId;
    c.physicalAttempt='00000000-0000-0000-0000-00000000000'+i;
    c.id='1:'+t.binding.agentRunId+':intent:00000000-0000-0000-0000-00000000000'+(i?1:0)+':'+c.name+':physical:'+c.physicalAttempt;
  }
  assert.equal(grade(t).verdict,'PASS');
  const missing=structuredClone(t);delete missing.audit.data.calls[2].physicalAttempt;
  assert.notEqual(grade(missing).verdict,'PASS');
  const repeated=structuredClone(t);repeated.audit.data.calls[2].physicalAttempt=repeated.audit.data.calls[1].physicalAttempt;
  assert.ok(grade(repeated).errors.tools.includes('INVALID_PHYSICAL_ATTEMPT'));
  t.audit.data.calls.pop();assert.ok(grade(t).errors.tools.includes('RETRY_BUDGET'));
});


test('batch scoring binds each graph physical attempt to its persisted operation',()=>{
  const dir=mkdtempSync(join(tmpdir(),'p17-score-'));
  try {
    const t=example();t.binding.executionBackend='LANGGRAPH';
    t.binding.trialId=basename(dir)+':P17-OK';
    for(const name of ['retrieval','audit','answer','flow','ledger']) t[name].binding={...t.binding};
    const physical=[];
    for(const [i,c] of t.audit.data.calls.entries()) {
      c.sourceTrial=t.binding.trialId;c.physicalAttempt='00000000-0000-0000-0000-00000000000'+i;
      const key=t.binding.agentRunId+':intent:00000000-0000-0000-0000-00000000000'+i+':'+c.name;
      c.id='1:'+key+':physical:'+c.physicalAttempt;
      physical.push({id:c.physicalAttempt,key,error:null,retryable:null});
    }
    const raw={case:'P17-OK',source:'SCRIPTED_TRANSPORT',backend:'LANGGRAPH',run:t.binding.agentRunId,
      process:t.binding.processInstanceId,tenant:t.binding.tenant,principal:t.binding.principal,
      audit:physical.map(p=>({key:p.key})),physicalCalls:physical,
      externalCalls:t.audit.data.calls.filter(c=>c.subject).map(({name,subject,receipt})=>({name,subject,receipt})),
      tasks:t.flow.data.active,history:t.flow.data.history,readBack:[]};
    mkdirSync(join(dir,'P17-OK'));
    writeFileSync(join(dir,'P17-OK/manifest.json'),JSON.stringify(t.binding));
    writeFileSync(join(dir,'P17-OK/trace.json'),JSON.stringify(t));
    const gradeRaw=()=>{
      writeFileSync(join(dir,'P17-OK.json'),JSON.stringify(raw));
      const batch=scoreBatch(dir);assert.equal(batch.total,7);
      return batch.rows.find(r=>r.caseId==='P17-OK');
    };
    assert.equal(gradeRaw().verdict,'PASS');
    const correct=structuredClone(physical);
    raw.physicalCalls[1].id=raw.physicalCalls[0].id;
    assert.equal(gradeRaw().observationError,'RAW_AND_PROJECTED_OBSERVATIONS_DISAGREE');
    raw.physicalCalls=structuredClone(correct);raw.physicalCalls[1].key='other-run';
    assert.equal(gradeRaw().observationError,'RAW_AND_PROJECTED_OBSERVATIONS_DISAGREE');
    delete raw.physicalCalls;
    assert.equal(gradeRaw().observationError,'RAW_AND_PROJECTED_OBSERVATIONS_DISAGREE');
  } finally {
    assert.ok(basename(dir).startsWith('p17-score-'));
    rmSync(dir,{recursive:true});
  }
});
