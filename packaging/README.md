# 离线安装包打包说明

本目录是「迭代小组平台」Windows 离线安装包的打包工程。仓库里只保存脚本、配置模板与说明；运行时组件（二进制）在打包时从官方地址下载到 `cache/`，不进 git（见本目录 `.gitignore`）。

## 目录内容

| 文件 | 说明 |
| --- | --- |
| `build-package.sh` | 打包主脚本（在 Linux 构建机运行） |
| `windows/*.bat` | 随包交付的 Windows 脚本：install / start / stop / uninstall / backup / restore / upgrade |
| `windows/IterationBoardApp.xml`、`windows/IterationBoardPG.xml` | WinSW 服务定义模板（包内组装到 `service/` 目录，安装时复制到 `app/` 并替换安装路径占位符） |
| `config/application.yml` | 应用配置模板（占位符 `__DB_PASSWORD__`、`__APP_PORT__` 由 install.bat 在安装时替换） |

## 打包步骤

```bash
bash packaging/build-package.sh            # 完整打包（需联网下载运行时组件）
bash packaging/build-package.sh --skip-downloads   # 仅用 cache/ 已有组件重打包
```

脚本依次执行：

1. 用仓库内 Maven 包装脚本离线构建后端 jar；
2. `npm run build` 构建真实前端，并把 `frontend/dist` 注入 jar 的 `BOOT-INF/classes/static/`（后端工程自身的 `static/` 只有 openapi.yaml，jar 默认不含前端——组装只发生在打包环节，不改仓库源码）；
3. 下载/复用运行时组件（官方直链，见下表），解压进 staging；
4. 按交付结构组装目录树：`app/`（jar）、`runtime/`（pgsql、jre、winsw、vc_redist.x64.exe）、`config/`（application.yml + prompts）、`data/`、`backup/`、`logs/`、`docs/`（部署手册、用户手册）、`service/`（WinSW 模板）、根目录 7 个 .bat；
5. 打成 `packaging/dist/iteration-board-offline-1.0.0.zip`。

## 运行时组件官方来源

| 组件 | 版本 | 官方地址 |
| --- | --- | --- |
| PostgreSQL Windows x64 二进制 zip | 18.7（与开发/测试锁定版本一致，1.0.1 起） | https://get.enterprisedb.com/postgresql/postgresql-18.7-1-windows-x64-binaries.zip（EnterpriseDB 官方归档） |
| Eclipse Temurin JRE Windows x64 zip | 17（最新 17.x） | https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jre/hotspot/normal/eclipse（Adoptium 官方 API） |
| VC++ 2015-2022 x64 运行库 | 最新 | https://aka.ms/vs/17/release/vc_redist.x64.exe（微软官方直链） |
| WinSW x64 | v2.12.0（稳定版，MIT；1.0.1 起弃用 3.x alpha 线） | https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW-x64.exe（WinSW 官方 GitHub 发布；需 .NET Framework 4.6.1+，Win10/11 自带） |

某个组件下载失败时，脚本会继续完成其余组装并在结尾醒目列出缺失组件与官方 URL；交付前必须补齐，不得使用来源不明的二进制替代。

## 交付前检查

- 解压 zip，核对目录树与 7 个 .bat、2 个服务 XML 齐全；
- 在 Windows 机器上按同目录 `WINDOWS-实测清单.md` 逐项实测并签字；
- 部署操作见 `docs/部署手册.md`，使用说明见 `docs/用户手册.md`（两份手册也会随包放入 `docs/`）。
