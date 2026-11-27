# Explicit graph acceptance; never use a previous batch or a paid model.
$ErrorActionPreference = 'Stop'
$batch = [guid]::NewGuid().ToString()
Push-Location (Join-Path $PSScriptRoot '..')
try {
    & .\mvnw.cmd -pl workflow-boot -am '-Dsurefire.failIfNoSpecifiedTests=false' "-Dworkflow.p17.batch-id=$batch" '-Dworkflow.p17.graph=true' '-Dtest=P17GraphJointRunTest' test
    $testStatus = $LASTEXITCODE
    $directory = "workflow-boot/target/p17-graph-runs/$batch"
    if (-not (Test-Path -LiteralPath "$directory/acceptance.json")) {
        Write-Error "Graph acceptance did not produce all-case evidence for $batch."
        exit 1
    }
    $summary = & node scripts/p17-score-batch.mjs $directory
    $grade = $LASTEXITCODE
    [IO.File]::WriteAllText((Join-Path (Resolve-Path $directory) 'scores.json'), ($summary -join [Environment]::NewLine), [Text.UTF8Encoding]::new($false))
    $summary
    if ($testStatus -ne 0 -or $grade -ne 0) { exit 1 }
    exit 0
} finally { Pop-Location }
