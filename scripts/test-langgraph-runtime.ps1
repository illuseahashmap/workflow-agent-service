param([string]$PostgresBin, [switch]$IncludeJava)

$ErrorActionPreference = 'Stop'
$runtimePath = Join-Path (Split-Path -Parent $PSScriptRoot) 'agent-runtime-langgraph'
$previousBin = $env:WORKFLOW_LANGGRAPH_TEST_PG_BIN
Push-Location $runtimePath
try {
    if ($PostgresBin) {
        $resolvedBin = (Resolve-Path -LiteralPath $PostgresBin).Path
        if (-not (Test-Path -LiteralPath (Join-Path $resolvedBin 'initdb.exe'))) {
            throw 'PostgresBin must contain initdb.exe and pg_ctl.exe.'
        }
        $env:WORKFLOW_LANGGRAPH_TEST_PG_BIN = $resolvedBin
    }
    & uv sync --frozen
    if ($LASTEXITCODE -ne 0) { throw 'LangGraph dependency synchronization failed.' }
    & uv run ruff check .
    if ($LASTEXITCODE -ne 0) { throw 'LangGraph lint failed.' }
    & uv run ruff format --check .
    if ($LASTEXITCODE -ne 0) { throw 'LangGraph formatting check failed.' }
    if ($env:WORKFLOW_LANGGRAPH_TEST_PG_BIN -or $env:WORKFLOW_LANGGRAPH_TEST_DATABASE_URL) {
        if ($IncludeJava) {
            & uv run pytest -q
        } else {
            & uv run pytest -m 'not java' -q
        }
    } else {
        if ($IncludeJava) { throw 'Java gateway acceptance requires an isolated PostgreSQL server.' }
        Write-Warning 'Only local graph semantics will be tested; PostgreSQL acceptance is excluded.'
        & uv run pytest -m 'not postgres' -q
    }
    if ($LASTEXITCODE -ne 0) { throw 'LangGraph tests failed.' }
} finally {
    $env:WORKFLOW_LANGGRAPH_TEST_PG_BIN = $previousBin
    Pop-Location
}
