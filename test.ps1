<#
    hollow end-to-end test.

    Starts a throwaway JVM, attaches hollow-core, loads a headless probe,
    checks the marker, ejects, checks the marker is gone, then kills the JVM.

    .\test.ps1            build first, then run
    .\test.ps1 -NoBuild   reuse dist/ as it stands
#>
[CmdletBinding()]
param(
    [string]$Jdk,
    [switch]$NoBuild
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$dist = Join-Path $root 'dist'
$failures = 0

function Step {
    param([string]$Name)
    Write-Host ""
    Write-Host "== $Name"
}

function Check {
    param([string]$Name, [bool]$Condition, [string]$Detail = '')
    if ($Condition) {
        Write-Host "   ok    $Name"
    } else {
        Write-Host "   FAIL  $Name $Detail"
        $script:failures++
    }
}

if (-not $NoBuild) {
    & (Join-Path $root 'build.ps1') -NoRuntime -Jdk $Jdk
    if ($LASTEXITCODE -ne 0) { throw 'build failed' }
}

$jdk = if ($Jdk) { $Jdk } elseif ($env:JAVA_HOME) { $env:JAVA_HOME } else {
    $found = Get-ChildItem (Join-Path $root '.tools') -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } | Select-Object -First 1
    if ($found) { $found.FullName } else { throw 'no JDK; pass -Jdk' }
}

$java = Join-Path $jdk 'bin\java.exe'
$marker = Join-Path $env:TEMP 'hollow-probe.txt'
Remove-Item $marker -Force -ErrorAction SilentlyContinue

Step 'start target JVM'
$harness = Start-Process -FilePath $java `
    -ArgumentList @('-cp', (Join-Path $dist 'probe-agent.jar'), 'hollow.harness.Sleep') `
    -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $env:TEMP 'hollow-harness.out')
Start-Sleep -Milliseconds 1200
Check 'target alive' (-not $harness.HasExited) "exit=$($harness.ExitCode)"
Check 'target is a java process' ($harness.ProcessName -like 'java*') $harness.ProcessName

try {
    Step 'attach and load'
    & $java '-cp' (Join-Path $dist 'hollow.jar') 'hollow.injector.Main' `
        '--inject' '--pid' $harness.Id '--jar' (Join-Path $dist 'probe-agent.jar') '--args' 'hello'
    Check 'inject exit 0' ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"
    Check 'marker written' (Test-Path $marker)
    if (Test-Path $marker) {
        $body = Get-Content $marker -Raw
        Check 'marker has the right pid' ($body -match "pid=$($harness.Id)")
        Check 'agent args arrived' ($body -match 'args=hello')
    }

    Step 'second load reuses the channel'
    & $java '-cp' (Join-Path $dist 'hollow.jar') 'hollow.injector.Main' `
        '--inject' '--pid' $harness.Id '--jar' (Join-Path $dist 'probe-agent.jar')
    Check 're-inject exit 0' ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"

    Step 'eject'
    & $java '-cp' (Join-Path $dist 'hollow.jar') 'hollow.injector.Main' '--eject' '--pid' $harness.Id
    Check 'eject exit 0' ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"
    Check 'marker removed by unload hook' (-not (Test-Path $marker))
}
finally {
    Step 'teardown'
    if (-not $harness.HasExited) {
        Stop-Process -Id $harness.Id -Force -ErrorAction SilentlyContinue
        Write-Host '   stopped target'
    }
    Remove-Item $marker -Force -ErrorAction SilentlyContinue
}

Write-Host ''
if ($failures -eq 0) {
    Write-Host 'PASS'
    exit 0
}
Write-Host "FAIL ($failures)"
exit 1
