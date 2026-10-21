# ProTask MCP 服务

将 ProTask 任务管理系统的能力以 [MCP](https://modelcontextprotocol.io/)（Model Context Protocol）服务的形式暴露给 AI Agent（stdio 传输）。

服务通过 ProTask 后端 REST API 工作，**所有写入操作都会经过写入规范守卫**，拒绝并纠正 AI Agent 产生的不合规数据，防止污染系统。

## 提供的工具

| 工具 | 类型 | 说明 |
| --- | --- | --- |
| `list_projects` | 只读 | 我的项目列表 |
| `get_project` | 只读 | 项目详情（含已解析的属性模板 cardProperties 与成员列表），写属性前必查 |
| `list_tasks` | 只读 | 项目任务列表 |
| `search_tasks` | 只读 | 全局搜索任务 |
| `get_dashboard` | 只读 | 跨项目仪表盘统计 |
| `create_project` | 写入 | 创建项目 |
| `update_project` | 写入 | 更新项目（读取现有值后合并，只覆盖传入字段） |
| `create_task` | 写入 | 创建任务（同名任务默认拒绝，防重复） |
| `update_task` | 写入 | 更新任务（properties 默认合并而非整体替换） |
| `add_comment` | 写入 | 给任务添加 Markdown 评论（支持 @提及成员） |
| `list_attachments` | 只读 | 任务附件分页列表（含公开访问 url 与大小） |
| `upload_attachment` | 写入 | 上传本地文件为任务附件（白名单 + 大小预检） |
| `download_attachment` | 只读 | 下载附件到本地文件（落盘，不解析内容） |
| `delete_task` | 危险 | 删除任务，必须显式传 `confirm: true` |
| `delete_project` | 危险 | 删除项目，必须显式传 `confirm: true` |

## 写入规范守卫（防污染数据）

所有写入工具在调用后端前强制执行以下规范，违规请求被拒绝并返回纠正指引：

1. **严格入参**：Zod schema 约束每个字段，删除类工具的 `confirm` 缺失直接拒绝。
2. **文本净化**：
   - 标题强制单行（去换行/控制字符、折叠空白），任务标题 ≤200、项目名 ≤100 字符；
   - 正文剥离 `<script>/<style>` 块与所有 HTML 标签，统一 LF 换行、折叠多余空行。
3. **属性模板校验**：任务 `properties` 必须逐项匹配项目 `cardProperties` 模板——
   - 模板外的属性 id 一律拒绝；
   - select/multiSelect 值必须命中已有选项（接受选项 id 或显示文本，自动映射为 id）；
   - date 归一化为 `YYYY-MM-DD`；number/checkbox 强制类型；member 必须是项目成员 userId。
4. **防重复创建**：项目内已存在同名任务时拒绝创建（可显式 `allowDuplicate: true` 覆盖）。
5. **安全合并**：`update_task` 默认与现有属性合并，避免误清空未传字段。
6. **危险操作门控**：删除必须显式 `confirm: true`。
7. **附件预检**：`upload_attachment` 上传前本地校验——
   - 扩展名必须在后端白名单内（图片 + pdf/doc/docx/xls/xlsx/ppt/pptx/txt/md/csv/zip/rar/7z）；
   - 文件必须存在、是普通文件且非空；单文件不超过 `PROTASK_MAX_FILE_SIZE`（默认 20MB，支持 `20MB`/`1024KB`/纯字节写法）；
   - 文件名会剥离路径分隔符与控制字符，防止用文件名伪造目录。

## 附件工具

| 工具 | 后端接口 | 权限 |
| --- | --- | --- |
| `list_attachments` | `GET /api/v1/tasks/{taskId}/attachments` | VIEW_PROJECT |
| `upload_attachment` | `POST /api/v1/tasks/{taskId}/attachments`（multipart） | MANAGE_PROJECT_CARDS |
| `download_attachment` | `GET /api/v1/tasks/{taskId}/attachments/{id}/download` | VIEW_PROJECT |

典型流程：`list_attachments` 拿到 `id` → 需要时 `download_attachment` 落盘；或 `upload_attachment` 传本地路径直接上传。

限制与行为：

- 单任务默认最多 20 个附件（后端 `protask.attachment.max-per-task`），超限由后端拒绝；
- 图片附件返回公开静态 `url`，可直接写进任务描述的 Markdown；非图片附件必须走鉴权下载接口；
- `download_attachment` 会自动创建父目录，同名文件直接覆盖；二进制不经过 MCP 文本通道，只返回本地路径与字节数；
- 下载只搬运文件，不解析内容；PDF/Word 等需 Agent 侧自行具备解析能力；
- 上传实现使用 Node 原生 `FormData`/`Blob`，**不手动设置 `Content-Type`**（boundary 由 fetch 生成），401 时复用与 JSON 请求相同的重登重试逻辑。

## 配置

### 凭证

支持两种方式（凭证文件优先）：

- **凭证文件**（推荐，避免 MCP 配置明文密码）：JSON 文件
  `{ "username": "...", "password": "...", "baseUrl": "http://localhost:8000" }`，
  通过环境变量 `PROTASK_CREDS_FILE` 指定路径。
- **环境变量**：`PROTASK_USERNAME`、`PROTASK_PASSWORD`，可选 `PROTASK_BASE_URL`（默认 `http://localhost:8000`）。
- **附件大小上限**：可选 `PROTASK_MAX_FILE_SIZE`（默认 `20MB`），与后端同名环境变量同源，仅用于上传前本地预检；未配置时按 20MB 处理。

### 构建

```powershell
cd mcp
npm install
npm run build   # 输出 dist/index.js
```

### 接入 MCP 客户端

以典型 MCP 客户端配置为例：

```json
{
  "mcpServers": {
    "protask": {
      "command": "node",
      "args": ["d:/ProTask/mcp/dist/index.js"],
      "env": {
        "PROTASK_CREDS_FILE": "d:/ProTask/mcp/creds.json"
      }
    }
  }
}
```

或直接使用环境变量：

```json
{
  "mcpServers": {
    "protask": {
      "command": "node",
      "args": ["d:/ProTask/mcp/dist/index.js"],
      "env": {
        "PROTASK_BASE_URL": "http://localhost:8000",
        "PROTASK_USERNAME": "your-username",
        "PROTASK_PASSWORD": "your-password"
      }
    }
  }
}
```

## 一键安装包（分发给普通用户）

面向不具备开发环境的用户，提供开箱即用的安装包：无需安装 Node/Docker，脚本自动检测或下载便携版运行时。

### 制作安装包

```powershell
npm run package    # 产出 installer/protask-mcp-installer.zip（约 150KB）
```

包内容：`protask-mcp.mjs`（esbuild 单文件产物，含全部依赖）+ `install.ps1` + `install.sh` + `README.txt`。

### 用户侧安装流程

1. 解压 zip
2. Windows 双击/运行 `install.ps1`；macOS/Linux 执行 `bash install.sh`
3. 脚本引导填写后端地址/账号/密码，自动完成：登录校验 → 安装到用户目录（Windows `%LOCALAPPDATA%\ProTaskMCP`，Unix `~/.local/share/protask-mcp`）→ 检测或下载 Node 运行时 → 写入 `creds.json` → MCP 握手冒烟测试
4. 脚本打印并保存 `mcp-config-snippet.json`，用户把其中的 `mcpServers` 配置粘贴进 AI Agent 即可

支持非交互安装（用于批量部署）：

```powershell
powershell -ExecutionPolicy Bypass -File install.ps1 -BaseUrl http://<后端地址>:8000 -Username <账号> -Password <密码>
```

```bash
bash install.sh http://<后端地址>:8000 <账号> <密码>
```

### Windows 向导式安装包（Setup，可选）

如需传统的“双击 exe 下一步下一步”安装体验，可用 Inno Setup 编译向导式安装包（英文界面）：

```powershell
# 需先安装编译器：winget install JRSoftware.InnoSetup
npm run package-setup   # 产出 installer/output/ProTaskMCP-Setup.exe（约 2MB）
```

用户双击 `ProTaskMCP-Setup.exe`，向导会引导填写后端地址/账号/密码（密码掩码显示），内部复用与 zip 版相同的安装核心（登录校验 → 文件部署 → Node 运行时检测下载 → 写入凭证 → 冒烟测试）。安装完成页可直接打开 `mcp-config-snippet.json`。无需管理员权限，按用户安装到 `%LOCALAPPDATA%\ProTaskMCP`。

### 卸载

- zip 版：删除安装目录即可，无注册表/系统服务残留
- Setup 版：Windows 设置 → 应用 → ProTask MCP Service → 卸载（会一并清理凭证与便携版运行时）

## Docker 运行

MCP 是 stdio 服务，由客户端按需拉起，因此容器化后仍以 `docker run -i` 短命进程方式运行，而不是常驻容器。

### 1. 构建镜像

`docker-compose.yml` 中已加入 `mcp` 服务（`tools` profile，默认 `up -d` 不会启动它）：

```powershell
docker compose build mcp    # 构建 protask-mcp 镜像
```

### 2. MCP 客户端配置（容器版）

后端整套跑在 Docker 时，容器通过 compose 内部网络的服务名 `server` 访问后端：

```json
{
  "mcpServers": {
    "protask": {
      "command": "docker",
      "args": [
        "run", "-i", "--rm",
        "--network", "protask_default",
        "-v", "d:/ProTask/mcp/creds.docker.json:/creds.json:ro",
        "-e", "PROTASK_CREDS_FILE=/creds.json",
        "protask-mcp"
      ]
    }
  }
}
```

其中 `creds.docker.json` 的 `baseUrl` 必须用内部网络地址（见仓库示例）：

```json
{ "username": "admin", "password": "...", "baseUrl": "http://server:8000" }
```

注意事项：

- 前提：`docker compose up -d` 已启动 mysql/server，容器需接入同一网络（默认 `protask_default`，项目目录名不同时用 `docker network ls` 确认）
- 若后端跑在宿主机（非容器），改用 `-e PROTASK_BASE_URL=http://host.docker.internal:8000` 并去掉 `--network`
- 也可直接用环境变量传凭证：把 `-v/-e PROTASK_CREDS_FILE` 换成 `-e PROTASK_USERNAME=... -e PROTASK_PASSWORD=...`

## Agent 使用建议

服务的 `instructions` 已内置写入规范提示，另建议：

1. 写任务属性前先 `get_project` 获取 `cardProperties` 模板与成员列表；
2. 创建前先 `list_tasks` 查重；
3. 更新只传变更字段；
4. 删除操作仅在用户明确要求时执行；
5. 处理附件时先 `list_attachments` 查已有文件，再决定上传或下载；单任务附件数量与大小上限由后端配额与 `PROTASK_MAX_FILE_SIZE` 共同约束。
