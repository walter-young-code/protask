# ProTask - 团队任务管理系统

前后端分离的任务管理 Web 应用。前端 Vue 3（组合式 API），后端 Java Spring Boot 3 单体架构，MySQL 数据库。支持中英双语、暗色模式、MCP AI 集成。

## 功能概览

- **项目管理**：创建 / 归档 / 复制项目，项目分类，项目模板库，从模板创建项目
- **任务管理**：增删改查，状态流转（todo → in_progress → done），全局搜索，动态卡片属性
- **多视图**：看板视图、表格视图、日历视图、画廊视图，可设置项目默认视图
- **仪表盘**：跨项目任务统计与分类汇总
- **协作**：项目成员管理（owner / admin / member），任务评论（支持 @提及），站内通知
- **附件与图片**：任务附件上传 / 下载，描述区 Markdown 图片嵌入
- **项目分享**：生成只读分享链接，访客无需登录即可查看
- **日历订阅**：生成 ICS 订阅地址，在 Outlook / Apple Calendar 等外部日历中同步任务
- **团队管理**：创建团队、分配成员，项目关联团队
- **邀请注册**：生成一次性邀请链接，关闭公开注册入口
- **管理后台**：用户管理、团队管理、模板管理、归档项目管理、站点设置（Logo / 站点名称）
- **暗色模式**：语义 CSS 变量 + Element Plus 暗色变量，顶栏一键切换
- **多语言**：前后端均支持中英文切换（vue-i18n + 后端 i18n properties）
- **MCP 服务**：将项目 / 任务管理能力暴露给 AI Agent，带写入规范守卫防数据污染

## 技术栈

| 层 | 技术 |
| --- | --- |
| 前端 | Vue 3.5 + TypeScript + Vite 8 + Element Plus + Pinia + Vue Router + Axios |
| 前端测试 | Vitest |
| 后端 | Java 17 + Spring Boot 3.3 + MyBatis-Plus 3.5.9 + Sa-Token 1.39（JWT） |
| 数据库 | MySQL 8.0（Docker 容器），Flyway 版本迁移 |
| MCP 服务 | Node.js 20+ + MCP SDK + Zod（stdio 传输） |
| 代码规范 | ESLint (flat config) + Prettier |

## 目录结构

```
ProTask/
├── server/                 # Spring Boot 后端（端口 8000）
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/protask/
│       │   ├── common/     # 统一响应 Result、异常处理
│       │   ├── config/     # Sa-Token / MyBatis-Plus / 密码 / 初始化数据
│       │   ├── controller/ # 控制层（RESTful API）
│       │   ├── service/    # 业务逻辑层
│       │   ├── mapper/     # 数据访问层（MyBatis-Plus）
│       │   ├── entity/     # 数据库实体
│       │   └── dto/        # 请求/响应对象
│       └── resources/
│           ├── application.yml
│           ├── i18n/messages*.properties      # 后端中英文错误消息
│           ├── ValidationMessages*.properties # 后端中英文校验消息
│           └── db/migration/  # Flyway SQL 脚本
├── webapp/                 # Vue 3 前端（端口 3000）
│   └── src/
│       ├── api/            # Axios 封装 + 接口模块
│       ├── components/     # 通用组件 + 视图组件（看板/表格/日历/画廊）
│       ├── i18n/           # vue-i18n 实例（语言持久化/探测）
│       ├── layouts/        # 主布局 + 管理后台布局
│       ├── locales/        # 中英文语言包（zh-CN/en-US）
│       ├── router/         # 路由 + 登录守卫 + 管理员守卫
│       ├── stores/         # Pinia 全局状态（用户/项目/分类/通知/分享/站点）
│       ├── styles/         # 全局样式（含暗色模式 CSS 变量）
│       ├── types/          # TS 类型定义
│       ├── utils/          # 状态流转规则、权限、主题切换等工具
│       └── views/          # 页面（登录/注册/仪表盘/项目详情/管理后台）
├── mcp/                    # MCP 服务（AI Agent 集成）
│   ├── src/                # TypeScript 源码
│   ├── installer/          # Inno Setup 安装包构建脚本
│   └── dist-bundle/        # esbuild 单文件产物
└── docker-compose.yml      # MySQL + Server + Webapp + MCP 容器编排
```

## 多语言支持（中/英）

- **前端**：vue-i18n，语言包在 `webapp/src/locales/`（zh-CN / en-US）。顶栏右侧可切换语言，选择持久化到 `localStorage`（key `protask_locale`），未选择时按浏览器语言自动探测；Element Plus 组件内置文案（日期选择器、分页等）随语言同步切换。
- **后端**：错误消息与参数校验消息按请求头 `Accept-Language` 返回对应语言（默认中文）。资源文件：`server/src/main/resources/i18n/messages*.properties`（业务错误）与 `ValidationMessages*.properties`（DTO 校验）。前端 Axios 已自动携带当前语言。
- 新增文案时：前端在两个语言包中同步补充 key；后端在两个 properties 中同步补充，DTO 注解用 `{key}` 引用、业务异常用 `new BusinessException(ResultCode.XXX, "key", args...)` 抛出。

