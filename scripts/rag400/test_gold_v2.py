import importlib.util
import json
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('live',Path(__file__).with_name('live-answers.py'))
live=importlib.util.module_from_spec(spec);spec.loader.exec_module(live)


class ReviewedGold(unittest.TestCase):
    def test_frozen_review_and_scope_alignment(self):
        root=Path(__file__).resolve().parents[2]/'docs/fixtures/knowledge'
        p=root/'rag400-v2'; old=root/'rag400-v1'
        review=json.loads((p/'gold-review.json').read_text(encoding='utf-8'))
        self.assertFalse(review['independentReview'])
        self.assertEqual(live.digest(p/'knowledge-snapshot.json'),live.digest(old/'knowledge-snapshot.json'))
        grants=json.loads((p/'auth-fixture.json').read_text(encoding='utf-8'))['grantedScopes']
        all_cases={}
        for split in ('dev','calibration','holdout-candidate'):
            data=p/'holdout' if split=='holdout-candidate' else p
            source=old/'holdout' if split=='holdout-candidate' else old
            self.assertEqual(review['caseHashes'][split],live.digest(data/f'{split}.cases.jsonl'))
            self.assertEqual(review['sourceCaseHashes'][split],live.digest(source/f'{split}.cases.jsonl'))
            for c in live.rows(data/f'{split}.cases.jsonl'):
                all_cases[c['caseId']]=c
                self.assertEqual(c['allowedKnowledgeScopes'],grants)
                self.assertFalse(c['query'].startswith('仅依据冻结合成资料作答'))
                self.assertFalse(c['query'].startswith('关于'))
                self.assertNotIn('题面约定',c['query'])
                self.assertNotIn('请解释分类到对应细则',c['query'])
        self.assertEqual(len(all_cases),400)
        self.assertEqual(len(review['overrides']),29)
        for cid,rule in review['overrides'].items():
            self.assertEqual(all_cases[cid]['scenarioType'],'INSUFFICIENT')
            self.assertEqual(live.reviewed_actions(all_cases[cid],review),['ABSTAIN','ANSWER'])
            self.assertTrue(rule['requiredMeaning'])
        # Missing exact numbers must still abstain; widening must never spread to all insufficient cases.
        self.assertEqual(live.reviewed_actions(all_cases['R400-FIN-e21b247981-1'],review),['ABSTAIN'])
        self.assertEqual(live.reviewed_actions(all_cases['R400-HR-8626a60af7-2'],review),['ABSTAIN'])
        self.assertNotIn('换车',all_cases['R400-ADM-e59e2543f7-1']['expectedClaims'][0]['displayText'])
        self.assertNotIn('释放',all_cases['R400-ADM-eba06c67c3-2']['expectedClaims'][0]['displayText'])
        self.assertEqual(len(review['remoteReview']['narrowedRequiredClaims']),17)
        self.assertEqual(len(all_cases['R400-OPS-5ff50a6004-1']['expectedClaims']),3)
        self.assertIn('样品冷藏柜',all_cases['R400-ADM-d1d3cd0ba4-2']['query'])


if __name__=='__main__': unittest.main()
