# Windows 脚本编写规范（.bat，1.1.0 定稿）

> 适用：迭代小组平台全部 Windows 批处理脚本（`packaging/windows/*.bat` 及运行时生成的 .bat）。
> 每条规则配「反例 → 正例」与一句为什么。背景与事故见 `docs/根因台账-安装脚本.md`（尤其第 3、5、12 条）。
> 守门工具：`tools/check-bat.py`（规则 R1–R6 + 原有 6 条），CI 档 1 必跑、全绿才交付。

## 1. 外壳模板（每个用户可双击的 .bat 必须有）

统一契约四件套：自动化钩子（IB_AUTO/IB_YES）、日志（:log 双写）、防闪退外壳（IB_WRAPPED）、自提权（:ib_elevate）。可复制模板（放在参数解析之后、实质工作之前；子程序放在文件末尾）：

```bat
@echo off
setlocal EnableExtensions DisableDelayedExpansion
set "IB_ARGS=%*"
rem ... header / argument parsing sets NOPAUSE and YES ...
rem --- IB unified contract: automation hooks + anti-crash shell --
if "%IB_AUTO%"=="1" set "NOPAUSE=1"
if "%IB_AUTO%"=="1" set "YES=1"
if "%IB_YES%"=="1" set "YES=1"
if defined IB_WRAPPED goto :ib_wrap_done
if "%IB_AUTO%"=="1" goto :ib_wrap_done
if "%NOPAUSE%"=="1" goto :ib_wrap_done
set "IB_WRAPPED=1"
cmd /k ""%~f0" %IB_ARGS%"
exit /b
:ib_wrap_done
rem --- IB unified contract: log file next to this script --------
for /f "usebackq delims=" %%T in (`powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"`) do set "IB_TS=%%T"
if "%IB_TS%"=="" set "IB_TS=run"
set "IB_LOG=%~dp0%~n0-%IB_TS%.log"
call :log [INFO] %~n0 started - log file: %IB_LOG%
net session >nul 2>&1
if errorlevel 1 goto :ib_elevate
cd /d "%~dp0"
```

```bat
rem --- file end: subroutines --------------------------------------
:ib_elevate
if "%IB_ELEVATED%"=="1" goto :ib_elevate_fail
set "IB_ELEVATED=1"
set "IB_SELF=%~f0"
set "IB_DIR=%~dp0"
powershell -NoProfile -Command "Start-Process -FilePath $env:IB_SELF -ArgumentList $env:IB_ARGS -Verb RunAs -WorkingDirectory $env:IB_DIR"
if errorlevel 1 goto :ib_elevate_fail
exit /b 0
:ib_elevate_fail
echo [ERROR] Please run as administrator - auto-elevation failed
call :ib_pause
exit /b 1

:ib_pause
if defined IB_LOG echo [INFO] Log file: %IB_LOG%
if "%NOPAUSE%"=="0" pause
exit /b 0

