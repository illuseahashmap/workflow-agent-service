"""Local infrastructure adapter; repository evaluation scripts remain unchanged."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

BASE = Path(__file__).resolve().parent
ROOT = BASE / 'evaluation-hardening'
OUT = BASE / 'rag400-run-001'
PUBLIC = ROOT / 'docs/fixtures/knowledge/rag400-v1'
SCRIPTS = ROOT / 'scripts/rag400'
SPLITS = ['dev', 'calibration', 'holdout-candidate']
TARGET = ROOT / 'workflow-boot/src/test/java/io/github/illuseahashmap/workflow/knowledge/Rag400ProjectRetrievalTest.java'


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def save(p, value):
    p.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT, text=True).strip()


def main():
    if git('status', '--porcelain'):
        raise RuntimeError('Source must be clean')
    subprocess.run(['git', 'merge-base', '--is-ancestor', 'ef333aa', 'HEAD'], cwd=ROOT, check=True)
    OUT.mkdir(exist_ok=False)
    record = {'sourceCommit': git('rev-parse', 'HEAD'), 'splits': SPLITS,
        'mode': 'keyword-retrieval/direct-model; NOT Agent E2E',
        'infrastructureDeviation': 'User approved native PostgreSQL instead of Docker; disposable databases on loopback local PostgreSQL 17.5; no business table access.',
        'retrieval': {}, 'stages': [], 'exitCode': None,
        'datasetFiles': {str(f.relative_to(PUBLIC)).replace('\\', '/'): sha(f) for f in sorted(PUBLIC.rglob('*'))
            if f.is_file() and (f.name.endswith(('.cases.jsonl', '.queries.jsonl')) or f.name == 'knowledge-snapshot.json')},
        'scriptHashes': {name: sha(SCRIPTS / name) for name in ['Rag400ProjectRetrievalTest.java', 'normalize-project.py', 'live-answers.py', 'run-remote.py']},
        'answerMaxTokens': 4096, 'judgeMaxTokens': 4096, 'workers': 3,
        'sameModelJudge': True, 'model': 'deepseek/deepseek-v4-flash'}
    env = os.environ.copy()
    for name in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'JAVA_OPTS', 'MAVEN_ARGS', 'MAVEN_OPTS',
                 'WORKFLOW_MIGRATION_DB_URL', 'WORKFLOW_MIGRATION_DB_USERNAME', 'WORKFLOW_MIGRATION_DB_PASSWORD'):
        env.pop(name, None)
    env['PYTHONUTF8'] = '1'
    env['JAVA_HOME'] = str(BASE / 'runtime/jdk25.0.5_1')
    secret = json.loads((BASE / 'config/secrets.json').read_text(encoding='utf-8-sig'))
    env.update(WORKFLOW_TEST_DATABASE_URL='jdbc:postgresql://127.0.0.1:15432/postgres',
               WORKFLOW_TEST_DATABASE_USER='postgres', WORKFLOW_TEST_DATABASE_PASSWORD=secret['databasePassword'])
    def run(label, args, log=False):
        start = time.monotonic()
        print('Starting ' + label, flush=True)
        if log:
            with (BASE / 'logs' / (label + '.log')).open('w', encoding='utf-8') as output:
                result = subprocess.run(args, cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT)
        else:
            result = subprocess.run(args, cwd=ROOT, env=env)
        record['stages'].append({'stage': label, 'exitCode': result.returncode, 'elapsedSeconds': round(time.monotonic() - start, 2)})
        save(OUT / 'run.json', record)
        if result.returncode:
            raise RuntimeError(f'{label} failed: {result.returncode}')
    copied = False
    try:
        run('offline-data', ['node', str(SCRIPTS / 'validate.mjs'), str(PUBLIC), str(PUBLIC / 'holdout'), '--no-write'])
        run('offline-node', ['node', '--test', str(SCRIPTS / 'test-validator.mjs')])
        run('offline-python', [sys.executable, '-m', 'unittest', 'discover', '-s', str(SCRIPTS), '-p', 'test_*.py'])
        if TARGET.exists():
            raise RuntimeError('Refusing existing temporary Java file')
        shutil.copyfile(SCRIPTS / 'Rag400ProjectRetrievalTest.java', TARGET)
        copied = True
        try:
            for split in SPLITS:
                retrieval = OUT / split / 'retrieval'
                retrieval.mkdir(parents=True, exist_ok=False)
                run('retrieval-' + split, ['cmd.exe', '/d', '/c', str(ROOT / 'mvnw.cmd'), '--batch-mode', '--no-transfer-progress',
                    '-pl', 'workflow-boot', '-am', '-Dsurefire.failIfNoSpecifiedTests=false', '-Dtest=Rag400ProjectRetrievalTest',
                    '-Dcheckstyle.skip=true', '-Drag400.migrationLocations=classpath:db/migration/platform',
                    f'-Drag400.input={PUBLIC}', f'-Drag400.output={retrieval}', f'-Drag400.split={split}', 'test'], log=True)
                record['retrieval'][split] = sha(retrieval / 'project-raw.jsonl')
                run('normalize-' + split, [sys.executable, str(SCRIPTS / 'normalize-project.py'), '--public', str(PUBLIC), '--run', str(retrieval), '--split', split])
        finally:
            if copied:
                TARGET.unlink()
                (ROOT / 'workflow-boot/target/test-classes/io/github/illuseahashmap/workflow/knowledge/Rag400ProjectRetrievalTest.class').unlink(missing_ok=True)
        if git('status', '--porcelain'):
            raise RuntimeError('Source dirty after retrieval cleanup')
        for split in SPLITS:
            run('live-' + split, [sys.executable, str(SCRIPTS / 'live-answers.py'), '--public', str(PUBLIC), '--split', split,
                '--retrieval', str(OUT / split / 'retrieval/project-raw.jsonl'), '--output', str(OUT / split / 'model'),
                '--config', str(BASE / 'config/model-provider.json'), '--answer-max-tokens', '4096', '--judge-max-tokens', '4096', '--workers', '3'])
        record['exitCode'] = 0
    except Exception as exc:
        record['exitCode'] = 1
        record['failureType'] = type(exc).__name__
        raise
    finally:
        save(OUT / 'run.json', record)
        shutil.copyfile(Path(__file__), OUT / 'infrastructure-adapter.py')
        save(OUT / 'checksums.json', {str(f.relative_to(OUT)).replace('\\', '/'): sha(f) for f in sorted(OUT.rglob('*')) if f.is_file() and f.name != 'checksums.json'})
    print('Completed all three splits; exit code 0', flush=True)


if __name__ == '__main__':
    main()