## 环境要求

- JDK 17+
- Maven 3.9+
- Node.js 20.19+
- Docker（用于运行 MySQL）

## 快速开始

### 1. 启动 MySQL

```powershell
docker compose up -d mysql
```

连接信息：`127.0.0.1:3306`，root/`protask-strong-pw-change-me`（由 `PROTASK_DB_ROOT_PASSWORD` 覆盖），数据库 `protask`（自动创建）。

### 2. 启动后端（端口 8000）

```powershell
# 本地直跑时需将数据库密码注入环境变量（与上方容器密码一致）
$env:PROTASK_DB_PASSWORD="protask-strong-pw-change-me"
mvn -f server/pom.xml spring-boot:run
```

首次启动 Flyway 自动建表，并初始化默认管理员账号：**admin / Test@12345**（仅开发用途，请及时修改）。

### 3. 启动前端（端口 3000）

```powershell
cd webapp
npm install
npm run dev
```

浏览器访问 http://localhost:3000 ，Vite 已将 `/api` 代理到后端 8000 端口。

### 4. Docker 全量部署（可选）

```powershell
docker compose build
docker compose up -d
```

将依次构建并启动 MySQL、Server（8000）、Webapp（3000 nginx）三个服务。MCP 服务在 `tools` profile 下，默认不启动。

## 常用命令

| 命令 | 说明 |
| --- | --- |
| `cd webapp && npm run build` | 前端类型检查 + 生产构建（输出 webapp/dist） |
| `cd webapp && npm run test` | Vitest 单元测试 |
| `cd webapp && npm run lint` | ESLint 检查并自动修复 |
| `cd webapp && npm run format` | Prettier 格式化 |
| `mvn -f server/pom.xml test` | 后端测试 |
| `mvn -f server/pom.xml package` | 后端打包为 JAR（server/target/protask-server.jar） |
| `cd mcp && npm run build` | 构建 MCP 服务 |
| `cd mcp && npm run bundle` | esbuild 打包为单文件（mcp/dist-bundle/protask-mcp.mjs） |

## API 概览

统一响应结构：`{ "code": 200, "message": "...", "data": ... }`；认证方式为请求头 `Authorization: Bearer <JWT>`。

### 认证与用户

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/v1/auth/register | 注册 |
| POST | /api/v1/auth/login | 登录，返回 JWT |
| POST | /api/v1/auth/logout | 登出 |
| GET | /api/v1/auth/me | 当前用户信息 |
| POST | /api/v1/auth/change-password | 修改密码 |
| POST | /api/v1/auth/invites | 生成一次性邀请链接 |
| GET | /api/v1/auth/invites/validate | 校验邀请链接有效性 |
| GET | /api/v1/users | 用户列表（任务分配用） |

### 项目

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET/POST | /api/v1/projects | 我的项目列表 / 创建项目 |
| GET/PUT/DELETE | /api/v1/projects/{id} | 项目详情 / 更新 / 删除（软删除） |
| POST | /api/v1/projects/{id}/archive | 归档项目 |
| POST | /api/v1/projects/{id}/copy | 复制项目 |
| POST | /api/v1/projects/{id}/to-template | 项目转为模板 |
| POST | /api/v1/projects/from-template | 从模板创建项目 |
| PUT | /api/v1/projects/{id}/default-view | 设置默认视图 |
| PUT | /api/v1/projects/{id}/card-properties | 更新卡片动态属性模板 |

### 项目成员

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | /api/v1/projects/{id}/members | 成员列表 |
| POST | /api/v1/projects/{id}/members | 添加成员 |
| PUT | /api/v1/projects/{id}/members | 变更成员角色 |
| DELETE | /api/v1/projects/{id}/members/{userId} | 移除成员 |

### 项目分享与日历订阅

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST/GET/DELETE | /api/v1/projects/{id}/share | 开启 / 查询 / 关闭项目分享 |
| GET | /api/v1/projects/share/{shareId} | 通过分享链接解析项目 |
| POST | /api/v1/projects/{id}/calendar-subscription | 开启日历订阅 |
| POST | /api/v1/projects/{id}/calendar-subscription/regenerate | 重新生成订阅凭证 |
| GET/DELETE | /api/v1/projects/{id}/calendar-subscription | 查询 / 关闭日历订阅 |
| GET | /api/v1/public/calendars/{token}.ics | ICS 日历文件（公开） |

### 任务与块

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET/POST | /api/v1/projects/{projectId}/tasks | 任务列表 / 创建任务 |
| PUT | /api/v1/tasks/{id} | 更新任务 |
| DELETE | /api/v1/tasks/{id} | 删除任务 |
| GET | /api/v1/tasks/search | 全局搜索任务 |
| GET | /api/v1/dashboard | 跨项目仪表盘统计 |
| GET/POST | /api/v1/projects/{projectId}/blocks | 块列表 / 创建块 |
| PATCH | /api/v1/blocks/{id} | 局部更新块 |
| DELETE | /api/v1/blocks/{id} | 删除块 |

