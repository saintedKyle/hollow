<#
    hollow build.

    Produces, in dist/:

      hollow-core.jar    the agent loaded into the game
      hollow.jar         the injector (GUI + CLI)
      example-agent.jar  proof-of-life payload
      runtime/           a jlink image, so the injector needs no installed JDK
      hollow.bat         launcher that uses runtime/

    Needs a JDK 17 or newer to build. Pass -Jdk to point at one.
#>
[CmdletBinding()]
param(
    [string]$Jdk,
    [switch]$NoRuntime
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$build = Join-Path $root 'build'
$dist = Join-Path $root 'dist'

function Resolve-Jdk {
    param([string]$Explicit)

    if ($Explicit) {
        if (Test-Path (Join-Path $Explicit 'bin\javac.exe')) { return $Explicit }
        throw "no javac.exe under $Explicit"
    }
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
        return $env:JAVA_HOME
    }
    $local = Get-ChildItem (Join-Path $root '.tools') -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'bin\javac.exe') } |
        Select-Object -First 1
    if ($local) { return $local.FullName }

    $onPath = Get-Command javac -ErrorAction SilentlyContinue
    if ($onPath) { return (Split-Path (Split-Path $onPath.Source -Parent) -Parent) }

    throw 'no JDK found. Pass -Jdk C:\path\to\jdk, set JAVA_HOME, or drop one in .tools\'
}

function Invoke-Tool {
    param([string]$Exe, [string[]]$Arguments, [string]$Label)
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Label failed with exit $LASTEXITCODE" }
}

function New-Jar {
    param([string]$Out, [string]$Manifest, [string]$Classes)
    $arguments = @('--create', '--file', $Out)
    if ($Manifest) { $arguments += @('--manifest', $Manifest) }
    $arguments += @('-C', $Classes, '.')
    Invoke-Tool (Join-Path $jdk 'bin\jar.exe') $arguments "jar $Out"
}

$jdk = Resolve-Jdk -Explicit $Jdk
$javac = Join-Path $jdk 'bin\javac.exe'
$java = Join-Path $jdk 'bin\java.exe'
$jlink = Join-Path $jdk 'bin\jlink.exe'

Write-Host "jdk    $jdk"
Write-Host "out    $dist"

if (Test-Path $build) { Remove-Item $build -Recurse -Force -ErrorAction SilentlyContinue }
New-Item -ItemType Directory -Force -Path $build | Out-Null
if (Test-Path $dist) {
    Get-ChildItem $dist -File | Remove-Item -Force -ErrorAction SilentlyContinue
    if (-not $NoRuntime) {
        Remove-Item (Join-Path $dist 'runtime') -Recurse -Force -ErrorAction SilentlyContinue
    }
}
New-Item -ItemType Directory -Force -Path $dist | Out-Null

function Compile {
    param([string]$SourceDir, [string]$OutDir, [string[]]$ClassPath = @())
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    $sources = Get-ChildItem -Path $SourceDir -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
    if (-not $sources) { throw "no sources under $SourceDir" }
    $arguments = @('-encoding', 'UTF-8', '-g',
                   '-Xlint:all,-serial,-this-escape', '-Werror',
                   '-d', $OutDir)
    if ($ClassPath.Count -gt 0) {
        $arguments += @('-cp', ($ClassPath -join ';'))
    }
    $arguments += $sources
    Invoke-Tool $javac $arguments "javac $SourceDir"
}

# ---- core -------------------------------------------------------------------
Write-Host 'compile hollow-core'
Compile (Join-Path $root 'src\core') (Join-Path $build 'core')

$coreManifest = Join-Path $build 'core.mf'
@(
    'Manifest-Version: 1.0'
    'Agent-Class: hollow.core.Bootstrap'
    'Can-Redefine-Classes: true'
    'Can-Retransform-Classes: true'
    ''
) -join "`r`n" | Set-Content -Path $coreManifest -Encoding ASCII -NoNewline
Add-Content -Path $coreManifest -Value '' -Encoding ASCII

