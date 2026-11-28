import importlib.util
from pathlib import Path
import unittest
import json
import hashlib
from tempfile import TemporaryDirectory
from unittest.mock import patch
from contextlib import redirect_stdout
from io import StringIO

spec=importlib.util.spec_from_file_location('normalize',Path(__file__).with_name('normalize-project.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class NativeMappingTest(unittest.TestCase):
    def test_cli_selects_each_split_and_reports_exposure(self):
        public = Path(__file__).resolve().parents[2] / 'docs/fixtures/knowledge/rag400-v1'
        digest = hashlib.sha256((public / 'knowledge-snapshot.json').read_bytes()).hexdigest()
        for split, count in [('dev', 200), ('calibration', 80), ('holdout-candidate', 120)]:
            with self.subTest(split=split), TemporaryDirectory() as folder:
                data = public / 'holdout' if split == 'holdout-candidate' else public
                queries = module.rows(data / f'{split}.queries.jsonl')
                raw = [{'caseId': q['caseId'], 'status': 'SUCCESS', 'snapshotSha256': digest,
                        'hits': [], 'latencyMs': 1, 'retrievalStatus': 'EMPTY'} for q in queries]
                target = Path(folder)
                (target / 'project-raw.jsonl').write_text('\n'.join(json.dumps(r) for r in raw), encoding='utf-8')
                with patch('sys.argv', ['normalize-project.py', '--public', str(public), '--run', folder, '--split', split]), redirect_stdout(StringIO()):
                    module.main()
                summary = module.read(target / 'summary.json')
                self.assertEqual(summary['cases'], count)
                self.assertEqual(summary['formerHoldoutRead'], split == 'holdout-candidate')

    def setUp(self):
        self.docs={'d':{'scope':'s','documentVersion':'1-draft','sha256':'h','_lines':['## section','first','second']}}
        self.bodies={'d':{'section':{2,3}}}
        self.row={'caseId':'c','status':'SUCCESS','snapshotSha256':'snap','hits':[]}
    def hit(self,line,rank):
        return {'documentCode':'d','databaseVersion':1,'scope':'s','startLine':line,'endLine':line,
                'rank':rank,'text':self.docs['d']['_lines'][line-1]}
    def test_heading_is_not_evidence(self):
        self.row['hits']=[self.hit(1,1)]
        full,partial=module.normalize([self.row],self.docs,self.bodies,'snap')
        self.assertEqual(full[0]['evidence'],[]);self.assertEqual(partial[0]['evidence'],[])
    def test_partial_clause_not_whole_section(self):
        self.row['hits']=[self.hit(2,2)]
        full,partial=module.normalize([self.row],self.docs,self.bodies,'snap')
        self.assertEqual(full[0]['evidence'],[]);self.assertEqual(partial[0]['evidence'][0]['rank'],2)
    def test_completion_rank_is_last_needed_chunk(self):
        self.row['hits']=[self.hit(2,2),self.hit(3,8)]
        full,_=module.normalize([self.row],self.docs,self.bodies,'snap')
        self.assertEqual(full[0]['evidence'][0]['rank'],8)
    def test_fabricated_native_excerpt_rejected(self):
        self.row['hits']=[{**self.hit(2,1),'text':'fabricated'}]
        with self.assertRaises(ValueError):module.normalize([self.row],self.docs,self.bodies,'snap')

if __name__=='__main__':unittest.main()