### 附件、通知、分类、团队

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET/POST | /api/v1/tasks/{taskId}/attachments | 附件列表 / 上传附件 |
| GET | /api/v1/attachments/{id}/download | 下载附件 |
| DELETE | /api/v1/attachments/{id} | 删除附件 |
| POST | /api/v1/upload/image | 上传图片（描述区嵌入） |
| GET | /api/v1/notifications | 通知列表 |
| GET | /api/v1/notifications/unread-count | 未读通知数 |
| PUT | /api/v1/notifications/{id}/read | 标记通知已读 |
| PUT | /api/v1/notifications/read-all | 全部标记已读 |
| GET/POST | /api/v1/categories | 分类列表 / 新建分类 |
| PUT/DELETE | /api/v1/categories/{id} | 修改 / 删除分类 |
| GET/POST | /api/v1/teams | 团队列表 / 创建团队 |
| PUT | /api/v1/teams/{id} | 重命名团队 |
| GET | /api/v1/teams/all | 全量团队列表（下拉选择用） |

### 模板与管理后台

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | /api/v1/templates | 模板库列表 |
| GET/PUT/DELETE | /api/v1/admin/templates/{id} | 管理模板 |
| GET/POST/PUT/DELETE | /api/v1/admin/users | 管理用户 |
| PUT | /api/v1/admin/users/{id}/password | 重置用户密码 |
| GET | /api/v1/admin/projects/archived | 归档项目列表 |
| POST | /api/v1/admin/projects/{id}/restore | 恢复归档项目 |
| DELETE | /api/v1/admin/projects/{id} | 删除归档项目 |
| DELETE | /api/v1/admin/projects/deleted | 物理清理已删项目 |
| GET/PUT | /api/v1/settings | 站点公开设置 / 更新站点设置 |

### 任务状态流转规则

- `todo`（待处理）→ `in_progress` / `done`
- `in_progress`（进行中）→ `todo` / `done`
- `done`（已完成）→ `in_progress`（不可直接退回 todo）

前后端均实现该规则校验（后端 `TaskService.TRANSITIONS`，前端 `src/utils/meta.ts`）。

## 数据库设计

| 表 | 说明 |
| --- | --- |
| `sys_user` | 用户表：用户名唯一，BCrypt 加密密码，角色 admin/user，支持昵称/姓名/注销标记 |
| `team` | 团队表：团队名称，关联用户 |
| `project` | 项目表：负责人 owner_id，状态 active/archived，分类，团队关联，分享/模板/日历订阅凭证 |
| `project_member` | 项目成员表：多对多关系，项目角色 owner/admin/member |
| `block` | 统一块模型：card（任务卡）/ view（视图）/ text（文本）/ comment（评论） |
| `attachment` | 附件表：关联任务，文件名/路径/大小 |
| `upload_image` | 上传图片表：描述区嵌入图片，归属追踪 |
| `notification` | 通知表：站内消息，已读状态 |
| `project_category` | 项目分类表：用户级分类 |
| `invite_token` | 邀请令牌表：一次性注册邀请，过期控制 |
| `site_setting` | 站点设置表：Logo、站点名称等全局配置 |

结构变更请新增 Flyway 脚本 `server/src/main/resources/db/migration/V{n}__xxx.sql`。

## MCP 服务

`mcp/` 目录提供 [MCP](https://modelcontextprotocol.io/) 服务，将 ProTask 的项目 / 任务管理能力暴露给 AI Agent（stdio 传输）。所有写入操作经过规范守卫，拒绝不合规数据防止污染系统。

支持 Docker 容器化部署（`docker compose build mcp`），也提供 Windows Inno Setup 安装包（`cd mcp && npm run package-setup`）。详见 [mcp/README.md](mcp/README.md)。


##  web 订阅问题和复制失败问题

这个问题和之前的剪贴板复制失败是**同一个根因**：你的 ProTask 目前通过 **HTTP** 提供服务（nginx 只监听了 80 端口），生成的订阅链接也是 `http://...`。

**Outlook on the web (outlook.office.com) 拒绝订阅 HTTP 链接**，这是微软的安全策略 — 它要求日历订阅 URL 必须是 HTTPS。

具体表现：
- **通过链接从 web 订阅** → 失败（Outlook 服务器拒绝拉取 HTTP 源）
- **下载 ICS 文件再手动上传** → 成功（本地文件不受 HTTPS 限制）
- **复制按钮报错** → 失败（`navigator.clipboard` 要求 HTTPS 安全上下文）

三个问题全部指向同一个解决方案：**nginx 配置 HTTPS**。

等 SSL 证书申请好后，需要做以下改动：

| 文件 | 改动 |
|---|---|
| `nginx.conf` | 添加 443 监听 + SSL 证书配置 + HTTP→HTTPS 重定向 |
| `Dockerfile` | 暴露 443 端口 |
| `docker-compose.yml` | 映射 443 端口 + 挂载证书目录 |

配置好 HTTPS 后，三个问题会同时解决：
1. Outlook 日历订阅链接变为 `https://...`，可正常订阅
2. 剪贴板 `navigator.clipboard` 在 HTTPS 下正常工作
3. 订阅 URL 由后端基于请求 Origin 生成，自动变为 HTTPS