import importlib.util
from pathlib import Path
import subprocess
import json
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('remote_runner', Path(__file__).with_name('run-remote.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class RemoteRunnerSafety(unittest.TestCase):
    def test_native_server_is_explicit_and_cannot_point_to_business_database(self):
        with TemporaryDirectory() as folder:
            p=Path(folder)/'db.json'
            data={'purpose':'ISOLATED_EVALUATION_SERVER','jdbcUrl':'jdbc:postgresql://127.0.0.1:15432/postgres',
                  'username':'test','password':'fake-only'}
            p.write_text(json.dumps(data),encoding='utf-8')
            self.assertEqual(module.database_config(p)['username'],'test')
            for url in ('jdbc:postgresql://127.0.0.1:5432/workflow_agent','jdbc:postgresql://remote.example/postgres'):
                data['jdbcUrl']=url;p.write_text(json.dumps(data),encoding='utf-8')
                with self.assertRaises(ValueError): module.database_config(p)

    def test_maven_failure_cleans_only_owned_container_and_temporary_test(self):
        calls = []
        owned = 'a' * 64

        def run(args, env=None, capture=False):
            args = [str(x) for x in args]
            calls.append(args)
            if args[:3] == ['git', 'status', '--porcelain']:
                return ''
            if args[:2] == ['git', 'rev-parse']:
                return 'commit'
            if args[:2] == ['docker', 'run']:
                return owned
            if args[:2] == ['docker', 'port']:
                return '127.0.0.1:54321'
            if '-Dtest=Rag400ProjectRetrievalTest' in args:
                self.assertNotIn('WORKFLOW_MIGRATION_DB_URL', env)
                self.assertEqual(env['WORKFLOW_TEST_DATABASE_URL'], 'jdbc:postgresql://127.0.0.1:54321/postgres')
                raise subprocess.CalledProcessError(1, 'maven')

        with TemporaryDirectory() as folder:
            target = Path(folder) / 'temporary.java'
            with (patch.object(module, 'ROOT', Path(folder) / 'source'),
                  patch.object(module, 'EXTRA_TARGETS', {}),
                  patch.object(module, 'TARGET', target), patch.object(module, 'run', side_effect=run),
                  patch.object(module.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0)),
                  patch('sys.argv', ['run-remote.py', '--output', str(Path(folder) / 'batch'), '--modes', 'keyword']),
                  self.assertRaises(subprocess.CalledProcessError)):
                module.main()
            self.assertFalse(target.exists())
            self.assertEqual(calls[-1], ['docker', 'rm', '--force', '--volumes', owned])


if __name__ == '__main__':
    unittest.main()
