# check-bat 新规则清单与命中/修复记录（1.1.0 脚本重检，2026-11-24）

> 配套：`docs/脚本重检清单-1.1.0.md`、`docs/Windows脚本编写规范.md`、`docs/根因台账-安装脚本.md` 第 12 条。
> 基线 = 提交 973231d 的 12 个 .bat，用扩展后的 `tools/check-bat.py` 先跑旧脚本集记录命中，再逐条修复到全绿。
> 本机为 Linux，未真跑 .bat；结论来自静态规则 + 逐行人工复核，不声称真机验证。

## 一、新规则清单（在原有 6 条规则之上追加）

| 规则 | 内容 | 近似性说明 |
|---|---|---|
| R1 block-path-var | 括号块内出现路径/用户输入变量展开：`%TARGET% %ROOT% %PATCHROOT% %NEWROOT% %BKROOT% %CURVER% %SEL% %SRC% %DEST% %BUNDLE% %DBBACKUP% %WORK% %ZIP% %PKG% %IB_ROOT% %IB_LOG% %IB_SELF% %IB_DIR% %IB_DET_1% %IB_DET_2% %LOGINARG% %~dp0 %1-%9 %~1-%~9 %*`。块界定用括号配平扫描（先剔除引号内与 `^` 转义内容） | 变量集合为白名单制：不在名单内的变量（如 `%IBRC%` 退出码、`%APP_PORT%` 端口号）不报——它们不是路径、值里不会出现 `)`。`%%F` 等 for 变量不报（不是 `%VAR%` 展开形态） |
| R2 exit-no-guard | `exit`/`exit /b` 前 3 行内无 pause 且文件无 `IB_AUTO` 豁免标记 | **近似规则，宁可标注人工复核**：文件含 `IB_AUTO` 字面量即整体豁免（统一契约下 IB_AUTO=1 不 pause、人工运行有 IB_WRAPPED 外壳 + `:ib_pause` 兜底）；子程序的 `exit /b 0` 是预期误报来源，靠契约标记整体消解，不逐条豁免 |
| R3 errorlevel-in-block | 括号块内出现 `if errorlevel` / `if not errorlevel` 写法 | 无近似：块内 errorlevel 在整块解析时已被定死（台账第 3/5 条），任何块内出现都必须改 |
| R4 encoding-crlf | 非 ASCII 字节（原 non-ascii 规则）或存在非 CRLF 换行（原 lf-line-endings 的加强版：混合换行也报，逐文件一条并给坏行数） | 精确检查 |
| R5 temp-literal | 非注释行出现 `%TEMP%` 字面量 | 精确检查（注释行不报：规范文档需要提及它） |
| R6 unquoted-path | 路径变量未加引号的高风险用法 | **近似规则**：去引号后按空白切 token，token 形如 `%VAR%\...` 且不以引号开头、且该行不是 `set` 赋值行才报。引号内拼接、for 变量、纯变量引用不在此列；带 cmd 转义引号的复合参数（如 schtasks /TR）扫描看不见引号，会误报，靠豁免处理（见下） |

| R7 setp-file-read | `set /p VAR=<文件` 用重定向读文件内容 | 精确检查。LF-only 文件上 set /p 会读过第一行、把换行读进变量值，后续 `if "%VAR%"==` 比较变成跨行命令直接语法错误；读文件首行一律用 PowerShell Get-Content -First 1 的 for /f 写法（upgrade.bat 既有范式） |
| R8 input-bare-compare | 控制台输入变量（set /p 提示读入的 VAR）出现在裸 `if "%VAR%"==` / `=="%VAR%"` 比较里 | 按文件收集 set /p 提示读入的变量名后逐行比对；哨兵形式 `"%VAR%x"=="x"` 不命中（值后紧跟的不是闭合引号）。用户键入/拖拽的路径可带尾随反斜杠，反斜杠紧贴闭合引号会被 cmd 解析器误读，比较行报「was unexpected at this time」 |

豁免机制：行尾或上方 4 行内注释块中出现 `rem IB-CHECK-EXEMPT <规则号>` 即豁免该行该规则；每条豁免必须在本记录与重检清单里写理由。

原有规则（paren-in-block / amp-chain-in-block / echo-pipe-test / ps-same-file-pipeline / non-ascii / lf-line-endings）保持不变，基线与修复后均为 0 命中。

## 二、基线命中（旧脚本集，扩展规则首跑）

总计 **101 条**：

