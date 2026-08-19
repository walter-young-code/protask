# ProTask MCP 一键安装包构建脚本
# 产出：installer/protask-mcp-installer.zip
# 内容：protask-mcp.mjs（单文件服务）+ install.ps1 / install.sh + README.txt
#
# 用法：npm run package   （或 powershell -File installer/build-package.ps1）

$ErrorActionPreference = 'Stop'

$mcpRoot   = Split-Path -Parent $PSScriptRoot          # mcp/
$installer = $PSScriptRoot                               # mcp/installer/
$staging   = Join-Path $installer 'pkg'
$zipPath   = Join-Path $installer 'protask-mcp-installer.zip'
$bundle    = Join-Path $mcpRoot 'dist-bundle\protask-mcp.mjs'

Write-Host "[1/5] 生成单文件 bundle ..." -ForegroundColor Cyan
Push-Location $mcpRoot
try {
  if (-not (Test-Path $bundle)) { npm run bundle | Out-Null }
  if (-not (Test-Path $bundle)) { throw "bundle 生成失败：$bundle 不存在" }
} finally {
  Pop-Location
}

Write-Host "[2/5] 准备暂存目录 ..." -ForegroundColor Cyan
if (Test-Path $staging) { Remove-Item $staging -Recurse -Force }
New-Item -ItemType Directory -Path $staging | Out-Null

Write-Host "[3/5] 拷贝产物 ..." -ForegroundColor Cyan
Copy-Item $bundle (Join-Path $staging 'protask-mcp.mjs')
Copy-Item (Join-Path $installer 'install.ps1') $staging
Copy-Item (Join-Path $installer 'install.sh')  $staging

Write-Host "[4/5] 生成用户说明 README.txt ..." -ForegroundColor Cyan
$readme = @'
==========================================================
  ProTask MCP 服务 一键安装包
==========================================================

【安装】
  - Windows      ：双击运行 install.ps1
                   （若提示脚本策略，先执行：
                    powershell -ExecutionPolicy Bypass -File install.ps1）
  - macOS/Linux ：终端执行  bash install.sh

安装脚本会自动完成：
  1. 检测或下载 Node 运行时（无需你手动安装 Node）
  2. 将服务安装到本机用户目录
  3. 引导你填写后端地址 / 账号 / 密码，并校验登录
  4. 生成凭证文件 creds.json 与 MCP 客户端配置片段

【配置 AI Agent】
安装完成后，脚本会打印一段 mcpServers 配置 JSON，把它粘贴进
你的 AI Agent / MCP 客户端配置即可。配置片段也会保存在安装目录的
mcp-config-snippet.json 中，随时可查。

【卸载】
删除安装目录即可：
  - Windows      ：%LOCALAPPDATA%\ProTaskMCP
  - macOS/Linux ：~/.local/share/protask-mcp

【说明】
本服务以 stdio 方式由 AI Agent 按需拉起，非常驻进程，
不占用端口、不随系统启动。所有写入均经过格式规范守卫，
防止产生污染数据。
'@
Set-Content -Path (Join-Path $staging 'README.txt') -Value $readme -Encoding UTF8

Write-Host "[5/5] 打包 zip ..." -ForegroundColor Cyan
if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
Compress-Archive -Path (Join-Path $staging '*') -DestinationPath $zipPath

Remove-Item $staging -Recurse -Force

$size = [math]::Round((Get-Item $zipPath).Length / 1KB, 1)
Write-Host ""
Write-Host "完成：$zipPath （$size KB）" -ForegroundColor Green
Write-Host "分发该 zip 给用户，解压后运行对应平台的 install 脚本即可。"
