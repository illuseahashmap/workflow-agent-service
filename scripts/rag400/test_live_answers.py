import importlib.util
import json
from pathlib import Path
import unittest

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

    def test_no_markdown_repair(self):
        with self.assertRaises(ValueError):
            module.parse_answer('```json\n{}\n```', set())


if __name__ == '__main__':
    unittest.main()
