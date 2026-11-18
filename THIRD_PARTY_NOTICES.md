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

## 待核对项

- PostgreSQL JDBC Driver 版本 42.7.14 已核对（以打包 jar 的 BOOT-INF/lib 与本地 Maven 仓库双证据确认，2026-11-18）。
- 其余组件的许可证类型均已按各项目官方仓库/包元数据核对。
