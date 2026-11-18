# 浏览器模拟后端（mock-backend）
独立 TS 模块，按 `../contract/openapi.yaml` 与 `state-machine.md` 全量实现 M1 接口面，数据只在内存中。
仅当前端以 `VITE_API_MODE=mock` 构建时才会被引用；真实后端不引用本目录。
开关：`src/index.ts` 的 `MOCK_CONFIG`（演示构建 registrationOpen=false, demoMode=true）。
演示账号口令：由环境变量 DEMO_PASSWORD 提供（种子在 Node 环境下读取），未设置时使用占位符 CHANGE_ME_DEMO_PASSWORD；冒烟测试与前端一键填入使用同一口径，设置方法见根 README。
