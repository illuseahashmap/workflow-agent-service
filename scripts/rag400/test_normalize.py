import importlib.util
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('normalize',Path(__file__).with_name('normalize-project.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class NativeMappingTest(unittest.TestCase):
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
