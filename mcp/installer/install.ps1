# ProTask MCP 服务 - Windows 一键安装脚本
# 用法（交互）：    powershell -ExecutionPolicy Bypass -File install.ps1
# 用法（非交互）：  powershell -ExecutionPolicy Bypass -File install.ps1 `
#                     -BaseUrl http://server:8000 -Username admin -Password 'xxx'
param(
  [string]$BaseUrl,
  [string]$Username,
  [string]$Password
)

$ErrorActionPreference = 'Stop'
$NodeVersion = 'v20.18.1'
$InstallDir  = Join-Path $env:LOCALAPPDATA 'ProTaskMCP'

function Step([string]$msg) { Write-Host ""; Write-Host "==> $msg" -ForegroundColor Cyan }

# ---------- 1. 收集配置 ----------
Step "配置 ProTask 后端连接"
if (-not $BaseUrl)  { $BaseUrl  = Read-Host "后端地址（如 http://192.168.1.10:8000）" }
$BaseUrl = $BaseUrl.TrimEnd('/')
if (-not $BaseUrl) { throw "后端地址不能为空" }

if (-not $Username) { $Username = Read-Host "用户名" }
if (-not $Username) { throw "用户名不能为空" }

if (-not $Password) {
  $secure = Read-Host "密码" -AsSecureString
  $Password = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
    [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
}

# ---------- 2. 校验登录 ----------
Step "校验账号能否登录后端"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$loginBody = @{ username = $Username; password = $Password } | ConvertTo-Json
try {
  $resp = Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/login" -Method Post `
    -ContentType 'application/json; charset=utf-8' -Body $loginBody
} catch {
  throw "无法连接后端 $BaseUrl ：$($_.Exception.Message)"
}
if ($resp.code -ne 200) { throw "登录失败：$($resp.message)" }
Write-Host "登录成功，当前用户：$($resp.data.user.username)" -ForegroundColor Green

# ---------- 3. 准备安装目录 ----------
Step "准备安装目录 $InstallDir"
New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
# 若脚本已在目标目录内（如 Setup 向导式安装），跳过自拷贝
if ($PSScriptRoot.TrimEnd('\') -ne $InstallDir.TrimEnd('\')) {
  Copy-Item (Join-Path $PSScriptRoot 'protask-mcp.mjs') (Join-Path $InstallDir 'protask-mcp.mjs') -Force
}

# ---------- 4. 定位 / 下载 Node 运行时 ----------
Step "准备 Node 运行时"
$NodeExe = $null

$sysNode = Get-Command node -ErrorAction SilentlyContinue
if ($sysNode) {
  $ver = (& node -v) -replace '^v', ''
  $major = [int]($ver.Split('.')[0])
  if ($major -ge 18) {
    $NodeExe = $sysNode.Source
    Write-Host "检测到系统 Node $ver，直接复用：$NodeExe" -ForegroundColor Green
  } else {
    Write-Host "系统 Node 版本过低（$ver），将下载便携版运行时" -ForegroundColor Yellow
  }
}

if (-not $NodeExe) {
  $runtimeDir = Join-Path $InstallDir 'node'
  $NodeExe = Join-Path $runtimeDir 'node.exe'
  if (-not (Test-Path $NodeExe)) {
    $zipUrl  = "https://nodejs.org/dist/$NodeVersion/node-$NodeVersion-win-x64.zip"
    $zipFile = Join-Path $env:TEMP "node-$NodeVersion-win-x64.zip"
    Write-Host "下载便携版 Node（约 30MB）：$zipUrl"
    Invoke-WebRequest -Uri $zipUrl -OutFile $zipFile -UseBasicParsing
    Write-Host "解压 ..."
    $tmpExtract = Join-Path $env:TEMP "node-$NodeVersion-extract"
    if (Test-Path $tmpExtract) { Remove-Item $tmpExtract -Recurse -Force }
    Expand-Archive -Path $zipFile -DestinationPath $tmpExtract
    New-Item -ItemType Directory -Path $runtimeDir -Force | Out-Null
    Copy-Item (Join-Path $tmpExtract "node-$NodeVersion-win-x64\node.exe") $NodeExe -Force
    Remove-Item $tmpExtract -Recurse -Force
    Remove-Item $zipFile -Force
  } else {
    Write-Host "复用已下载的便携版 Node：$NodeExe" -ForegroundColor Green
  }
}

# ---------- 5. 写入凭证文件（无 BOM UTF-8，避免 JSON 解析失败） ----------
Step "写入凭证文件"
$credsPath = Join-Path $InstallDir 'creds.json'
$credsJson = @{ username = $Username; password = $Password; baseUrl = $BaseUrl } | ConvertTo-Json
[IO.File]::WriteAllText($credsPath, $credsJson, [Text.UTF8Encoding]::new($false))
Write-Host "已写入：$credsPath" -ForegroundColor Green

# ---------- 6. 冒烟测试：零依赖 JSON-RPC 握手，真实验证服务可启动 ----------
Step "冒烟测试（拉起服务并完成 MCP 握手）"
$probeJs = @'
import { spawn } from 'node:child_process';
const [nodeExe, bundle, creds] = process.argv.slice(2);
const child = spawn(nodeExe, [bundle], {
  env: { ...process.env, PROTASK_CREDS_FILE: creds },
  stdio: ['pipe', 'pipe', 'pipe'],
});
let buf = '';
child.stdout.on('data', (d) => {
  buf += d;
  let idx;
  while ((idx = buf.indexOf('\n')) >= 0) {
    const line = buf.slice(0, idx);
    buf = buf.slice(idx + 1);
    if (!line.trim()) continue;
    try {
      const msg = JSON.parse(line);
      if (msg.id === 1 && msg.result) {
        console.log('PROBE_OK server=' + (msg.result.serverInfo?.name || '?') + ' protocol=' + msg.result.protocolVersion);
        child.kill();
        process.exit(0);
      }
      if (msg.id === 1 && msg.error) {
        console.log('PROBE_ERR ' + JSON.stringify(msg.error));
        process.exit(1);
      }
    } catch {}
  }
});
child.stderr.on('data', () => {});
child.on('exit', (code) => { if (code !== 0 && code !== null) { console.log('PROBE_EXIT_' + code); process.exit(code); } });
const req = JSON.stringify({
  jsonrpc: '2.0', id: 1, method: 'initialize',
  params: { protocolVersion: '2024-11-05', capabilities: {}, clientInfo: { name: 'probe', version: '1.0.0' } },
}) + '\n';
child.stdin.write(req);
setTimeout(() => { console.log('PROBE_TIMEOUT'); child.kill(); process.exit(3); }, 10000);
'@
$probeFile = Join-Path $InstallDir '.probe.mjs'
[IO.File]::WriteAllText($probeFile, $probeJs, [Text.UTF8Encoding]::new($false))
try {
  # PS 5.1 下 ErrorActionPreference=Stop 会把原生命令 stderr 当终止错误，临时放宽
  $oldEAP = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  $probeOut = & $NodeExe $probeFile $NodeExe (Join-Path $InstallDir 'protask-mcp.mjs') $credsPath 2>&1
  $ErrorActionPreference = $oldEAP
  $probeText = ($probeOut | Out-String).Trim()
  Write-Host $probeText
  if ($probeText -match 'PROBE_OK') {
    Write-Host "冒烟测试通过：服务可正常启动并响应 MCP 协议" -ForegroundColor Green
  } else {
    throw "冒烟测试未通过：$probeText"
  }
} finally {
  $ErrorActionPreference = 'Stop'
  Remove-Item $probeFile -Force -ErrorAction SilentlyContinue
}

# ---------- 7. 生成 MCP 客户端配置片段 ----------
Step "生成 AI Agent 配置片段"
$snippet = [ordered]@{
  mcpServers = [ordered]@{
    protask = [ordered]@{
      command = $NodeExe
      args    = @((Join-Path $InstallDir 'protask-mcp.mjs'))
      env     = [ordered]@{ PROTASK_CREDS_FILE = $credsPath }
    }
  }
}

# ConvertTo-Json 缩进按绝对位置对齐，嵌套后格式很差；重排为标准 2 空格缩进
# 注意：必须声明 ValueFromPipeline，否则管道输入不会绑定到 $raw（会得到空串并写出空文件）
function Format-JsonIndent {
  param(
    [Parameter(ValueFromPipeline = $true)]
    [string]$raw
  )
  process {
    $sb = [Text.StringBuilder]::new()
    $depth = 0; $inStr = $false; $esc = $false; $bol = $true
    foreach ($c in $raw.ToCharArray()) {
      if ($esc) { [void]$sb.Append($c); $esc = $false; continue }
      if ($inStr) {
        [void]$sb.Append($c)
        if ($c -eq '\') { $esc = $true } elseif ($c -eq '"') { $inStr = $false }
        continue
      }
      if     ($c -eq '"') { if ($bol) { [void]$sb.Append('  ' * $depth); $bol = $false }; [void]$sb.Append($c); $inStr = $true }
      elseif ($c -eq '{' -or $c -eq '[') { [void]$sb.AppendLine($c.ToString()); $depth++; $bol = $true }
      elseif ($c -eq '}' -or $c -eq ']') { $depth--; if (-not $bol) { [void]$sb.AppendLine('') }; [void]$sb.Append('  ' * $depth); [void]$sb.Append($c.ToString()); $bol = $false }
      elseif ($c -eq ':') { [void]$sb.Append(': ') }
      elseif ($c -eq ',') { if ($bol) { [void]$sb.Append(',') } else { [void]$sb.AppendLine(',') }; $bol = $true }
      elseif ($c -match '\S') { if ($bol) { [void]$sb.Append('  ' * $depth); $bol = $false }; [void]$sb.Append($c) }
    }
    $sb.ToString().TrimEnd()
  }
}

$snippetJson = $snippet | ConvertTo-Json -Depth 6 | Format-JsonIndent
if (-not $snippetJson) { throw "配置片段生成失败：内容为空（Format-JsonIndent 未收到管道输入）" }
$snippetPath = Join-Path $InstallDir 'mcp-config-snippet.json'
[IO.File]::WriteAllText($snippetPath, $snippetJson, [Text.UTF8Encoding]::new($false))

Write-Host ""
Write-Host "================ 安装完成 ================" -ForegroundColor Green
Write-Host "安装目录：$InstallDir"
Write-Host ""
Write-Host "请把以下配置粘贴进你的 AI Agent / MCP 客户端：" -ForegroundColor Cyan
Write-Host $snippetJson
Write-Host ""
Write-Host "配置片段已保存：$snippetPath" -ForegroundColor Green
