<#
    Proves the quiet claim against a real log4j2.

    A target JVM logs twice a second to a file appender. hollow injects a
    payload that writes to System.out, System.err and log4j2. The test then
    checks that the harness lines are in the file and the payload lines are
    not, and that the payload's raw stdout never reached the process output.

    Needs log4j-api and log4j-core jars. Pass -Log4jDir, or let the script
    fetch them into %TEMP%\hollow-log4j.

    .\tools\test-log.ps1
    .\tools\test-log.ps1 -Log4jDir C:\jars
#>
[CmdletBinding()]
param(
    [string]$Jdk,
    [string]$Log4jDir,
    [string]$Version = '2.24.3',
    [switch]$NoBuild
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$dist = Join-Path $root 'dist'
$work = Join-Path $env:TEMP 'hollow-log4j'
$failures = 0

function Check {
    param([string]$Name, [bool]$Condition, [string]$Detail = '')
    if ($Condition) { Write-Host "   ok    $Name" }
    else { Write-Host "   FAIL  $Name $Detail"; $script:failures++ }
}

if (-not $NoBuild) {
    & (Join-Path $root 'build.ps1') -NoRuntime -Jdk $Jdk | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'build failed' }
}

$jdk = if ($Jdk) { $Jdk } elseif ($env:JAVA_HOME) { $env:JAVA_HOME } else {
    $found = Get-ChildItem (Join-Path $root '.tools') -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } | Select-Object -First 1
    if ($found) { $found.FullName } else { throw 'no JDK; pass -Jdk' }
}
$java = Join-Path $jdk 'bin\java.exe'

if (-not $Log4jDir) {
    $Log4jDir = $work
    New-Item -ItemType Directory -Force -Path $work | Out-Null
    $ProgressPreference = 'SilentlyContinue'
    foreach ($artifact in @('log4j-api', 'log4j-core')) {
        $target = Join-Path $work "$artifact-$Version.jar"
        if (-not (Test-Path $target)) {
            $url = "https://repo1.maven.org/maven2/org/apache/logging/log4j/$artifact/$Version/$artifact-$Version.jar"
            Write-Host "fetch  $artifact-$Version.jar"
            Invoke-WebRequest -Uri $url -OutFile $target -UseBasicParsing -TimeoutSec 120
        }
    }
}

$api = Join-Path $Log4jDir "log4j-api-$Version.jar"
$core = Join-Path $Log4jDir "log4j-core-$Version.jar"
if (-not (Test-Path $api) -or -not (Test-Path $core)) { throw "log4j jars not found in $Log4jDir" }

$cfg = Join-Path $work 'cfg'
New-Item -ItemType Directory -Force -Path $cfg | Out-Null
$logFile = Join-Path $work 'harness.log'
$marker = Join-Path $env:TEMP 'hollow-noisy.txt'
$stdout = Join-Path $work 'stdout.txt'
$stderr = Join-Path $work 'stderr.txt'
Remove-Item $logFile, $marker, $stdout, $stderr -Force -ErrorAction SilentlyContinue

@'
<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="ERROR">
  <Appenders>
    <File name="F" fileName="${sys:hollow.log}" append="false">
      <PatternLayout pattern="%d{HH:mm:ss.SSS} %-5p %c - %m%n"/>
    </File>
  </Appenders>
  <Loggers>
    <Root level="debug">
      <AppenderRef ref="F"/>
    </Root>
  </Loggers>
</Configuration>
'@ | Set-Content -Path (Join-Path $cfg 'log4j2.xml') -Encoding UTF8

$classpath = @(
    (Join-Path $dist 'probe-agent.jar'),
    $api, $core, $cfg
) -join ';'

Write-Host ''
Write-Host '== start logging target'
$harness = Start-Process -FilePath $java `
    -ArgumentList @("-Dhollow.log=$logFile", '-cp', $classpath, 'hollow.harness.LogHarness') `
    -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr
Start-Sleep -Milliseconds 1500
Check 'target alive' (-not $harness.HasExited)

try {
    Write-Host ''
    Write-Host '== inject noisy payload'
    & $java '-cp' (Join-Path $dist 'hollow.jar') 'hollow.injector.Main' `
        '--inject' '--pid' $harness.Id '--jar' (Join-Path $dist 'probe-agent.jar') '--class' 'hollow.probe.Noisy'
    Check 'inject exit 0' ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"
    Check 'payload ran' (Test-Path $marker)

    Start-Sleep -Milliseconds 1200

    $log = if (Test-Path $logFile) { Get-Content $logFile -Raw } else { '' }
    $out = if (Test-Path $stdout) { Get-Content $stdout -Raw } else { '' }
    $err = if (Test-Path $stderr) { Get-Content $stderr -Raw } else { '' }

    Write-Host ''
    Write-Host '== log assertions'
    Check 'harness lines present' ($log -match 'HARNESS-TICK-\d+')
    Check 'payload log4j line suppressed' (-not ($log -match 'HOLLOW-LOG4J-SHOULD-NOT-APPEAR'))
    Check 'payload stdout suppressed' (-not ($out -match 'HOLLOW-SYS-OUT-SHOULD-NOT-APPEAR'))
    Check 'payload stderr suppressed' (-not ($err -match 'HOLLOW-SYS-ERR-SHOULD-NOT-APPEAR'))
}
finally {
    if (-not $harness.HasExited) { Stop-Process -Id $harness.Id -Force -ErrorAction SilentlyContinue }
    Remove-Item $marker -Force -ErrorAction SilentlyContinue
}

Write-Host ''
if ($failures -eq 0) { Write-Host 'PASS'; exit 0 }
Write-Host "FAIL ($failures)"
exit 1
