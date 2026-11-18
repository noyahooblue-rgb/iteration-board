# 第三方组件与许可清单

本清单以后端 `backend/pom.xml`、前端 `frontend/package.json`（版本以 `frontend/package-lock.json` 实际锁定为准）、`mock-backend/package.json` 的直接依赖为准。传递依赖未逐项展开。

## 后端（backend/pom.xml）

| 组件 | 版本 | 许可证 |
| --- | --- | --- |
| Spring Boot（spring-boot-starter-parent 及 web / security / data-jpa / flyway / validation / test 等 starter） | 4.1.2 | Apache-2.0 |
| Flyway（flyway-core、flyway-database-postgresql） | 13.12.2 | Apache-2.0 |
| PostgreSQL JDBC Driver（org.postgresql:postgresql） | 42.7.14 | BSD-2-Clause |
| springdoc-openapi（springdoc-openapi-starter-webmvc-ui） | 3.2.0 | Apache-2.0 |
| Apache POI（poi-ooxml） | 6.0.0 | Apache-2.0 |
| junixsocket（junixsocket-common、junixsocket-native-common，测试作用域，当前测试配置未直接使用） | 2.10.1 | Apache-2.0 |
| Spring Security Test / Spring Boot Test（测试作用域，随 Spring Boot） | 4.1.2（随父 POM） | Apache-2.0 |

## 前端（frontend/package.json，锁定版本据 package-lock.json）

| 组件 | 声明版本 | 锁定版本 | 许可证 |
| --- | --- | --- | --- |
| vue | ^3.5.0 | 3.5.45 | MIT |
| vue-router | ^4.4.0 | 4.6.5 | MIT |
| pinia | ^2.2.0 | 2.3.1 | MIT |
| lucide-vue-next | ^0.460.0 | 0.460.0 | ISC |
| marked | ^14.0.0 | 14.1.4 | MIT |
| dompurify | ^3.2.0 | 3.4.18 | MPL-2.0 OR Apache-2.0 |
| @fontsource-variable/inter（Inter 可变字体） | ^5.1.0 | 5.3.0 | OFL-1.1 |
| vite（开发依赖） | ^6.0.0 | 6.4.6 | MIT |
| @vitejs/plugin-vue（开发依赖） | ^5.2.0 | 5.2.4 | MIT |
| typescript（开发依赖） | ^5.7.0 | 5.9.3 | Apache-2.0 |
| vue-tsc（开发依赖） | ^2.2.0 | 2.2.12 | MIT |
| esbuild（vite 传递依赖；mock-backend 冒烟测试直接引用其打包能力） | —（传递） | 0.25.12 | MIT |

## 模拟后端（mock-backend/package.json）

无第三方运行时依赖：仅使用 TypeScript 类型与浏览器/Node 标准能力；其冒烟测试引用前端依赖树中的 esbuild（见上表）。

## 离线安装包运行时组件（packaging/，随 Windows 离线包分发）

| 组件 | 版本 | 许可证 / 来源 |
| --- | --- | --- |
| PostgreSQL Windows x64 二进制 | 18.7 | PostgreSQL License；EnterpriseDB 官方二进制归档 |
| Eclipse Temurin JRE Windows x64 | 17（最新 17.x） | GPL-2.0 with Classpath Exception；Adoptium 官方发布 |
| WinSW x64 | v2.12.0 | MIT；WinSW 官方 GitHub 发布 |
| Microsoft VC++ 2015–2022 x64 运行库 DLL（随应用部署，见下） | 14.44.35211.0 | 微软可再分发文件（Distributable Code），见下 |

### VC++ 运行库 DLL（app-local，随应用部署）

自离线包 1.0.2 起，VC++ 运行库不再通过 vc_redist.x64.exe 系统安装（其静默安装在受管控机器上可能无限挂起），改为把运行库 DLL 直接放在使用它们的二进制旁边（app-local 部署）：

| 文件 | 版本 | 大小（字节） | SHA-256 | 放置位置 |
| --- | --- | --- | --- | --- |
| vcruntime140.dll | 14.44.35211.0 | 124,544 | `d5e4d9a3e835fa679450145d6a7d94e36573a509317111904d9b3712c30d9066` | runtime\pgsql\bin、runtime\pgsql\lib |
| vcruntime140_1.dll | 14.44.35211.0 | 49,792 | `1f2d41c4aa5db0bc33ebf7b66d72943a817d7ce6cbe880502a9403823633093f` | runtime\pgsql\bin、runtime\pgsql\lib |
| msvcp140.dll | 14.44.35211.0 | 557,728 | `0f885b509a685d2bbfa652fed26b5fb31d88fbdab0a978c641d1c7b8aa460aa9` | runtime\pgsql\bin、runtime\pgsql\lib |

- 来源：微软官方 VC++ 2015–2022 x64 可再发行安装程序 vc_redist.x64.exe（https://aka.ms/vs/17/release/vc_redist.x64.exe，本次所用安装程序文件 SHA-256 `cc0ff0eb1dc3f5188ae6300faef32bf5beeba4bdd6e8e445a9184072096b713b`），从其 vcRuntimeAdditional amd64 载荷中提取上述 DLL 原文件，未做任何修改。
- 许可：上述 DLL 属于微软《Visual Studio 2022 可再分发文件列表》（Distributable Code）中允许随应用再分发的文件，依微软软件许可条款中的可分发代码条款随本应用分发。
- 完整性依据：对包内 PostgreSQL 全部 .exe/.dll 做 PE 导入表分析（objdump -p，含延迟加载表），非系统、非自带依赖只有这三个 DLL；三者的自身导入仅为 KERNEL32、彼此与 api-ms-win-crt-*（UCRT API 集，Windows 10/11 系统自带）。Temurin JRE 无需此处理：Adoptium 发布包已在 jre\bin 内自带其 VC++ 运行库 DLL（1.0.1 包内实物核对属实）。

## 待核对项

- PostgreSQL JDBC Driver 版本 42.7.14 已核对（以打包 jar 的 BOOT-INF/lib 与本地 Maven 仓库双证据确认，2026-11-18）。
- 其余组件的许可证类型均已按各项目官方仓库/包元数据核对。
