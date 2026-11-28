import importlib.util
import json
from pathlib import Path
import unittest
from contextlib import redirect_stdout
from io import StringIO
from tempfile import TemporaryDirectory
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('live_answers', Path(__file__).with_name('live-answers.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class OutputContracts(unittest.TestCase):
    def test_answer(self):
        answer, valid = module.parse_answer(json.dumps({'action': 'ANSWER', 'answer': 'Supported', 'citations': ['c1']}), {'c1'})
        self.assertTrue(valid)
        self.assertEqual(answer['action'], 'ANSWER')

    def test_unknown_citation_not_accepted(self):
        _, valid = module.parse_answer(json.dumps({'action': 'ANSWER', 'answer': 'Text', 'citations': ['invented']}), {'c1'})
        self.assertFalse(valid)

    def test_duplicate_citation_not_accepted(self):
        _, valid = module.parse_answer(json.dumps({'action': 'ANSWER', 'answer': 'Text', 'citations': ['c1', 'c1']}), {'c1'})
        self.assertFalse(valid)

    def test_bad_enum_or_empty_answer(self):
        for action, answer in [('APPROVE', 'Text'), ('ANSWER', '')]:
            with self.assertRaises(ValueError):
                module.parse_answer(json.dumps({'action': action, 'answer': answer, 'citations': []}), set())

    def test_judge_rejects_string_booleans(self):
        with self.assertRaises(ValueError):
            module.parse_judge(json.dumps({'claimsSatisfied': 'true', 'noUnsupportedClaims': True, 'reason': ''}))

    def test_judge_valid(self):
        result = module.parse_judge(json.dumps({'claimsSatisfied': False, 'noUnsupportedClaims': True, 'reason': 'Missing condition'}))
        self.assertFalse(result['claimsSatisfied'])

    def test_new_judge_requires_explicit_gold_review(self):
        value = {'claimsSatisfied': True, 'noUnsupportedClaims': True, 'reason': 'supported'}
        with self.assertRaises(ValueError):
            module.parse_judge(json.dumps(value), require_review=True)
        value.update(goldReviewRequired=True, goldReviewReason='reference asks unrelated facts')
        self.assertTrue(module.parse_judge(json.dumps(value), require_review=True)['goldReviewRequired'])
        value['goldReviewReason'] = ''
        with self.assertRaises(ValueError):
            module.parse_judge(json.dumps(value), require_review=True)

    def test_no_markdown_repair(self):
        with self.assertRaises(ValueError):
            module.parse_answer('```json\n{}\n```', set())


class RunContracts(unittest.TestCase):
    def exercise(self, budget_exhausted=False, split='dev', review_required=False):
        root = Path(__file__).resolve().parents[2]
        public = root / 'docs/fixtures/knowledge/rag400-v1'
        data = public / 'holdout' if split == 'holdout-candidate' else public
        queries = module.rows(data / f'{split}.queries.jsonl')
        snapshot = module.digest(public / 'knowledge-snapshot.json')
        calls = []

        class Client:
            def __init__(self, **kwargs): pass
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def post(self, url, headers, json):
                calls.append(json)
                is_judge = len(calls) % 2 == 0
                payload = ({'claimsSatisfied': True, 'noUnsupportedClaims': True, 'reason': 'test',
                            'goldReviewRequired': review_required,
                            'goldReviewReason': 'reference dispute' if review_required else ''}
                           if is_judge else {'action': 'ANSWER', 'answer': 'test', 'citations': ['chunk']})
                import json as codec
                body = {'choices': [{'message': {'content': codec.dumps(payload)},
                                     'finish_reason': 'length' if budget_exhausted else 'stop'}]}
                class Response:
                    status_code = 200
                    is_success = True
                    def json(self): return body
                return Response()

        with TemporaryDirectory() as directory:
            temp = Path(directory)
            config = temp / 'config.json'
            config.write_text(json.dumps({'model': 'test', 'baseUrl': 'https://example.invalid/v1', 'apiKey': 'fake-secret'}))
            retrieval = temp / 'retrieval.jsonl'
            records = [{'caseId': q['caseId'], 'snapshotSha256': snapshot, 'hits': [
                {'chunkId': 'chunk', 'documentCode': 'document', 'startLine': 1, 'endLine': 2, 'text': 'text'}]} for q in queries]
            retrieval.write_text('\n'.join(json.dumps(r) for r in records))
            output = temp / 'output'
            argv = ['live-answers.py', '--config', str(config), '--public', str(public), '--split', split,
                    '--retrieval', str(retrieval), '--output', str(output), '--limit', '1',
                    '--answer-max-tokens', '2048', '--judge-max-tokens', '4096']
            with patch('sys.argv', argv), patch.object(module.httpx, 'Client', Client), redirect_stdout(StringIO()):
                module.main()
                module.main()  # Same batch resumes without a second call.
            summary = json.loads((output / 'summary.json').read_text(encoding='utf-8'))
            self.assertEqual(summary['split'], split)
            self.assertEqual(len(calls), 1 if budget_exhausted else 2)
            self.assertEqual(calls[0]['max_tokens'], 2048)
            answer_input = json.loads(calls[0]['messages'][1]['content'])
            self.assertEqual(set(answer_input), {'question', 'retrievedEvidence', 'retrievalAbstained'})
            if budget_exhausted:
                self.assertEqual(summary['answerStatuses'], {'BUDGET_EXHAUSTED': 1})
                self.assertEqual(summary['judgeStatuses'], {'NOT_RUN': 1})
            else:
                self.assertEqual(calls[1]['max_tokens'], 4096)
                self.assertEqual(summary['semanticJudgePass'], 1)
                if review_required:
                    self.assertEqual(summary['automaticPass'], 0)
                    self.assertEqual(summary['goldReviewRequired'], 1)
            with patch('sys.argv', argv + ['--judge-max-tokens', '8192']), redirect_stdout(StringIO()):
                with self.assertRaisesRegex(ValueError, 'mixed configuration'):
                    module.main()
            # Duplicate raw records must not silently replace another observation.
            retrieval.write_text('\n'.join(json.dumps(r) for r in records + records[:1]))
            with patch('sys.argv', argv), redirect_stdout(StringIO()):
                with self.assertRaisesRegex(ValueError, 'duplicate, missing or foreign'):
                    module.main()

    def test_resume_and_gold_separation(self):
        self.exercise()

    def test_budget_exhaustion_is_not_semantic_failure(self):
        self.exercise(budget_exhausted=True)

    def test_calibration_selection(self):
        self.exercise(split='calibration')

    def test_former_holdout_selection(self):
        self.exercise(split='holdout-candidate')

    def test_gold_dispute_never_automatically_passes(self):
        self.exercise(review_required=True)


if __name__ == '__main__':
    unittest.main()
