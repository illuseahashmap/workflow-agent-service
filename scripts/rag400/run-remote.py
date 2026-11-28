"""Reproducible RAG evaluation: owned database, frozen source, separate split reports."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import time
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
PUBLIC = ROOT / 'docs/fixtures/knowledge/rag400-v2'
SCRIPTS = ROOT / 'scripts/rag400'
TARGET = ROOT / 'workflow-boot/src/test/java/io/github/illuseahashmap/workflow/knowledge/Rag400ProjectRetrievalTest.java'
SPLITS = ['dev', 'calibration', 'holdout-candidate']
MODES = ['keyword', 'vector', 'hybrid', 'hybrid-expansion']
TEST_ROOT = ROOT / 'workflow-boot/src/test/java'
EXTRA_TARGETS = {
    'Rag400EvaluationModels.java': TEST_ROOT / 'io/github/illuseahashmap/workflow/knowledge/Rag400EvaluationModels.java',
    'Rag400ExpansionBridge.java': TEST_ROOT / 'io/github/illuseahashmap/knowledge/retrieval/application/Rag400ExpansionBridge.java',
}
CONFIG_KEYS = ('provider','baseUrl','model','modelVersion','dimension','batchSize','timeoutSeconds',
               'maxResponseBytes','queryInstruction','corpusInstruction')

def public_config(path):
    if not path: return None
    value = json.loads(path.read_text(encoding='utf-8'))
    return {k:value[k] for k in CONFIG_KEYS if k in value}


def validate_config(path, embedding=False):
    value=json.loads(path.read_text(encoding='utf-8'))
    for key in ('baseUrl','model','apiKey'):
        if not isinstance(value.get(key),str) or not value[key].strip():
            raise ValueError(f'Configuration requires {key}')
    url=urlsplit(value['baseUrl'])
    if url.scheme!='https' or not url.hostname or url.username or url.password or url.query or url.fragment:
        raise ValueError('Configuration requires a clean HTTPS endpoint without credentials/query/fragment')
    if embedding:
        if value.get('provider','OPENAI_COMPATIBLE') not in ('OPENAI_COMPATIBLE','VOLCENGINE_ARK'):
            raise ValueError('Unsupported embedding provider')
        if not isinstance(value.get('modelVersion'),str) or not value['modelVersion'].strip():
            raise ValueError('Embedding requires modelVersion')
        if type(value.get('dimension')) is not int or not 1<=value['dimension']<=4096:
            raise ValueError('Embedding dimension must be 1..4096')


def database_config(path):
    if not path: return None
    value=json.loads(path.read_text(encoding='utf-8'))
    address=value.get('jdbcUrl','')
    url=urlsplit(address.removeprefix('jdbc:'))
    if (not address.startswith('jdbc:postgresql://') or url.hostname not in ('127.0.0.1','localhost','::1')
        or url.path!='/postgres' or url.query or url.fragment or url.username or url.password
        or value.get('purpose')!='ISOLATED_EVALUATION_SERVER'):
        raise ValueError('Native database must be an explicitly dedicated loopback evaluation server /postgres')
    if not value.get('username') or not value.get('password'):
        raise ValueError('Native database credentials required')
    return value



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
    p.add_argument('--splits', nargs='+', choices=SPLITS, default=['dev','calibration'])
    p.add_argument('--modes', nargs='+', choices=MODES, default=MODES)
    p.add_argument('--embedding-config', type=Path)
    p.add_argument('--database-config', type=Path, help='Dedicated loopback native PostgreSQL alternative to Docker')
    p.add_argument('--expansion-config', type=Path)
    p.add_argument('--fixture-models', action='store_true', help='Synthetic vectors/topics for wiring tests ONLY')
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
    if len(set(a.modes)) != len(a.modes): p.error('duplicate mode')
    if a.fixture_models and a.live: p.error('synthetic fixtures cannot produce live quality scores')
    if not a.fixture_models:
        if any(m!='keyword' for m in a.modes) and not a.embedding_config:
            p.error('vector/hybrid modes require --embedding-config; use --modes keyword for the old baseline')
        if 'hybrid-expansion' in a.modes and not a.expansion_config:
            p.error('hybrid-expansion requires --expansion-config')
    out = a.output.resolve()
    if out == ROOT or ROOT in out.parents:
        p.error('output must be outside checkout')
    for config in (a.config, a.judge_config, a.embedding_config, a.expansion_config, a.database_config):
        if config and (ROOT in config.resolve().parents or out in config.resolve().parents):
            p.error('credentials must be outside checkout AND result directory')
        if config and config!=a.database_config: validate_config(config, embedding=config==a.embedding_config)
    native=database_config(a.database_config)
    if run(['git', 'status', '--porcelain'], capture=True).strip():
        raise RuntimeError('Commit or isolate changes before evaluation; source must be clean')
    revision = run(['git', 'rev-parse', 'HEAD'], capture=True).strip()
    frozen = {'sourceCommit': revision, 'splits': a.splits, 'modes':a.modes, 'fixtureOnly':a.fixture_models,
              'databaseMode':{'mode':'NATIVE','jdbcUrl':native['jdbcUrl'],'username':native['username']} if native else {'mode':'DOCKER'},
              'embeddingConfig':public_config(a.embedding_config), 'expansionConfig':public_config(a.expansion_config),
              'datasetFiles': {str(f.relative_to(PUBLIC)): sha(f) for f in sorted(PUBLIC.rglob('*'))
                               if f.is_file() and (f.name.endswith(('.cases.jsonl', '.queries.jsonl'))
                                                  or f.name in ('knowledge-snapshot.json','gold-review.json'))},
              'mode': 'production keyword/vector/RRF/query-expansion matrix; NOT Agent E2E',
              'retrievalImage': 'pgvector/pgvector:pg18-trixie'}
    manifest = out / 'run.json'
    if a.resume:
        previous = json.loads(manifest.read_text(encoding='utf-8'))
        if previous['frozen'] != frozen:
            raise RuntimeError('Source or inputs changed; use a new output directory')
    else:
        out.mkdir(parents=True, exist_ok=False)
        previous = {'frozen': frozen, 'retrieval': {}, 'auxiliary': {}}
        save(manifest, previous)
    # Full validation, including the now-versioned former holdout. No model calls.
    run(['node', SCRIPTS / 'validate.mjs', PUBLIC, PUBLIC / 'holdout', '--no-write'])
    env = os.environ.copy()
    for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'JAVA_OPTS',
                'MAVEN_ARGS', 'MAVEN_OPTS', 'WORKFLOW_MIGRATION_DB_URL',
                'WORKFLOW_MIGRATION_DB_USERNAME', 'WORKFLOW_MIGRATION_DB_PASSWORD'):
        env.pop(key, None)
    env['PYTHONUTF8'] = '1'
    if native:
        import psycopg
        address=urlsplit(native['jdbcUrl'][5:])
        try:
            with psycopg.connect(host=address.hostname,port=address.port or 5432,dbname='postgres',
                                  user=native['username'],password=native['password'],connect_timeout=10) as connection:
                installed=connection.execute("SELECT EXISTS (SELECT 1 FROM pg_available_extensions WHERE name='vector')").fetchone()[0]
                if any(m!='keyword' for m in a.modes) and not installed:
                    raise RuntimeError('Install pgvector matching the native PostgreSQL major version before running vector modes')
        except psycopg.Error as failure:
            raise RuntimeError(f'Native database preflight failed: {type(failure).__name__}') from None
    if a.embedding_config: env['RAG400_EMBEDDING_CONFIG'] = str(a.embedding_config.resolve())
    if 'hybrid-expansion' in a.modes:
        spec=importlib.util.spec_from_file_location('expansion_inputs',SCRIPTS/'expansion-inputs.py')
        expansion=importlib.util.module_from_spec(spec);spec.loader.exec_module(expansion)
        for split in a.splits:
            data=PUBLIC/'holdout' if split=='holdout-candidate' else PUBLIC
            expansion_dir=out/split/'expansion'
            target=expansion_dir/'queries.jsonl'
            if split in previous['auxiliary'] and sha(target)!=previous['auxiliary'][split]:
                raise RuntimeError('Auxiliary artifact changed; refusing resume')
            expansion.prepare(data/f'{split}.queries.jsonl',expansion_dir,a.expansion_config,a.fixture_models)
            previous['auxiliary'][split]=sha(target)
            save(manifest,previous)
    container = None
    copied = []
    try:
        missing = [s for s in a.splits if any(f'{s}/{m}' not in previous['retrieval'] for m in a.modes)]
        targets = {'Rag400ProjectRetrievalTest.java': TARGET, **EXTRA_TARGETS}
        if missing:
            if any(t.exists() for t in targets.values()):
                raise RuntimeError('Temporary test target exists; refusing to overwrite')
            if native:
                env.update(WORKFLOW_TEST_DATABASE_URL=native['jdbcUrl'],WORKFLOW_TEST_DATABASE_USER=native['username'],
                           WORKFLOW_TEST_DATABASE_PASSWORD=native['password'])
            else:
                env.update(POSTGRES_PASSWORD=secrets.token_hex(24))
                container = run(['docker', 'run', '--detach', '--publish', '127.0.0.1::5432',
                                 '--env', 'POSTGRES_PASSWORD', frozen['retrievalImage']], env, True).strip()
                print(f'Owned evaluation container: {container}', flush=True)
                for _ in range(60):
                    ready = subprocess.run(['docker', 'exec', container, 'pg_isready', '-h', '127.0.0.1', '-U', 'postgres'],
                                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
                    if ready.returncode == 0: break
                    time.sleep(1)
                else: raise RuntimeError('Test database not ready')
                port = run(['docker', 'port', container, '5432/tcp'], capture=True).strip().rsplit(':', 1)[1]
                env.update(WORKFLOW_TEST_DATABASE_URL=f'jdbc:postgresql://127.0.0.1:{port}/postgres',
                           WORKFLOW_TEST_DATABASE_USER='postgres', WORKFLOW_TEST_DATABASE_PASSWORD=env['POSTGRES_PASSWORD'])
            for name, target in targets.items():
                target.parent.mkdir(parents=True,exist_ok=True)
                shutil.copyfile(SCRIPTS/name,target)
                copied.append(target)
        for split in a.splits:
            if split in missing:
                if any((out/split/m/'retrieval').exists() for m in a.modes):
                    raise RuntimeError('Partial retrieval exists; keep failed batch and use a new directory')
                wrapper = [ROOT / 'mvnw.cmd'] if os.name == 'nt' else ['bash', ROOT / 'mvnw']
                locations='classpath:db/migration/platform'
                if any(m!='keyword' for m in a.modes): locations+=',classpath:db/migration/vector'
                run([*wrapper, '--batch-mode', '--no-transfer-progress', '-pl', 'workflow-boot', '-am',
                     '-Dsurefire.failIfNoSpecifiedTests=false', '-Dtest=Rag400ProjectRetrievalTest',
                     '-Dcheckstyle.skip=true', f'-Drag400.migrationLocations={locations}',
                     f'-Drag400.input={PUBLIC}', f'-Drag400.output={out/split}', f'-Drag400.split={split}',
                     f'-Drag400.modes={",".join(a.modes)}',f'-Drag400.fixture={str(a.fixture_models).lower()}',
                     f'-Drag400.cache={out/"embedding-cache.json"}',
                     f'-Drag400.auxiliary={out/split/"expansion/queries.jsonl"}', 'test'], env)
                for mode in a.modes:
                    previous['retrieval'][f'{split}/{mode}']=sha(out/split/mode/'retrieval/project-raw.jsonl')
                save(manifest,previous)
            for mode in a.modes:
                retrieval=out/split/mode/'retrieval'
                if sha(retrieval/'project-raw.jsonl')!=previous['retrieval'][f'{split}/{mode}']:
                    raise RuntimeError('Retrieval artifact changed; refusing resume')
                run([sys.executable,SCRIPTS/'normalize-project.py','--public',PUBLIC,'--run',retrieval,'--split',split],env)
    finally:
        for target in copied:
            target.unlink(missing_ok=True)
            if TEST_ROOT in target.parents:
                compiled=ROOT/'workflow-boot/target/test-classes'/target.relative_to(TEST_ROOT).with_suffix('.class')
                compiled.unlink(missing_ok=True)
                for inner in compiled.parent.glob(compiled.stem+'$*.class'): inner.unlink()
        if container: run(['docker','rm','--force','--volumes',container])
    # All retrieval finishes before any paid call; no database remains during model evaluation.
    if a.live:
        for split in a.splits:
            for mode in a.modes:
                command = [sys.executable, SCRIPTS / 'live-answers.py', '--public', PUBLIC, '--split', split,
                           '--retrieval', out/split/mode/'retrieval/project-raw.jsonl', '--output', out/split/mode/'model',
                           '--config', a.config.resolve(), '--workers', a.workers,
                           '--answer-max-tokens', a.answer_max_tokens, '--judge-max-tokens', a.judge_max_tokens]
                if a.judge_config: command.extend(['--judge-config', a.judge_config.resolve()])
                run(command,env)
    comparison={}
    for split in a.splits:
        comparison[split]={}
        for mode in a.modes:
            entry={'retrieval':json.loads((out/split/mode/'retrieval/summary.json').read_text(encoding='utf-8'))}
            model=out/split/mode/'model/summary.json'
            if model.exists(): entry['model']=json.loads(model.read_text(encoding='utf-8'))
            comparison[split][mode]=entry
    save(out/'comparison.json',{'fixtureOnly':a.fixture_models,'splits':comparison,
                               'limits':'Paired same queries/corpus/final K=10; query vectors warmed once. No production accuracy or latency claim.'})
    files = {str(f.relative_to(out)): sha(f) for f in sorted(out.rglob('*'))
             if f.is_file() and f.name != 'checksums.json'}
    save(out / 'checksums.json', files)
    print(f'Completed: {out}; return the entire directory. No production accuracy claim.')


if __name__ == '__main__':
    main()
