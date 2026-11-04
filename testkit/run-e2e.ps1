#requires -version 5.1
<#
.SYNOPSIS
    End-to-end login test for MultiLogin on Velocity.

.DESCRIPTION
    1. Starts a fake Yggdrasil (returns a fixed profile from /session/minecraft/hasJoined)
    2. Starts a real Velocity proxy with the plugin under test
    3. Runs LoginProbe, a minimal Minecraft client that performs the full login handshake
    4. Asserts the UUID/name carried by LoginSuccess

    NOTE: keep this file pure ASCII. Windows PowerShell 5.1 decodes BOM-less files as
    ANSI/GBK, and non-ASCII comments then break the parser. Chinese docs live in PORTING.md.

.PARAMETER Jar
    Path to the MultiLogin plugin jar under test.

.EXAMPLE
    .\testkit\run-e2e.ps1 -Jar .\dist\MultiLogin-Velocity-3.5.1.jar -ServerDir .\run\velocity-3.5.1
#>
param(
    [Parameter(Mandatory = $true)][string]$Jar,
    [string]$ServerDir = '',
    [int]$ProxyPort = 25588,
    [int]$YggPort = 18080,
    [string]$Username = 'TestPlayer',
    [string]$ExpectedUuid = '11111111-2222-3333-4444-555555555555',
    [int]$Protocol = 767,
    [string]$JavaHome = 'D:\jdk21_azlu',
    # Comma separated path prefixes; a hit makes the fake Yggdrasil answer 204.
    # Used to verify multi-service fallback and the OFFICIAL code path.
    [string]$DenyPaths = '',
    # When set, overrides the player name returned by the fake Yggdrasil.
    [string]$ForcedName = ''
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$java = Join-Path $JavaHome 'bin\java.exe'
if ([string]::IsNullOrWhiteSpace($ServerDir)) { $ServerDir = Join-Path $root 'run\velocity-3.5.1' }
$serverDir = $ServerDir
$classes = Join-Path $root 'testkit\classes'
$logDir = Join-Path $root 'run\logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

if (-not (Test-Path $Jar)) { throw "Plugin jar not found: $Jar" }
if (-not (Test-Path $java)) { throw "java not found: $java" }
if (-not (Test-Path (Join-Path $classes 'LoginProbe.class'))) {
    throw "Test kit is not compiled. Run: javac -encoding UTF-8 -d testkit\classes testkit\*.java"
}

# Drop any previous plugin jar so the proxy cannot pick up a stale build.
Get-ChildItem (Join-Path $serverDir 'plugins') -Filter '*.jar' -ErrorAction SilentlyContinue | Remove-Item -Force
Copy-Item $Jar (Join-Path $serverDir 'plugins') -Force

$yggLog = Join-Path $logDir 'fake-yggdrasil.log'
$proxyLog = Join-Path $logDir 'velocity.log'
Remove-Item $yggLog, $proxyLog -ErrorAction SilentlyContinue

$ygg = $null
$proxy = $null
$result = 1
try {
    Write-Host "==> starting fake Yggdrasil (127.0.0.1:$YggPort)"
    # Start-Process rejects empty strings in -ArgumentList, so "-" is used as an empty placeholder.
    $denyArg = if ([string]::IsNullOrEmpty($DenyPaths)) { '-' } else { $DenyPaths }
    $nameArg = if ([string]::IsNullOrEmpty($ForcedName)) { '-' } else { $ForcedName }
    $yggArgs = @(
        '-cp', $classes, 'FakeYggdrasil', "$YggPort",
        $ExpectedUuid.Replace('-', ''), $Username, $denyArg, $nameArg
    )
    $ygg = Start-Process -FilePath $java -ArgumentList $yggArgs `
        -RedirectStandardOutput $yggLog -RedirectStandardError "$yggLog.err" -PassThru -WindowStyle Hidden
    Start-Sleep -Seconds 2

    Write-Host "==> starting Velocity (127.0.0.1:$ProxyPort)"
    $proxy = Start-Process -FilePath $java -ArgumentList @('-jar', 'velocity.jar') -WorkingDirectory $serverDir `
        -RedirectStandardOutput $proxyLog -RedirectStandardError "$proxyLog.err" -PassThru -WindowStyle Hidden

    $deadline = (Get-Date).AddSeconds(90)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 1
        if ((Test-Path $proxyLog) -and ((Get-Content $proxyLog -Raw -ErrorAction SilentlyContinue) -match 'Listening on|Done \(')) { break }
        if ($proxy.HasExited) { throw "Velocity exited early, code $($proxy.ExitCode)" }
    }
    if ($proxy.HasExited) { throw "Velocity exited early, code $($proxy.ExitCode)" }
    Start-Sleep -Seconds 2

    Write-Host "==> running login probe (protocol=$Protocol user=$Username)"
    & $java -cp $classes LoginProbe $ProxyPort $Protocol $Username $ExpectedUuid
    $result = $LASTEXITCODE
} finally {
    Write-Host ""
    Write-Host "===== Velocity log (key lines) ====="
    if (Test-Path $proxyLog) {
        Get-Content $proxyLog | Select-String -Pattern 'multilogin|MultiLogin|ERROR|Exception|Listening|Done \(' |
            Select-Object -Last 60 | ForEach-Object { $_.Line }
    }
    Write-Host ""
    Write-Host "===== fake Yggdrasil log ====="
    if (Test-Path $yggLog) { Get-Content $yggLog | Select-Object -Last 20 }

    foreach ($p in @($proxy, $ygg)) {
        if ($p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
    }
}

Write-Host ""
if ($result -eq 0) { Write-Host "==> E2E RESULT: PASS" -ForegroundColor Green }
else { Write-Host "==> E2E RESULT: FAIL (exit=$result)" -ForegroundColor Red }
exit $result
