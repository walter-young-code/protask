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
- **管理后台**：用户管理、团队管理、模板管理、归档项目管理、存储管理、站点设置（Logo / 站点名称）
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
│       │   ├── storage/    # 文件存储抽象（FileStorage / 本地磁盘实现）
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

- JDK 17（建议；`server/Dockerfile` 的构建镜像即 temurin-17）
- Maven 3.9+
- Node.js 20.19+
- Docker（用于运行 MySQL）

> JDK 23 及以上本机直跑需额外参数：javac 从 23 起默认不再运行注解处理器，Lombok 不生效会报一堆
> “找不到符号 getXxx()”。此时用 `mvn -Dmaven.compiler.proc=full -Dlombok.version=1.18.44 ...` 编译，
> 或直接用 JDK 17 / Docker 构建。Mockito 的 inline mock 也需要匹配 JDK 的 Byte Buddy 版本，
> 高版本 JDK 下部分既有单测会直接报错，与业务代码无关。

## 快速开始

> 先复制根目录 `.env.example` 为 `.env`，并填写必填项：
> `PROTASK_DB_ROOT_PASSWORD`（容器 MySQL 的 root 密码）、`PROTASK_DB_PASSWORD`（后端连接用的数据库密码，
> 容器场景填成与前者相同的值；宿主机 MySQL 场景填独立业务账号密码）、
> `PROTASK_JWT_SECRET`（可用 `openssl rand -base64 48` 生成）。
> 未配置时 `docker compose` 会直接报错。文件存储默认本地磁盘，接阿里云 OSS 见「存储与上传配置」。

### 1. 启动 MySQL

```powershell
docker compose up -d mysql
```

连接信息：`127.0.0.1:3306`，root/`<PROTASK_DB_ROOT_PASSWORD>`（来自 `.env`），数据库 `protask`（自动创建）。

### 2. 启动后端（端口 8000）

```powershell
# 本地直跑时需将数据库密码注入环境变量（与 .env 中的容器密码一致）
$env:PROTASK_DB_PASSWORD="<PROTASK_DB_ROOT_PASSWORD>"
$env:PROTASK_JWT_SECRET="<随机长字符串>"
mvn -f server/pom.xml spring-boot:run
```

首次启动 Flyway 自动建表，并初始化管理员账号：`admin`。初始密码优先取 `PROTASK_ADMIN_PASSWORD`；
未配置时随机生成，并在首次启动日志中以 WARN 输出（仅打印一次，请登录后立即修改）。

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

### 5. 生产部署（宿主机 nginx + 宿主机 MySQL + 阿里云 OSS）

服务器上 nginx 与 MySQL 已直接运行在宿主机、80/443 已被其他站点占用时，按二级域名部署，
只需要跑后端，`mysql` 与 `webapp` 两个服务都不启用。

后端（二选一）：

```bash
# 方式 A：宿主机直跑 jar（推荐，日志走 journald）
java -jar server/target/protask-server.jar

# 方式 B：仅起 server 容器，数据库连宿主机 MySQL
docker compose up -d --no-deps server   # .env 中覆盖 PROTASK_DB_URL / PROTASK_DB_USER / PROTASK_DB_PASSWORD
```

`.env` 的加载方式按运行方式不同，别踩坑：

| 运行方式 | 是否自动读 `.env` | 说明 |
| --- | --- | --- |
| `docker compose up` | 自动 | compose 读同目录 `.env` 做变量替换，`server` 服务已显式列出需要透传的键 |
| systemd（`deploy/protask.service`） | 自动 | 由 `EnvironmentFile=/etc/protask/.env` 注入；密码含 `$` 只能用 EnvironmentFile，`Environment=` 里要写成 `$$` |
| `java -jar` / `mvn spring-boot:run` 直跑 | 不读 | Spring Boot 不解析 `.env`，需 `set -a; . ./.env; set +a` 或 IDE 的 EnvFile 插件 |

