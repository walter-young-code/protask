#!/usr/bin/env bash
# ProTask MCP 服务 - macOS/Linux 一键安装脚本
# 用法（交互）：    bash install.sh
# 用法（非交互）：  bash install.sh <BaseUrl> <Username> <Password>
set -euo pipefail

NODE_VERSION="v20.18.1"
INSTALL_DIR="${HOME}/.local/share/protask-mcp"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

step() { echo ""; echo "==> $1"; }

# ---------- 1. 收集配置 ----------
step "配置 ProTask 后端连接"
BASE_URL="${1:-}"
USERNAME="${2:-}"
PASSWORD="${3:-}"

if [[ -z "$BASE_URL" ]]; then read -r -p "后端地址（如 http://192.168.1.10:8000）: " BASE_URL; fi
BASE_URL="${BASE_URL%/}"
[[ -z "$BASE_URL" ]] && { echo "错误：后端地址不能为空" >&2; exit 1; }

if [[ -z "$USERNAME" ]]; then read -r -p "用户名: " USERNAME; fi
[[ -z "$USERNAME" ]] && { echo "错误：用户名不能为空" >&2; exit 1; }

if [[ -z "$PASSWORD" ]]; then read -r -s -p "密码: " PASSWORD; echo; fi

# ---------- 2. 校验登录 ----------
step "校验账号能否登录后端"
LOGIN_RESP="$(curl -fsS -X POST "$BASE_URL/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":$(printf '%s' "$USERNAME" | python3 -c 'import sys,json;print(json.dumps(sys.stdin.read()))'),\"password\":$(printf '%s' "$PASSWORD" | python3 -c 'import sys,json;print(json.dumps(sys.stdin.read()))')}")" \
  || { echo "错误：无法连接后端 $BASE_URL" >&2; exit 1; }

CODE="$(printf '%s' "$LOGIN_RESP" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("code"))')"
if [[ "$CODE" != "200" ]]; then
  echo "错误：登录失败：$LOGIN_RESP" >&2; exit 1
fi
echo "登录成功"

# ---------- 3. 准备安装目录 ----------
step "准备安装目录 $INSTALL_DIR"
mkdir -p "$INSTALL_DIR"
cp "$SCRIPT_DIR/protask-mcp.mjs" "$INSTALL_DIR/protask-mcp.mjs"

# ---------- 4. 定位 / 下载 Node 运行时 ----------
step "准备 Node 运行时"
NODE_BIN=""

if command -v node >/dev/null 2>&1; then
  VER="$(node -v | sed 's/^v//')"
  MAJOR="${VER%%.*}"
  if [[ "$MAJOR" -ge 18 ]]; then
    NODE_BIN="$(command -v node)"
    echo "检测到系统 Node $VER，直接复用：$NODE_BIN"
  else
    echo "系统 Node 版本过低（$VER），将下载便携版运行时"
  fi
fi

if [[ -z "$NODE_BIN" ]]; then
  NODE_BIN="$INSTALL_DIR/node/bin/node"
  if [[ ! -x "$NODE_BIN" ]]; then
    OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
    ARCH="$(uname -m)"
    case "$ARCH" in
      x86_64|amd64) NODE_ARCH="x64" ;;
      arm64|aarch64) NODE_ARCH="arm64" ;;
      *) echo "错误：不支持的架构 $ARCH" >&2; exit 1 ;;
    esac
    PKG="node-$NODE_VERSION-$OS-$NODE_ARCH"
    URL="https://nodejs.org/dist/$NODE_VERSION/$PKG.tar.gz"
    TMP="$(mktemp -d)"
    echo "下载便携版 Node（约 40MB）：$URL"
    curl -fsSL "$URL" -o "$TMP/$PKG.tar.gz"
    echo "解压 ..."
    tar -xzf "$TMP/$PKG.tar.gz" -C "$TMP"
    mkdir -p "$INSTALL_DIR/node"
    cp -R "$TMP/$PKG/." "$INSTALL_DIR/node/"
    rm -rf "$TMP"
  else
    echo "复用已下载的便携版 Node：$NODE_BIN"
  fi
fi

# ---------- 5. 写入凭证文件 ----------
step "写入凭证文件"
CREDS_PATH="$INSTALL_DIR/creds.json"
python3 - "$USERNAME" "$PASSWORD" "$BASE_URL" "$CREDS_PATH" <<'PY'
import sys, json
u, p, b, path = sys.argv[1:5]
with open(path, 'w', encoding='utf-8') as f:
    json.dump({"username": u, "password": p, "baseUrl": b}, f, ensure_ascii=False, indent=2)
PY
echo "已写入：$CREDS_PATH"

# ---------- 6. 冒烟测试：零依赖 JSON-RPC 握手 ----------
step "冒烟测试（拉起服务并完成 MCP 握手）"
PROBE_FILE="$INSTALL_DIR/.probe.mjs"
cat > "$PROBE_FILE" <<'EOF'
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
child.stderr.on('data', (d) => process.stderr.write(d));
child.on('exit', (code) => { if (code !== 0 && code !== null) { console.log('PROBE_EXIT_' + code); process.exit(code); } });
const req = JSON.stringify({
  jsonrpc: '2.0', id: 1, method: 'initialize',
  params: { protocolVersion: '2024-11-05', capabilities: {}, clientInfo: { name: 'probe', version: '1.0.0' } },
}) + '\n';
child.stdin.write(req);
setTimeout(() => { console.log('PROBE_TIMEOUT'); child.kill(); process.exit(3); }, 10000);
EOF

PROBE_OUT="$("$NODE_BIN" "$PROBE_FILE" "$NODE_BIN" "$INSTALL_DIR/protask-mcp.mjs" "$CREDS_PATH" 2>&1 || true)"
rm -f "$PROBE_FILE"
echo "$PROBE_OUT"
if echo "$PROBE_OUT" | grep -q 'PROBE_OK'; then
  echo "冒烟测试通过：服务可正常启动并响应 MCP 协议"
else
  echo "错误：冒烟测试未通过" >&2; exit 1
fi

# ---------- 7. 生成 MCP 客户端配置片段 ----------
step "生成 AI Agent 配置片段"
SNIPPET_PATH="$INSTALL_DIR/mcp-config-snippet.json"
python3 - "$NODE_BIN" "$INSTALL_DIR/protask-mcp.mjs" "$CREDS_PATH" "$SNIPPET_PATH" <<'PY'
import sys, json
node_bin, bundle, creds, path = sys.argv[1:5]
cfg = {"mcpServers": {"protask": {"command": node_bin, "args": [bundle], "env": {"PROTASK_CREDS_FILE": creds}}}}
with open(path, 'w', encoding='utf-8') as f:
    json.dump(cfg, f, ensure_ascii=False, indent=2)
print(json.dumps(cfg, ensure_ascii=False, indent=2))
PY

echo ""
echo "================ 安装完成 ================"
echo "安装目录：$INSTALL_DIR"
echo ""
echo "请把上方配置粘贴进你的 AI Agent / MCP 客户端。"
echo "配置片段已保存：$SNIPPET_PATH"