| 规则 | 命中 | 分布 |
|---|---|---|
| R1 | 68 | apply-patch 5、backup 5、install 14、migrate-export 2、migrate-import 16、reset-admin 3、restore 7、rollback 4、start 1、uninstall 6、upgrade 5 |
| R2 | 7 | apply-patch 1、backup 3、install 2、upgrade 1——逐条复核均为子程序 `exit /b 0`（`:log` 尚不存在时的 `:show_admin_pwfile`、`:write_status`、`:port_listening`、`:show_pg_log` 出口），属 R2 近似规则的预期误报 |
| R3 | 21 | apply-patch 1、backup 1、install 2、migrate-export 2、migrate-import 6、restore 2、rollback 2、start 1、stop 1、uninstall 1、upgrade 2 |
| R4 | 0 | 旧脚本集编码与换行本来合规 |
| R5 | 0 | 旧脚本集无 `%TEMP%` 字面量（临时文件都走安装目录 `tmp\`） |
| R6 | 5 | install 1（schtasks /TR，误报，见豁免）、reset-admin 1、restore 1、rollback 1、upgrade 1（后 4 条均为报错信息里未加引号的路径回显） |

## 三、修复方式与结果

修复后：**0 命中**（`python3 tools/check-bat.py packaging/windows` 输出 `OK - 12 file(s) clean`）。

- R1（68）+ R3（21）：全部以**结构改造**消除，无一使用豁免。统一手法：括号块改 goto/call 子程序结构——`if cond (`…`)` 改为 `if not cond goto :label` + 顶层语句 + `:label`；嵌套的 `if errorlevel` 改为块外单行判断；需要在块内显示的路径先在顶层用 `call :log` 打出，再进块只用不含路径的提示文。逐文件落点见重检清单。
- R2（7）：统一脚本契约落地后 12 个文件全部含 `IB_AUTO` 处理与 `:ib_pause`，按规则定义整体豁免；7 条基线命中经复核均为子程序出口误报，无真实「无 pause 直接退出」新增问题（原各脚本的错误出口本来就有 `call :ib_pause`）。
- R6（5）：3 条真实项已修（reset-admin / restore / rollback / upgrade 的报错回显路径补引号——其中 restore、rollback、upgrade 三处同时位于被 goto 化的括号块内，一并处理；reset-admin 的 `:failed` 段 `%ROOT%\logs` 补引号）；2 条误报豁免如下。

## 四、豁免记录（共 2 条，逐条理由）

1. `install.bat` schtasks 创建计划任务行，R6 豁免（上一行注释块标记）：`/TR "\"%ROOT%\backup.bat\" auto"` 是一个完整的引号参数，内层引号是 cmd 转义形式 `\"`，R6 的去引号扫描无法识别这种嵌套引号，属规则近似性的已知误报；该行路径实际已加引号。
2. `migrate-export.bat` 分卷 PowerShell 命令行，R6 豁免（上一行注释块标记）：该行是生成 `merge-migration.bat` 的模板字符串数据，其中唯一的路径 token `%~dp0` 位于**生成文件**里的 `set "DIR=%~dp0"` 引号内，不是本脚本在执行的未引号命令；本行自身的目标路径全部经 `$env:IB_ZIP` 环境变量传递。

## 五、终验修复轮追加（2026-11-24，1.1.0 CI 终验失败驱动）

背景：打包轮 CI 终验（公开仓 Windows run 45362168304）首次真跑恶劣路径矩阵，N1/N3 失败暴露两类静态检查未覆盖的缺陷，遂追加 R7/R8 两条规则（`tools/check-bat.py`）：

- **R7 命中（修复前）**：`apply-patch.bat:158` 版本门 `set /p CURVER=<version.txt`。version.txt 由 Linux 打包以 LF 写出，set /p 读入带换行的值后，版本门 `if` 比较成为跨行命令，脚本在目标检测后直接报「The syntax of the command is incorrect.」死亡（N1 无参 ×3 实测）。修复：新增 `:detect_version` 子程序，版本首行改走 PowerShell `(Get-Content | Select-Object -First 1)` 的 for /f 回读（与 upgrade.bat / migrate-*.bat 既有范式一致）。
- **R8 命中（修复前）**：`apply-patch.bat:94`（参数路由非空判断）、`apply-patch.bat:114`（`:target_check` 空判断）、`restore.bat:74/79`（SEL 空判断 ×2），共 4 条。实测对应 N3-1.0.1：手动输入分支的 `if "%TARGET%"=="" goto :target_missing` 报 `="==" " goto :target_missing was unexpected at this time.`。修复：全部改哨兵后缀形式 `if "%VAR%x"=="x"`（restore.bat 两处同改），值尾的反斜杠不再紧贴闭合引号。
- **负控**：对修复前版本（git HEAD 原文件）跑扩展后 check-bat，恰命中上述 5 条（R7×1、R8×4），不多不少；修复后 `packaging/windows` 12 文件 **0 命中**。
- 同轮另修（非 check-bat 域，记此备查）：apply-patch 目标校验写死 1.1.0 布局（要求 version.txt）致 1.0.4/1.0.1 真实老安装被拒——校验改双标记（jar + config），版本识别改 `:detect_version` 三路（version.txt 首行 / 老布局指纹：1.0.4 = `runtime\pgsql\bin\msvcp140.dll` 在且 `runtime\vc_redist.x64.exe` 不在，1.0.1 反之；老 jar 的 MANIFEST 与 pom.properties 均为 0.1.0-SNAPSHOT，无可用版本字符串，此为实测依据）；rollback bundle 的 version.txt 拷贝改条件拷贝。矩阵侧修 Run-BatAuto 的 cmd /c 引号（整行外包一层）、N1 夹具改 rc4 全量包、Install-Fresh 向 stdin 喂 Y 答复老版本 choice 提问、N1 新增 manual 方式专测手动分支。详见 `docs/脚本重检清单-1.1.0.md` §7。