宿主机 MySQL 的三个必查项：

| 项 | 要求 |
| --- | --- |
| 建库 | `CREATE DATABASE pro_task DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci`（库名须与 `PROTASK_DB_URL` 中的库名一致） |
| 账号 | 单独业务账号并只授权该库，不用 root；对应 `PROTASK_DB_USER` / `PROTASK_DB_PASSWORD`，且需建库/DDL 权限（Flyway 建表与迁移） |
| 时区 | 表结构里大量 `DATETIME DEFAULT CURRENT_TIMESTAMP` 由数据库时区决定。容器版靠 `--default-time-zone=+00:00` 保证，宿主机须给 JDBC URL 追加 `&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`（仅影响本连接），否则时间戳会比应用口径快 8 小时 |

宿主机 nginx 新增子站（复用 `webapp/nginx.conf` 的 location 规则，两处不同：`proxy_pass` 指向
`http://127.0.0.1:8000`，`client_max_body_size` 要与后端 `PROTASK_MAX_REQUEST_SIZE`（默认 50MB）对齐，
默认 10m 会把大文件上传拦成 413）：

```nginx
server {
    listen 443 ssl http2;
    server_name protask.yourdomain.com;
    ssl_certificate     /etc/letsencrypt/live/protask.yourdomain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/protask.yourdomain.com/privkey.pem;

    root /var/www/protask/dist;          # cd webapp && npm run build 的产物
    index index.html;
    location / { try_files $uri $uri/ /index.html; }

    location /api/ {
        proxy_pass http://127.0.0.1:8000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 60s;
        client_max_body_size 51m;
    }
}
server { listen 80; server_name protask.yourdomain.com; return 301 https://$host$request_uri; }
```

因为请求必经这层可信代理，`PROTASK_RATE_LIMIT_TRUST_PROXY` 保持 `true`（登录限流按真实客户端 IP 计数）；
同时后端 8000 只绑定 `127.0.0.1`，不要开放到公网。前端与 API 同源，后端无需 CORS 配置。

仓库 `deploy/` 下备好了这套拓扑要用的文件：

| 文件 | 用途 |
| --- | --- |
| `deploy/nginx-protask.conf` | 子站 server block（含 HTTP-01 放行、Vite 资源长缓存、`client_max_body_size`），换域名后可直接放 `/etc/nginx/conf.d/` |
| `deploy/nginx-task.cnood.net.conf` | 同一份配置的宝塔（BT Panel）落地版：vhost 目录、日志路径与复用 `*.cnood.net` 泛域名证书，已在测试服务器实跑 |
| `deploy/protask.service` | 后端 systemd 单元：`EnvironmentFile` 注入环境变量（密码含 `$` 时唯一安全写法）、只绑定 127.0.0.1、`--logging.level.com.protask=info` 关掉逐条 SQL 日志、基础加固 |
| `deploy/oss-ram-policy.json` | OSS RAM 子账号最小权限策略，只放开 `protask/uploads/` 与 `protask/attachments/` 两个前缀 |

环境变量本身不入库：复制 `.env.example` 为 `.env` 填值，服务器上放 `/etc/protask/.env` 并 `chmod 600`。
共用桶时注意 OSS 的读写权限（ACL）是桶级的，无法只把某个前缀设为私有——
私有附件必须落在权限为「私有」的桶里。测试环境暂用单个公有读桶 `cat666cn` + 两个前缀
（`protask/uploads/`、`protask/attachments/`）跑通，上线前把 `PROTASK_OSS_PRIVATE_BUCKET` 换成私有桶即可，前缀不用改。

### 6. 测试服务器现状（101.133.132.240 / Ubuntu 24.04 / 宝塔面板）

已按上述拓扑部署，实测通过。落位如下：

