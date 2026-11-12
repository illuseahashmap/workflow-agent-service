# Local-only P-17 regression: never select the paid provider test or a previous batch.
$ErrorActionPreference = 'Stop'
$batch = [guid]::NewGuid().ToString()
Push-Location (Join-Path $PSScriptRoot '..')
try {
    & .\mvnw.cmd -pl workflow-boot -am '-Dsurefire.failIfNoSpecifiedTests=false' "-Dworkflow.p17.batch-id=$batch" '-Dtest=P17JointRunTest#scriptedRunsExercisePublishedBindingsAndOutbox,P17RoutingFixtureTest' test
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $directory = "workflow-boot/target/p17-runs/$batch"
    $summary = & node scripts/p17-score-batch.mjs $directory
    $grade = $LASTEXITCODE
    if (Test-Path -LiteralPath $directory) {
        [IO.File]::WriteAllText((Join-Path (Resolve-Path $directory) 'scores.json'), ($summary -join [Environment]::NewLine))
    }
    $summary
    exit $grade
} finally { Pop-Location }
