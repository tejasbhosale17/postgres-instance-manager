$ErrorActionPreference = 'Stop'

$wrapper = Join-Path $PSScriptRoot '..\mvnw.cmd'
if (-not (Test-Path $wrapper)) {
    throw "Maven wrapper was not found at $wrapper."
}

$dockerVersion = docker info --format '{{.ServerVersion}}'
if ($LASTEXITCODE -ne 0 -or -not $dockerVersion) {
    throw 'A running Docker Engine is required to measure the full test suite.'
}

$mavenVersionOutput = & $wrapper -version
if ($LASTEXITCODE -ne 0) {
    throw 'The Maven wrapper could not report its runtime versions.'
}
$javaVersionLine = $mavenVersionOutput | Where-Object { $_ -match 'Java version:' } | Select-Object -First 1
if (-not $javaVersionLine) {
    throw 'The Maven wrapper output did not include the Java runtime version.'
}

$startedAt = [DateTimeOffset]::UtcNow
$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
& $wrapper -B clean test
$mavenExitCode = $LASTEXITCODE
$stopwatch.Stop()

$reportDirectory = Join-Path $PSScriptRoot '..\target\surefire-reports'
$reports = @(Get-ChildItem -Path $reportDirectory -Filter 'TEST-*.xml' -File)
if ($reports.Count -eq 0) {
    throw "No Surefire XML reports were produced in $reportDirectory."
}

$suites = foreach ($report in $reports) {
    [xml]$xml = Get-Content -Path $report.FullName -Raw
    $xml.SelectNodes('//testsuite')
}
$testCount = 0
$failureCount = 0
$errorCount = 0
$skippedCount = 0
foreach ($suite in $suites) {
    $testCount += [int]$suite.GetAttribute('tests')
    $failureCount += [int]$suite.GetAttribute('failures')
    $errorCount += [int]$suite.GetAttribute('errors')
    $skippedCount += [int]$suite.GetAttribute('skipped')
}

$measurement = [ordered]@{
    startedAtUtc = $startedAt.ToString('o')
    elapsedSeconds = [Math]::Round($stopwatch.Elapsed.TotalSeconds, 3)
    outcome = if ($mavenExitCode -eq 0) { 'passed' } else { 'failed' }
    mavenExitCode = $mavenExitCode
    commit = (& git rev-parse HEAD).Trim()
    javaVersion = $javaVersionLine.Trim()
    mavenVersion = ($mavenVersionOutput | Select-Object -First 1).Trim()
    dockerEngineVersion = $dockerVersion.Trim()
    testSuites = $suites.Count
    tests = $testCount
    failures = $failureCount
    errors = $errorCount
    skipped = $skippedCount
}

$outputDirectory = Join-Path $PSScriptRoot '..\target\measurements'
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$outputPath = Join-Path $outputDirectory "test-suite-$($startedAt.ToString('yyyyMMdd-HHmmss')).json"
$measurement | ConvertTo-Json | Set-Content -Path $outputPath -Encoding utf8
Write-Output "Measurement written to $outputPath"
Write-Output ($measurement | ConvertTo-Json)

exit $mavenExitCode
