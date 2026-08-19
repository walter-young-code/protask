# ProTask - 团队任务管理系统

前后端分离的任务管理 Web 应用。前端 Vue 3（组合式 API），后端 Java Spring Boot 3 单体架构，MySQL 数据库。

## 技术栈

| 层 | 技术 |
| --- | --- |
| 前端 | Vue 3.5 + TypeScript + Vite 8 + Element Plus + Pinia + Vue Router + Axios |
| 前端测试 | Vitest |
| 后端 | Java 17 + Spring Boot 3.3 + MyBatis-Plus 3.5.9 + Sa-Token 1.39（JWT） |
| 数据库 | MySQL 8.0（Docker 容器），Flyway 版本迁移 |
| 代码规范 | ESLint (flat config) + Prettier |

## 目录结构

```
ProTask/
├── server/                 # Spring Boot 后端（端口 8000）
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/protask/
│       │   ├── common/     # 统一响应 Result、异常处理
│       │   ├── config/     # Sa-Token/MyBatis-Plus/密码/初始化数据
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
│       ├── components/     # 通用组件（任务/项目对话框）
│       ├── i18n/           # vue-i18n 实例（语言持久化/探测）
│       ├── layouts/        # 主布局（含顶栏语言切换器）
│       ├── locales/        # 中英文语言包（zh-CN/en-US）
│       ├── router/         # 路由 + 登录守卫
│       ├── stores/         # Pinia 全局状态（用户/项目）
│       ├── styles/         # 全局样式
│       ├── types/          # TS 类型定义
│       ├── utils/          # 状态流转规则等工具
│       └── views/          # 页面（登录/注册/总览/项目详情）
└── docker-compose.yml      # MySQL 8.0 容器
```

## 多语言支持（中/英）

- **前端**：vue-i18n，语言包在 `webapp/src/locales/`（zh-CN / en-US）。顶栏右侧可切换语言，选择持久化到 `localStorage`（key `protask_locale`），未选择时按浏览器语言自动探测；Element Plus 组件内置文案（日期选择器、分页等）随语言同步切换。
- **后端**：错误消息与参数校验消息按请求头 `Accept-Language` 返回对应语言（默认中文）。资源文件：`server/src/main/resources/i18n/messages*.properties`（业务错误）与 `ValidationMessages*.properties`（DTO 校验）。前端 Axios 已自动携带当前语言。
- 新增文案时：前端在两个语言包中同步补充 key；后端在两个 properties 中同步补充，DTO 注解用 `{key}` 引用、业务异常用 `new BusinessException(ResultCode.XXX, "key", args...)` 抛出。

## 环境要求

- JDK 17+
- Maven 3.9+
- Node.js 20.19+（本机为 24）
- Docker（用于运行 MySQL）

## 快速开始

### 1. 启动 MySQL

```powershell
docker compose up -d
```

连接信息：`localhost:3306`，root/root，数据库 `protask`（自动创建）。

### 2. 启动后端（端口 8000）

```powershell
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

## 常用命令

| 命令 | 说明 |
| --- | --- |
| `cd webapp && npm run build` | 前端类型检查 + 生产构建（输出 webapp/dist） |
| `cd webapp && npm run test` | Vitest 单元测试 |
| `cd webapp && npm run lint` | ESLint 检查并自动修复 |
| `cd webapp && npm run format` | Prettier 格式化 |
| `mvn -f server/pom.xml test` | 后端测试 |
| `mvn -f server/pom.xml package` | 后端打包为 JAR（server/target/protask-server.jar） |

## API 概览

统一响应结构：`{ "code": 200, "message": "...", "data": ... }`；认证方式为请求头 `Authorization: Bearer <JWT>`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/v1/auth/register | 注册 |
| POST | /api/v1/auth/login | 登录，返回 JWT |
| POST | /api/v1/auth/logout | 登出 |
| GET | /api/v1/auth/me | 当前用户信息 |
| GET | /api/v1/users | 用户列表（任务分配用） |
| GET/POST | /api/v1/projects | 我的项目列表 / 创建项目 |
| GET/PUT/DELETE | /api/v1/projects/{id} | 项目详情 / 更新 / 删除（仅负责人） |
| GET/POST | /api/v1/projects/{id}/tasks | 任务列表 / 创建任务 |
| PUT | /api/v1/tasks/{id} | 更新任务 |
| PATCH | /api/v1/tasks/{id}/status | 任务状态流转 |
| DELETE | /api/v1/tasks/{id} | 删除任务 |

### 任务状态流转规则

- `todo`（待处理）→ `in_progress` / `done`
- `in_progress`（进行中）→ `todo` / `done`
- `done`（已完成）→ `in_progress`（不可直接退回 todo）

前后端均实现该规则校验（后端 `TaskService.TRANSITIONS`，前端 `src/utils/meta.ts`）。

## 数据库设计

- `sys_user` 用户表：用户名唯一，BCrypt 加密密码，角色 admin/user
- `project` 项目表：负责人 owner_id，状态 active/archived
- `project_member` 项目成员表：多对多关系，项目角色 owner/admin/member
- `task` 任务表：标题、描述、状态、优先级、负责人、截止时间

结构变更请新增 Flyway 脚本 `server/src/main/resources/db/migration/V{n}__xxx.sql`。
