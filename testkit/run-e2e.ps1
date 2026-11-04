#requires -version 5.1
<#
.SYNOPSIS
    MultiLogin x Velocity 端到端登录验证脚本。

.DESCRIPTION
    1. 启动本地假 Yggdrasil（对 hasJoined 返回固定档案）
    2. 启动一台真实的 Velocity 测试代理，加载构建出来的 MultiLogin
    3. 用 LoginProbe 模拟 Minecraft 客户端完成一整套登录握手
    4. 断言代理返回的 LoginSuccess 里的 UUID/名字符合预期

.PARAMETER Jar
    要测试的 MultiLogin 插件 jar 路径

.EXAMPLE
    .\testkit\run-e2e.ps1 -Jar .\velocity\build\libs\MultiLogin-Velocity-xxx.jar
#>
param(
    [Parameter(Mandatory = $true)][string]$Jar,
    [string]$ServerDir = '',
    [int]$ProxyPort = 25588,
    [int]$YggPort = 18080,
    [string]$Username = 'TestPlayer',
    [string]$ExpectedUuid = '11111111-2222-3333-4444-555555555555',
    [int]$Protocol = 767,
    [string]$JavaHome = 'D:\jdk21_azlu'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$java = Join-Path $JavaHome 'bin\java.exe'
if ([string]::IsNullOrWhiteSpace($ServerDir)) { $ServerDir = Join-Path $root 'run\velocity-3.5.1' }
$serverDir = $ServerDir
$classes = Join-Path $root 'testkit\classes'
$logDir = Join-Path $root 'run\logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

if (-not (Test-Path $Jar)) { throw "找不到插件 jar: $Jar" }
if (-not (Test-Path $java)) { throw "找不到 java: $java" }
if (-not (Test-Path (Join-Path $classes 'LoginProbe.class'))) {
    throw "测试工具尚未编译，请先执行: javac -encoding UTF-8 -d testkit\classes testkit\*.java"
}

# 清理插件目录里旧版本的 jar，避免重复加载
Get-ChildItem (Join-Path $serverDir 'plugins') -Filter '*.jar' -ErrorAction SilentlyContinue | Remove-Item -Force
Copy-Item $Jar (Join-Path $serverDir 'plugins') -Force

$yggLog = Join-Path $logDir 'fake-yggdrasil.log'
$proxyLog = Join-Path $logDir 'velocity.log'
Remove-Item $yggLog, $proxyLog -ErrorAction SilentlyContinue

$ygg = $null
$proxy = $null
$result = 1
try {
    Write-Host "==> 启动假 Yggdrasil (127.0.0.1:$YggPort)"
    $ygg = Start-Process -FilePath $java -ArgumentList @(
        '-cp', $classes, 'FakeYggdrasil', "$YggPort",
        $ExpectedUuid.Replace('-', ''), $Username
    ) -RedirectStandardOutput $yggLog -RedirectStandardError "$yggLog.err" -PassThru -WindowStyle Hidden
    Start-Sleep -Seconds 2

    Write-Host "==> 启动 Velocity 测试代理 (127.0.0.1:$ProxyPort)"
    $proxy = Start-Process -FilePath $java -ArgumentList @('-jar', 'velocity.jar') -WorkingDirectory $serverDir `
        -RedirectStandardOutput $proxyLog -RedirectStandardError "$proxyLog.err" -PassThru -WindowStyle Hidden

    $deadline = (Get-Date).AddSeconds(90)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 1
        if ((Test-Path $proxyLog) -and ((Get-Content $proxyLog -Raw -ErrorAction SilentlyContinue) -match 'Listening on|Done \(')) { break }
        if ($proxy.HasExited) { throw "Velocity 提前退出，退出码 $($proxy.ExitCode)" }
    }
    if ($proxy.HasExited) { throw "Velocity 提前退出，退出码 $($proxy.ExitCode)" }
    Start-Sleep -Seconds 2

    Write-Host "==> 执行登录探测 (protocol=$Protocol user=$Username)"
    & $java -cp $classes LoginProbe $ProxyPort $Protocol $Username $ExpectedUuid
    $result = $LASTEXITCODE
} finally {
    Write-Host ""
    Write-Host "===== Velocity 日志（关键行） ====="
    if (Test-Path $proxyLog) {
        Get-Content $proxyLog | Select-String -Pattern 'multilogin|MultiLogin|ERROR|Exception|Listening|Done \(' |
            Select-Object -Last 60 | ForEach-Object { $_.Line }
    }
    Write-Host ""
    Write-Host "===== 假 Yggdrasil 日志 ====="
    if (Test-Path $yggLog) { Get-Content $yggLog | Select-Object -Last 20 }

    foreach ($p in @($proxy, $ygg)) {
        if ($p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
    }
}

Write-Host ""
if ($result -eq 0) { Write-Host "==> E2E RESULT: PASS" -ForegroundColor Green }
else { Write-Host "==> E2E RESULT: FAIL (exit=$result)" -ForegroundColor Red }
exit $result