New-Jar (Join-Path $dist 'hollow-core.jar') $coreManifest (Join-Path $build 'core')

# ---- injector ---------------------------------------------------------------
Write-Host 'compile hollow'
Compile (Join-Path $root 'src\injector') (Join-Path $build 'injector')

$injectorManifest = Join-Path $build 'injector.mf'
@(
    'Manifest-Version: 1.0'
    'Main-Class: hollow.injector.Main'
    ''
) -join "`r`n" | Set-Content -Path $injectorManifest -Encoding ASCII -NoNewline
Add-Content -Path $injectorManifest -Value '' -Encoding ASCII

New-Jar (Join-Path $dist 'hollow.jar') $injectorManifest (Join-Path $build 'injector')

# ---- example payload --------------------------------------------------------
Write-Host 'compile example-agent'
Compile (Join-Path $root 'src\example') (Join-Path $build 'example')

$exampleManifest = Join-Path $build 'example.mf'
@(
    'Manifest-Version: 1.0'
    'Agent-Class: hollow.example.DemoAgent'
    ''
) -join "`r`n" | Set-Content -Path $exampleManifest -Encoding ASCII -NoNewline
Add-Content -Path $exampleManifest -Value '' -Encoding ASCII

New-Jar (Join-Path $dist 'example-agent.jar') $exampleManifest (Join-Path $build 'example')

# ---- test fixtures ----------------------------------------------------------
Write-Host 'compile fixtures'
Compile (Join-Path $root 'src\fixtures') (Join-Path $build 'fixtures')

$probeManifest = Join-Path $build 'probe.mf'
@(
    'Manifest-Version: 1.0'
    'Agent-Class: hollow.probe.Probe'
    ''
) -join "`r`n" | Set-Content -Path $probeManifest -Encoding ASCII -NoNewline
Add-Content -Path $probeManifest -Value '' -Encoding ASCII

New-Jar (Join-Path $dist 'probe-agent.jar') $probeManifest (Join-Path $build 'fixtures')

# ---- runtime ----------------------------------------------------------------
if (-not $NoRuntime) {
    Write-Host 'jlink runtime'
    $runtime = Join-Path $dist 'runtime'
    $modules = 'java.base,java.desktop,jdk.attach,java.management,java.instrument'
    $arguments = @(
        '--add-modules', $modules,
        '--strip-debug', '--no-header-files', '--no-man-pages',
        '--compress=zip-6',
        '--output', $runtime
    )
    & $jlink @arguments
    if ($LASTEXITCODE -ne 0) {
        # older jlink builds do not know the zip-N form
        $arguments = @(
            '--add-modules', $modules,
            '--strip-debug', '--no-header-files', '--no-man-pages',
            '--compress=2',
            '--output', $runtime
        )
        Invoke-Tool $jlink $arguments 'jlink'
    }

    $launcher = Join-Path $dist 'hollow.bat'
    @(
        '@echo off'
        'setlocal'
        'set HERE=%~dp0'
        'if "%~1"=="" ('
        '  start "" "%HERE%runtime\bin\javaw.exe" -cp "%HERE%hollow.jar" hollow.injector.Main'
        ') else ('
        '  "%HERE%runtime\bin\java.exe" -cp "%HERE%hollow.jar" hollow.injector.Main %*'
        ')'
    ) -join "`r`n" | Set-Content -Path $launcher -Encoding ASCII
}

Write-Host ''
Write-Host 'built:'
Get-ChildItem $dist | ForEach-Object {
    if ($_.PSIsContainer) {
        Write-Host ("  {0,-22} {1}" -f ($_.Name + '/'), '')
    } else {
        Write-Host ("  {0,-22} {1,8:N1} KB" -f $_.Name, ($_.Length / 1KB))
    }
}