| 位置 | 内容 |
| --- | --- |
| `/opt/protask/protask-server.jar` | 后端可执行 jar，属主 `protask`（`useradd -r -s /usr/sbin/nologin` 专用账号） |
| `/etc/protask/.env` | 真实环境变量，`640 root:protask`；含宿主机 MySQL、OSS、管理员初始密码 |
| `/etc/systemd/system/protask.service` | `enable --now`，只监听 `127.0.0.1:8000`，`Restart=always` |
| `/var/www/protask/dist` | 前端产物，属主 `www:www`，目录 755 / 文件 644 |
| `/www/server/panel/vhost/nginx/task.cnood.net.conf` | 子站配置（宝塔 vhost 目录由 `nginx.conf` 第 96 行 `include *.conf` 加载） |
| `/www/wwwlogs/task.cnood.net.log` | 站点访问日志，沿用面板约定 |

未触碰的既有东西：`cnood.net`、`bt.cnood.net`、`phpmyadmin`、`101.133.132.240` 各站点配置，MySQL 里其它库，
nacos/xxl-job/mongo/redis 等进程，80/443/888 与 ufw 规则（80、443 本就放行）。`nginx -s reload` 是唯一的整体动作。

已实测通过：Flyway V1–V19 建表、管理员登录、Markdown 插图上传落 OSS 并以 `/api/v1/upload/files/**` 同源返回、
私有附件鉴权下载（匿名请求返回 401）、`/api/v1/admin/storage` 两桶统计、孤儿检测关闭时清理接口返回 403。

重新部署（服务器侧已就绪，只差传产物）：

```bash
# 本地构建（Windows：JDK 23+ 需附带这些参数，否则 Lombok 注解处理器不生效）
mvn -f server/pom.xml -DskipTests -Dmaven.compiler.proc=full -Dlombok.version=1.18.44 package
cd webapp && npm run build && tar -czf ../testtmp/protask-dist.tar.gz dist && cd ..

# 上传并重启（jar 变更）
scp server/target/protask-server.jar root@101.133.132.240:/opt/protask/
ssh root@101.133.132.240 'chown protask:protask /opt/protask/protask-server.jar && systemctl restart protask'

# 前端变更
scp testtmp/protask-dist.tar.gz root@101.133.132.240:/tmp/
ssh root@101.133.132.240 'rm -rf /var/www/protask/dist && tar -xzf /tmp/protask-dist.tar.gz -C /var/www/protask && rm -f /tmp/protask-dist.tar.gz && chown -R www:www /var/www/protask'

# 日志与状态
ssh root@101.133.132.240 'journalctl -u protask -n 50 --no-pager'
```

唯一待办：`task.cnood.net` 目前没有 A 记录（`cnood.net`、`www.cnood.net` 已指向本机）。加记录前可用
`curl -sk --resolve task.cnood.net:443:127.0.0.1 https://task.cnood.net/api/v1/site/settings` 在服务器上验证，
加完后浏览器即可直接访问，证书用的 `*.cnood.net` 泛域名（2026-11-20 到期，面板续签后本子站自动跟随）。

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

## 存储与上传配置

附件按任务分目录存放，文件名为 UUID：

| 目录 | 配置项 | 内容 | 访问方式 |
| --- | --- | --- | --- |
| `uploads/`（插图扁平存放）与 `uploads/{projectId}/{taskId}/`（图片附件） | `protask.upload-dir`（默认 `uploads`） | Markdown 插图、图片附件 | 公开静态 `/api/v1/upload/files/**` |
| `uploads-attachments/{projectId}/{taskId}/` | `protask.attachment-dir`（默认 `uploads-attachments`） | 文档/压缩包等附件 | 鉴权下载接口 |

相关配置项（`server/src/main/resources/application.yml`）：