:log
echo %*
if defined IB_LOG echo %*>>"%IB_LOG%"
exit /b 0
```

要点：IB_AUTO=1 时不 pause、不提问、不套 `cmd /k`（靠返回码与日志断言）；被其他脚本 `call` 的脚本靠 IB_WRAPPED 已定义（调用方进程内）或 NOPAUSE（如 backup 的 auto）跳过外壳，不许嵌套弹窗；不需要管理员的脚本（如 backup，计划任务以 SYSTEM 运行）不加提权段，但要在重检清单里写理由。

反例 → 正例：
- 反例：脚本第一行就干活，报错时窗口一闪而过，用户只能报「没反应、闪退」。
- 正例：上面的外壳。为什么：闪退现场不可复现等于没有错误信息；外壳 + 日志让每一次失败都留下可带回的证据（台账第 12 条）。

## 2. 括号块规则

- 任何 `if (...)` / `for (...)` 括号块内，**不得展开路径或用户输入变量**（`%TARGET% %ROOT% %~dp0 %1 %*` 等，check-bat R1 名单为准），不得写 `if errorlevel`（R3），`echo` 文本不得含未转义括号（paren-in-block）。
- 需要在块内显示/判断路径的，改 goto 结构或 `call` 子程序；必须先判断的，先在块外存不含路径的标志变量再进块。

反例 → 正例：
```bat
rem BAD: %TARGET% expands when the whole block is parsed;
rem a ) in the path ends the block early and the script dies.
if not exist "%TARGET%\app\iteration-board.jar" (
  echo [ERROR] Target install not found: %TARGET%
  exit /b 1
)
```
```bat
rem GOOD: same check, no block at all.
if exist "%TARGET%\app\iteration-board.jar" goto :tjar_ok
echo [ERROR] Target install not found: %TARGET%
call :ib_pause
exit /b 1
:tjar_ok
```
为什么：cmd 在**读入整块时**一次性展开 `%VAR%` 并配平括号，变量值里的 `)` 会被当成块结束——这是解析层事故，不是逻辑错误，测试路径不含括号时永远测不出来（台账第 12 条）。

## 3. 目标目录规则

- 操作既有安装的脚本（补丁/升级类）**绝不默认脚本所在目录**。顺序：①参数（给了就用并校验）；②服务注册反查（sc qc / 注册表 ImagePath），恰好一个时显示路径并确认（IB_YES/IB_AUTO 自动是）；③零个或多个 → 提示用户输入/拖入，不许猜。
- 最终目录必须校验安装标志齐全（本项目：`app\iteration-board.jar`、`config\application.yml`、`version.txt`），缺一即明确报错、非 0 退出、不动任何文件。
- 新装脚本（install）的目标 = 脚本所在目录是其语义本身，不适用反查；随安装部署、只操作本机的脚本（backup/restore 等）以 %~dp0 为安装目录是既定布局，不算「默认错误目录」——但必须在脚本头注释里写明这层语义。

反例 → 正例：
- 反例：`if "%TARGET%"=="" set "TARGET=%PATCHROOT%"`——用户从补丁目录双击，脚本把补丁包当安装目录打补丁。
- 正例：apply-patch 的三级 resolve + 三件套校验。为什么：默认值指向错误目录时，脚本越「健壮」破坏越大；宁可停下来问，也不许猜（台账第 12 条同源：交付说明曾称可选择目录而脚本并无此交互）。

## 4. 引号规则

- 所有路径引用一律加引号：`"%TARGET%\app\iteration-board.jar"`；命令参数、for /f 文件名、重定向目标同理。
- 报错信息里回显路径也要加引号——既防 R6 扫描误判，也让含空格的路径在日志里可辨。
- 唯一例外：schtasks /TR 这类复合参数用 `\"` 转义引号整体成串，加 R6 豁免注释并在重检清单登记理由。

反例 → 正例：
- 反例：`copy %TARGET%\app\iteration-board.jar %BKROOT%\`——路径含空格即断成多个参数，复制到错误位置还不报错。
- 正例：`copy /Y "%TARGET%\app\iteration-board.jar" "%BKROOT%\"`。为什么：安装路径允许空格是既定口径（空格路径冒烟常驻），未引号拼接在空格路径上必炸。

## 5. 编码规则

- .bat 一律**纯 ASCII + CRLF**：提示文案只写英文 ASCII；中文只进 .md 文档与应用本身。
- check-bat 的 non-ascii / R4 守门；编辑器保存时关掉「UTF-8 BOM」与 LF 转换。

反例 → 正例：
- 反例：在 .bat 里 echo 中文提示——cmd 按本机代码页（936/1252…）读脚本，轻则乱码、重则字节错位改变命令。
- 正例：`echo [ERROR] Please run as administrator.`。为什么：脚本要在代码页不可控的机器上跑，ASCII 是唯一全集（台账第 6 条的路径版同理）。

## 6. 临时目录规则

