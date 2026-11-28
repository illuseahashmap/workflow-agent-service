"""Reproducible RAG evaluation: owned database, frozen source, separate split reports."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
PUBLIC = ROOT / 'docs/fixtures/knowledge/rag400-v1'
SCRIPTS = ROOT / 'scripts/rag400'
TARGET = ROOT / 'workflow-boot/src/test/java/io/github/illuseahashmap/workflow/knowledge/Rag400ProjectRetrievalTest.java'
SPLITS = ['dev', 'calibration', 'holdout-candidate']


def run(args, env=None, capture=False):
    command = [str(x) for x in args]
    executable = shutil.which(command[0])
    if not executable:
        raise RuntimeError(f'Missing command: {command[0]}')
    command[0] = executable
    if os.name == 'nt' and executable.lower().endswith(('.cmd', '.bat')):
        command = ['cmd.exe', '/d', '/c', *command]
    return subprocess.run(command, cwd=ROOT, env=env, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None).stdout


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(path, value):
    temp = path.with_suffix('.tmp')
    temp.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    temp.replace(path)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', type=Path, required=True, help='New directory outside checkout')
    p.add_argument('--splits', nargs='+', choices=SPLITS, default=SPLITS)
    p.add_argument('--live', action='store_true', help='Explicitly enable paid model calls')
    p.add_argument('--config', type=Path)
    p.add_argument('--judge-config', type=Path)
    p.add_argument('--answer-max-tokens', type=int, default=4096)
    p.add_argument('--judge-max-tokens', type=int, default=4096)
    p.add_argument('--workers', type=int, default=3)
    p.add_argument('--resume', action='store_true')
    a = p.parse_args()
    if a.live and not a.config:
        p.error('--live requires --config')
    if len(set(a.splits)) != len(a.splits):
        p.error('duplicate split')
    out = a.output.resolve()
    if out == ROOT or ROOT in out.parents:
        p.error('output must be outside checkout')
    for config in (a.config, a.judge_config):
        if config and (ROOT in config.resolve().parents or out in config.resolve().parents):
            p.error('credentials must be outside checkout AND result directory')
    if run(['git', 'status', '--porcelain'], capture=True).strip():
        raise RuntimeError('Commit or isolate changes before evaluation; source must be clean')
    revision = run(['git', 'rev-parse', 'HEAD'], capture=True).strip()
    frozen = {'sourceCommit': revision, 'splits': a.splits,
              'datasetFiles': {str(f.relative_to(PUBLIC)): sha(f) for f in sorted(PUBLIC.rglob('*'))
                               if f.is_file() and (f.name.endswith(('.cases.jsonl', '.queries.jsonl'))
                                                  or f.name == 'knowledge-snapshot.json')},
              'mode': 'keyword-retrieval/direct-model; NOT Agent E2E',
              'retrievalImage': 'pgvector/pgvector:pg18-trixie'}
    manifest = out / 'run.json'
    if a.resume:
        previous = json.loads(manifest.read_text(encoding='utf-8'))
        if previous['frozen'] != frozen:
            raise RuntimeError('Source or inputs changed; use a new output directory')
    else:
        out.mkdir(parents=True, exist_ok=False)
        previous = {'frozen': frozen, 'retrieval': {}}
        save(manifest, previous)
    # Full validation, including the now-versioned former holdout. No model calls.
    run(['node', SCRIPTS / 'validate.mjs', PUBLIC, PUBLIC / 'holdout', '--no-write'])
    env = os.environ.copy()
    for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'JAVA_OPTS',
                'MAVEN_ARGS', 'MAVEN_OPTS', 'WORKFLOW_MIGRATION_DB_URL',
                'WORKFLOW_MIGRATION_DB_USERNAME', 'WORKFLOW_MIGRATION_DB_PASSWORD'):
        env.pop(key, None)
    env['PYTHONUTF8'] = '1'
    container = None
    copied = False
    try:
        missing = [s for s in a.splits if s not in previous['retrieval']]
        if missing:
            if TARGET.exists():
                raise RuntimeError('Temporary test target exists; refusing to overwrite')
            env.update(POSTGRES_PASSWORD=secrets.token_hex(24))
            container = run(['docker', 'run', '--detach', '--publish', '127.0.0.1::5432',
                             '--env', 'POSTGRES_PASSWORD', frozen['retrievalImage']], env, True).strip()
            print(f'Owned evaluation container: {container}', flush=True)
            for _ in range(60):
                ready = subprocess.run(['docker', 'exec', container, 'pg_isready', '-h', '127.0.0.1', '-U', 'postgres'],
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
                if ready.returncode == 0:
                    break
                time.sleep(1)
            else:
                raise RuntimeError('Test database not ready')
            port = run(['docker', 'port', container, '5432/tcp'], capture=True).strip().rsplit(':', 1)[1]
            env.update(WORKFLOW_TEST_DATABASE_URL=f'jdbc:postgresql://127.0.0.1:{port}/postgres',
                       WORKFLOW_TEST_DATABASE_USER='postgres', WORKFLOW_TEST_DATABASE_PASSWORD=env['POSTGRES_PASSWORD'])
            shutil.copyfile(SCRIPTS / 'Rag400ProjectRetrievalTest.java', TARGET)
            copied = True
        for split in a.splits:
            retrieval = out / split / 'retrieval'
            if split in previous['retrieval']:
                if sha(retrieval / 'project-raw.jsonl') != previous['retrieval'][split]:
                    raise RuntimeError('Retrieval artifact changed; refusing resume')
            else:
                retrieval.mkdir(parents=True, exist_ok=False)
                wrapper = [ROOT / 'mvnw.cmd'] if os.name == 'nt' else ['bash', ROOT / 'mvnw']
                run([*wrapper, '--batch-mode', '--no-transfer-progress', '-pl', 'workflow-boot', '-am',
                     '-Dsurefire.failIfNoSpecifiedTests=false', '-Dtest=Rag400ProjectRetrievalTest',
                     '-Dcheckstyle.skip=true', '-Drag400.migrationLocations=classpath:db/migration/platform',
                     f'-Drag400.input={PUBLIC}', f'-Drag400.output={retrieval}', f'-Drag400.split={split}', 'test'], env)
                previous['retrieval'][split] = sha(retrieval / 'project-raw.jsonl')
                save(manifest, previous)
            run([sys.executable, SCRIPTS / 'normalize-project.py', '--public', PUBLIC, '--run', retrieval, '--split', split], env)
    finally:
        if copied:
            TARGET.unlink()
            compiled = ROOT / 'workflow-boot/target/test-classes/io/github/illuseahashmap/workflow/knowledge/Rag400ProjectRetrievalTest.class'
            compiled.unlink(missing_ok=True)
        if container:
            run(['docker', 'rm', '--force', '--volumes', container])
    # All retrieval finishes before any paid call; no database remains during model evaluation.
    if a.live:
        for split in a.splits:
            command = [sys.executable, SCRIPTS / 'live-answers.py', '--public', PUBLIC, '--split', split,
                       '--retrieval', out / split / 'retrieval/project-raw.jsonl', '--output', out / split / 'model',
                       '--config', a.config.resolve(), '--workers', a.workers,
                       '--answer-max-tokens', a.answer_max_tokens, '--judge-max-tokens', a.judge_max_tokens]
            if a.judge_config:
                command.extend(['--judge-config', a.judge_config.resolve()])
            run(command, env)
    files = {str(f.relative_to(out)): sha(f) for f in sorted(out.rglob('*'))
             if f.is_file() and f.name != 'checksums.json'}
    save(out / 'checksums.json', files)
    print(f'Completed: {out}; return the entire directory. No production accuracy claim.')


if __name__ == '__main__':
    main()
