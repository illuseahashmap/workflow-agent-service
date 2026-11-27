import importlib.util
from pathlib import Path
import unittest

path = Path(__file__).with_name('score-retrieval.py')
spec = importlib.util.spec_from_file_location('score_retrieval', path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ScoringTest(unittest.TestCase):
    def setUp(self):
        self.anchor = {'evidenceId': 'a', 'documentCode': 'doc', 'documentVersion': '1',
                       'contentHash': 'x', 'locator': {'sectionId': 's'}}
        self.case = {'caseId': 'c', 'relevanceJudgments': [self.anchor], 'requiredEvidenceGroups': [['a']]}
        self.row = {'caseId': 'c', 'snapshotSha256': 'snapshot', 'status': 'SUCCESS', 'evidence': [self.anchor]}

    def test_missing_is_not_run_and_stays_in_denominator(self):
        r = module.score([self.case], [], 'snapshot')
        self.assertEqual(r['statusCounts'], {'NOT_RUN': 1})
        self.assertEqual(r['denominatorIncludesMissingAndErrors'], 1)
        self.assertEqual(r['completeRequiredGroupsAtK']['5'], 0)

    def test_exact_anchor_matches(self):
        self.assertEqual(module.score([self.case], [self.row], 'snapshot')['completeRequiredGroupsAtK']['5'], 1)

    def test_wrong_version_does_not_match(self):
        self.row['evidence'] = [{**self.anchor, 'documentVersion': '2'}]
        self.assertEqual(module.score([self.case], [self.row], 'snapshot')['completeRequiredGroupsAtK']['5'], 0)

    def test_normalization_preserves_original_chunk_rank(self):
        self.row['evidence'] = [{**self.anchor, 'rank': 8}]
        result=module.score([self.case], [self.row], 'snapshot')['completeRequiredGroupsAtK']
        self.assertEqual(result['5'],0)
        self.assertEqual(result['10'],1)

    def test_invalid_rank_is_rejected(self):
        self.row['evidence'] = [{**self.anchor, 'rank': 0}]
        with self.assertRaises(ValueError): module.score([self.case],[self.row],'snapshot')

    def test_wrong_snapshot_rejected(self):
        with self.assertRaises(ValueError): module.score([self.case], [self.row], 'different')

    def test_duplicate_run_observation_rejected(self):
        with self.assertRaises(ValueError): module.score([self.case], [self.row, self.row], 'snapshot')

    def test_leak_cannot_be_success_even_with_complete_gold(self):
        self.case['forbiddenEvidence'] = [self.anchor]
        r = module.score([self.case], [self.row], 'snapshot')
        self.assertEqual(r['statusCounts'], {'FORBIDDEN_EVIDENCE': 1})
        self.assertEqual(r['completeRequiredGroupsAtK']['5'], 0)


if __name__ == '__main__': unittest.main()