- 禁用 `%TEMP%`（check-bat R5）：临时文件一律放在脚本旁或安装目录下的专属 `tmp\` 子目录，用时间戳/用途命名，结束（成功与失败路径都要）清理。

反例 → 正例：
- 反例：`echo %DBPWD%> "%TEMP%\ib-pw.txt"`——中文用户名下 %TEMP% 是中文路径（PostgreSQL 子进程会踩代码页坑），且权限/清理不可控。
- 正例：`echo %DBPWD%> "%ROOT%\tmp\ib-pw.txt"` + 用后 `del`。为什么：临时目录和被操作对象同盘同权限域，行为可预测；冒烟档专门有中文 TEMP 变体，%TEMP% 写法等于把命运交给环境。

## 7. 返回码规则

- 成功 0、失败非 0，用户主动取消为 0（除非脚本既有口径另有规定并在头部注释写明）。
- 关键命令的退出码**立即**存变量（`set "IBRC=%ERRORLEVEL%"`）再判断；不许 `echo` 之后再判 errorlevel（echo 会清退出码，台账第 5 条）。
- 子程序一律 `exit /b`，主流程出口前打印日志路径（经 :ib_pause）。

反例 → 正例：
- 反例：`pg_dump ...` 后先 `echo done` 再 `if errorlevel 1 ...`——判到的永远是 echo 的 0。
- 正例：先存 IBRC 再输出、再判。为什么：errorlevel 是易失状态，任何一条命令都会覆盖它。

## 8. PowerShell 传参规则

- **只经环境变量传参**：路径、用户输入、密码类数据先 `set "IB_X=值"`，PS 侧读 `$env:IB_X`；禁止把 `%VAR%` 直接拼进 `-Command "..."` 字符串。
- 例外与遗留：存量脚本中少量 `'%ROOT%'` 式插值（值均为脚本目录派生、无用户输入）在重检清单中登记为遗留，新增代码不得再添；改写存量命令时必须整条改 env 方式并跑对应回归（如 migrate-import 的 IB-SETTINGS 命令有 tools/ci/test-migrate-settings.ps1 逐字提取测试，动它先过测试）。

反例 → 正例：
- 反例：`powershell -Command "Copy-Item '%TARGET%\a' '%TARGET%\b'"`——TARGET 含 `'` 或 `$` 即破坏 PS 语法甚至注入。
- 正例：`set "IB_TARGET=%TARGET%"` + `powershell -Command "Copy-Item (Join-Path $env:IB_TARGET 'a') ..."`。为什么：cmd 与 PS 是两层引号/展开规则，字符串拼接跨层必漏；环境变量是两层之间唯一不转义的通道（元根因，台账卷首）。

## 9. 交付说明与脚本行为一致性规则

- 交付说明/手册里描述的**每一个交互步骤**（确认提示、选择目录、输入路径……），脚本里必须真的存在，且在 CI 里有对应用例（IB_AUTO/IB_YES 驱动）。说明写了脚本没有的交互，按缺陷处理（台账第 12 条）。
- 脚本改交互，必须同步改说明与 CI 用例，三者同一提交。

反例 → 正例：
- 反例：交付说明称补丁「运行后按提示选择安装目录」，脚本实际无此交互且默认补丁目录——用户按说明操作必然落空，真机首验即翻车。
- 正例：apply-patch 本轮补齐三级 resolve 后，说明、用例、脚本三者对齐（CI 用例缺口已在重检清单 §5 明示并排期）。为什么：说明是用户唯一的操作地图，地图与地形不符时错的是地图制作者，不是用户。

## 10. 长期方向

- **逻辑迁 Java 运维命令，.bat 只做壳**：业务与复杂文件操作逐步收进应用的 ops 命令模式（先例：reset-admin 已收进 `--ib.ops=reset-admin`；AdminBootstrapRunner 收进应用），.bat 只负责提权、定位运行时、调用、显示结果。
- 判断标准：一段逻辑若需要第二层引号处理（PS 拼 SQL、PS 拼替换串……），就不该留在 .bat 里（台账第 8 条 `$118080` 即此类）。
- 在迁移完成前，新 .bat 逻辑以「操作系统层面的事」为限：服务启停、文件复制、进程等待、调用外部命令并检查返回码。

## 附：交付前自查（每个改过的 .bat）

1. `python3 tools/check-bat.py packaging/windows` 全绿（豁免逐条登记理由）。
2. 括号总配平为 0；goto/call 目标标签全部存在且不重名。
3. 纯 ASCII + CRLF（R4 即是此项）。
4. 逐行人工复核一遍括号块与 errorlevel 时机——Linux 上跑不了 .bat，静态复核就是最后一道闸；不许声称真机验证过。