| 配置 / 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PROTASK_MAX_FILE_SIZE` | `20MB` | 单文件上传上限，经 `/api/v1/site/settings` 下发给前端预校验 |
| `PROTASK_MAX_REQUEST_SIZE` | `50MB` | 单次请求总大小上限（为批量上传预留） |
| `protask.attachment.max-per-task` | `20` | 单任务附件数量上限（`<=0` 不限制） |
| `protask.attachment.max-per-project` | `0` | 单项目附件数量上限（`<=0` 不限制） |
| `protask.attachment.max-per-user-per-day` | `0` | 单用户每日上传附件数上限（`<=0` 不限制） |
| `protask.storage.warn-file-count` | `50000` | 两目录合计文件数告警阈值 |
| `protask.storage.warn-total-bytes` | `5368709120` | 两目录合计占用告警阈值（5GB） |
| `protask.storage.orphan-retention-days` | `7` | 孤儿图片保留天数（仅在启用孤儿检测时生效，无独立环境变量） |
| `PROTASK_STORAGE_ORPHAN_CHECK` | `false` | 孤儿图片检测与清理开关。默认关闭：判定需全表扫描块/项目/站点文本，并逐张向存储层取大小（OSS 为 HeadObject），且清理不可逆。关闭时后台不展示该卡片，接口也会直接拒绝；需回收无引用插图时临时开启 |
| `PROTASK_STORAGE_CHECK_CRON` | `0 0 3 * * *` | 存储巡检 cron，超阈值输出 WARN 日志 |
| `PROTASK_STORAGE_BACKEND` | `local` | 存储后端：`local` 本地磁盘，`oss` 阿里云 OSS |
| `PROTASK_OSS_ENDPOINT` | — | OSS 接入点，同地域 ECS 建议用 `-internal` 内网域名 |
| `PROTASK_OSS_ACCESS_KEY_ID` / `_SECRET` | — | RAM 子账号凭证，仅授权下列两个桶 |
| `PROTASK_OSS_PUBLIC_BUCKET` / `_PRIVATE_BUCKET` | — | 公开图片桶 / 私有附件桶（附件桶须为私有读写） |
| `PROTASK_OSS_PUBLIC_PREFIX` / `_PRIVATE_PREFIX` | `uploads/` / `attachments/` | 对象 key 前缀；库内存的是前缀之后的相对路径，上线后不可更改 |

存储抽象为 `FileStorage` 接口（`server/src/main/java/com/protask/storage/`），已有本地磁盘（`LocalFileStorage`）与阿里云 OSS（`OssFileStorage`）两个实现，`FileStorageConfig` 按 `protask.storage.backend` 条件装配，业务层（上传、鉴权下载、孤儿清理、巡检统计）只依赖 `StorageRegistry`，切换后端无需改代码或迁移库内数据。

OSS 模式下公开图片仍走 `/api/v1/upload/files/{filename}` 同源返回（`PublicImageController` 从对象存储流式读取，带 `ETag` 与一年强缓存），文件名严格限定为本系统上传的 UUID + 图片扩展名，因此该免登录接口读不到私有附件。附件下载继续走鉴权接口，不产生公开直链。若后续要省带宽，可把公开图片桶设为公共读并把该接口改成 302 跳转 OSS/CDN 直链，但库内 Markdown 已存的相对路径 URL 需保持可用。管理后台「存储管理」页可查看两个桶的用量并清理无引用的 Markdown 插图（OSS 模式按对象前缀分页统计）。

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
| GET/POST | /api/v1/tasks/{taskId}/attachments | 附件分页列表（`page`/`size`） / 上传附件 |
| GET | /api/v1/tasks/{taskId}/attachments/{id}/download | 鉴权下载附件 |
| DELETE | /api/v1/tasks/{taskId}/attachments/{id} | 删除附件 |
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
| GET | /api/v1/admin/storage | 存储用量与孤儿图片统计 |
| DELETE | /api/v1/admin/storage/orphans | 清理孤儿图片（默认关闭，需 `PROTASK_STORAGE_ORPHAN_CHECK=true`，否则返回 403） |
| DELETE | /api/v1/admin/projects/deleted | 物理清理已删项目 |
| GET/PUT | /api/v1/site/settings | 站点公开设置 / 更新站点设置 |

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