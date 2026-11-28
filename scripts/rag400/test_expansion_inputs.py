import importlib.util
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('expansion',Path(__file__).with_name('expansion-inputs.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


class QueryOnlyExpansion(unittest.TestCase):
    def test_only_query_is_sent_and_resume_has_no_extra_call(self):
        calls=[]
        class Client:
            def __init__(self,**kw): pass
            def __enter__(self): return self
            def __exit__(self,*args): pass
            def post(self,url,**kw):
                calls.append(kw['json'])
                class Response:
                    status_code=200
                    is_success=True
                    def json(self): return {'choices':[{'finish_reason':'stop','message':{'content':'{"auxiliaryQuery":"服务合同"}'}}]}
                return Response()
        with TemporaryDirectory() as folder:
            p=Path(folder);queries=p/'q.jsonl';cfg=p/'secret.json'
            queries.write_text(json.dumps({'caseId':'x','query':'Question only','gold':'never-send'}),encoding='utf-8')
            cfg.write_text(json.dumps({'baseUrl':'https://example.invalid/v1','model':'m','apiKey':'fake-key'}),encoding='utf-8')
            with patch.object(module.httpx,'Client',Client):
                module.prepare(queries,p/'result',cfg)
                module.prepare(queries,p/'result',cfg)
            self.assertEqual(len(calls),1)
            self.assertEqual(calls[0]['messages'][1]['content'],'Question only')
            self.assertNotIn('never-send',json.dumps(calls))
            self.assertNotIn('fake-key',(p/'result/manifest.json').read_text(encoding='utf-8'))
            queries.write_text(json.dumps({'caseId':'x','query':'changed'}),encoding='utf-8')
            with self.assertRaises(ValueError): module.prepare(queries,p/'result',cfg)


if __name__=='__main__': unittest.main()
