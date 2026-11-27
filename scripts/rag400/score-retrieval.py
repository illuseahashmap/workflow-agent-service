"""Score normalized source-anchor observations; never infer success from missing logs.

Input JSONL rows: caseId, status (SUCCESS/ERROR/NOT_RUN), snapshotSha256,
evidence [{documentCode,documentVersion,contentHash,sectionId}]. Evidence order is rank.
An adapter must map actual retrieval trace references to frozen source anchors.
This evaluator does not execute RAG and does not certify server-side authorization.
"""
import argparse
import collections
import hashlib
import json
from pathlib import Path


def read(path):
    return [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines() if line.strip()]


def score(cases, observations, snapshot_hash):
    by_id = {}
    case_ids = {c['caseId'] for c in cases}
    for row in observations:
        if row['caseId'] not in case_ids or row['caseId'] in by_id:
            raise ValueError('Unexpected or duplicate observation')
        if row['status'] not in ('SUCCESS', 'ERROR', 'NOT_RUN'):
            raise ValueError('Unknown execution status')
        if row.get('snapshotSha256') != snapshot_hash:
            raise ValueError('Observation snapshot is not the frozen snapshot')
        by_id[row['caseId']] = row
    results = []
    for c in cases:
        row = by_id.get(c['caseId'])
        if row is None or row['status'] != 'SUCCESS':
            results.append({'caseId': c['caseId'], 'status': row['status'] if row else 'NOT_RUN'})
            continue
        actual = row.get('evidence')
        if not isinstance(actual, list): raise ValueError('Successful trace lacks evidence list')
        def key(e):
            return (e['documentCode'], e['documentVersion'], e['contentHash'], e.get('sectionId') or e['locator']['sectionId'])
        forbidden = {key(e) for e in c.get('forbiddenEvidence', [])}
        anchors = {e['evidenceId']: key(e) for e in c['relevanceJudgments']}
        ranked = [(key(e), e.get('rank', i+1)) for i,e in enumerate(actual)]
        if any(type(rank) is not int or rank < 1 for _,rank in ranked):
            raise ValueError('Evidence rank must be a positive original chunk rank')
        returned = [a for a,_ in ranked]
        leak = any(e in forbidden for e in returned)
        results.append({'caseId': c['caseId'], 'status': 'FORBIDDEN_EVIDENCE' if leak else 'OBSERVED',
                        'requiredGroupsComplete': {str(k): all(any(anchors[e] in {a for a,rank in ranked if rank<=k} for e in group)
                                for group in c['requiredEvidenceGroups']) for k in (3, 5, 10)}})
    complete = {str(k): sum(r.get('requiredGroupsComplete', {}).get(str(k), False) and r['status'] != 'FORBIDDEN_EVIDENCE'
                              for r in results) for k in (3, 5, 10)}
    return {'cases': len(cases), 'statusCounts': dict(collections.Counter(r['status'] for r in results)),
            'completeRequiredGroupsAtK': complete, 'denominatorIncludesMissingAndErrors': len(cases),
            'answerQuality': 'NOT_SCORED', 'authorizationAcceptance': 'REQUIRES_TRUSTED_BACKEND_TRACE', 'results': results}


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--cases', type=Path, required=True)
    p.add_argument('--snapshot', type=Path, required=True)
    p.add_argument('--observations', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    result = score(read(a.cases), read(a.observations), hashlib.sha256(a.snapshot.read_bytes()).hexdigest())
    a.output.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')
    print(json.dumps({k: v for k, v in result.items() if k != 'results'}, ensure_ascii=False))
