# 迭代小组平台（iteration-board）

面向 Scrum 小组的协同看板：小组在同一个工作区里管理迭代、事项、进展与报告，支持 AI 辅助、文件导入与外部系统对接，数据保存在自有 PostgreSQL 数据库中。功能全貌见 [docs/功能说明.md](docs/功能说明.md)。

> 截图待补：后续将使用全虚构演示数据补充界面截图。

## 目录结构

| 目录 | 内容 |
| --- | --- |
| `backend/` | 真实后端：Spring Boot + Java 17 + PostgreSQL 18，Flyway 数据库迁移；构建为单个 jar（内嵌前端构建产物） |
| `frontend/` | 真实前端：Vue 3 + TypeScript + Vite + Pinia + Vue Router |
| `mock-backend/` | 浏览器内模拟后端：按同一份契约在浏览器内存中模拟 `/api/v1`，仅供演示构建使用，永不进入真实后端产物 |
| `contract/` | 唯一契约来源：`openapi.yaml` + `state-machine.md`，真实后端与模拟后端共同遵循 |
| `tools/ai-eval/` | AI 功能提示词回归评测脚本（Python 3.12 标准库单文件），使用方法见其目录内说明 |
| `docs/` | 功能说明等通用文档 |

真实构建与演示构建严格隔离：前端默认 `real` 模式构建不引用 `mock-backend/` 的任何代码与演示字符串，演示数据只存在于 `mock` 模式构建中。

## 本地运行

### 前置条件

- JDK 17、Maven 3.9+、Node.js 20+、PostgreSQL 18（本地已运行，存在数据库 `iteration_board` 与可登录角色 `postgres`）。

### 后端

数据库口令没有默认值，必须通过环境变量提供：

```bash
export DB_PASSWORD=<你的数据库口令>
# 可选覆盖：DB_URL（默认 jdbc:postgresql://localhost:5432/iteration_board）、DB_USERNAME（默认 postgres）
cd backend
mvn spring-boot:run
```

如需接入真实 AI 模型，再设置 `AI_MODE=live`、`AI_BASE_URL`、`AI_API_KEY`、`AI_TEXT_MODEL`（及可选 `AI_VISION_MODEL`）；默认 `AI_MODE=mock`，返回结构化样例结果，不调用外部服务。

后端测试使用独立的测试数据源环境变量（默认值与 CI 的 PostgreSQL 服务容器对齐）：

```bash
cd backend
mvn test
# 本地覆盖示例：TEST_DB_URL / TEST_DB_USERNAME / TEST_DB_PASSWORD
```

### 前端（真实模式）

```bash
cd frontend
npm ci
npm run dev   # 开发服务器将 /api 代理到 localhost:8080
npm run build # 生产构建，产物在 frontend/dist
```

### Mock 演示模式

演示模式下前端请求由 `mock-backend/` 在浏览器内存中处理，不需要后端与数据库：

```bash
cd frontend
npm ci
npm run build:mock   # 或 VITE_API_MODE=mock npm run dev
```

演示账号为种子数据中的虚构账号（组长 `leader`、PO `po`、成员 `member` 等）。演示口令由部署/运行时环境变量 `DEMO_PASSWORD` 提供：

- 种子数据（`mock-backend/src/seed.ts`）在 Node 环境下读取 `DEMO_PASSWORD`；未设置时使用占位符 `CHANGE_ME_DEMO_PASSWORD`（仅占位，不是可用口令，请务必在部署演示环境前设置真实演示口令）。
- 模拟后端冒烟测试（`mock-backend/test/smoke.mjs`）使用同一口径：`DEMO_PASSWORD=<演示口令> node test/smoke.mjs`。
- 前端一键填入同样读取种子中的同一口令值，三处口径一致。

## 构建离线包

离线安装包由仓库内的构建脚本生成（`packaging/build-package.sh`，在 Linux 构建机执行）：脚本完成后端打包、把前端生产构建内嵌进 jar，再组装 PostgreSQL / JRE / VC++ 运行库与 install / start / stop / uninstall / backup / restore / upgrade 脚本，产出 `packaging/dist/iteration-board-offline-1.0.0.zip`（运行时二进制经缓存目录组装，不进 Git）。部署与使用见 [docs/部署手册.md](docs/部署手册.md) 与 [docs/用户手册.md](docs/用户手册.md)；Windows 实测清单见 `packaging/WINDOWS-实测清单.md`。

```bash
bash packaging/build-package.sh            # 首次会下载官方运行时组件
bash packaging/build-package.sh --skip-downloads   # 复用 packaging/cache 缓存
```

## 技术栈

- 后端：Java 17、Spring Boot 4.1.2、Spring Security、Spring Data JPA、Flyway、PostgreSQL 18、Apache POI、springdoc-openapi
- 前端：Vue 3、TypeScript、Vite、Pinia、Vue Router、Lucide 图标、Inter 字体（均本地打包，不引用外网地址）
- 契约：OpenAPI 3（`contract/openapi.yaml`）+ 状态机文档
- 第三方组件与许可证清单见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)

## 许可

本项目采用 MIT 许可证，见 [LICENSE](LICENSE)。
