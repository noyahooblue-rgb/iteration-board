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
| R9 sums-encoding | 内嵌 PowerShell 用 Get-Content 读 SHA256SUMS.txt 而未显式 `-Encoding UTF8` | 精确检查（同行含 SHA256SUMS 与 Get-Content 且无 -Encoding UTF8 即报）。该文件由打包机以 UTF-8（无 BOM）生成，Windows PowerShell 5.1 默认按 ANSI 代码页解码，中文文件名（docs\\用户手册.md、docs\\部署手册.md、补丁说明.txt）必然乱码、补丁自校验误报缺失中止；同机自产自读文件（默认编码自洽）不在此列 |
| R8 input-bare-compare | 控制台输入变量（set /p 提示读入的 VAR）出现在裸 `if "%VAR%"==` / `=="%VAR%"` 比较里 | 按文件收集 set /p 提示读入的变量名后逐行比对；哨兵形式 `"%VAR%x"=="x"` 不命中（值后紧跟的不是闭合引号）。用户键入/拖拽的路径可带尾随反斜杠，反斜杠紧贴闭合引号会被 cmd 解析器误读，比较行报「was unexpected at this time」 |
| R10 echo-digit-redirect | `echo <数字>>` 形态（数字紧贴 `>`） | 精确检查。`echo 1>"file"` 的 `1>` 是句柄重定向：echo 无参数，文件里写进的是 ECHO 状态文本；`0>` 只清空文件。写数字一律重定向在前：`>"file" echo 1`（台账第 14 条⑧） |
| R11 self-copy-call | 脚本把自身拷到别处（copy 源为脚本自身文件名、目标为 %变量%）再 call 该变量时，call 行不含 `exit /b` | 近似规则：以 copy 行登记的副本变量为 call 目标变量。cmd 在 call 返回后按字节偏移续读原文件，原文件可能已被副本执行期间的动作覆盖成另一版本；call 与退出同行（整行预解析）才安全（台账第 14 条⑧） |

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
- 同轮另修（非 check-bat 域，记此备查）：apply-patch 目标校验写死 1.1.0 布局（要求 version.txt）致 1.0.4/1.0.1 真实老安装被拒——校验改双标记（jar + config），版本识别改 `:detect_version` 三路（version.txt 首行 / 老布局指纹：1.0.4 = `runtime\pgsql\bin\msvcp140.dll` 在且 `runtime\vc_redist.x64.exe` 不在，1.0.1 反之；老 jar 的 MANIFEST 与 pom.properties 均为 0.1.0-SNAPSHOT，无可用版本字符串，此为实测依据）；rollback bundle 的 version.txt 拷贝改条件拷贝。矩阵侧修 Run-BatAuto 的 cmd 启动引号、N1 夹具改 rc4 全量包、Install-Fresh 向 stdin 喂 Y 答复老版本 choice 提问、N1 新增 manual 方式专测手动分支。Run-BatAuto 引号一波两折，如实记：第一版修复把整行作单个参数外包一层引号，复跑（run 45377864212 / 45378969080）实测仍失败——PowerShell 对原生命令的单个字符串参数二次加引号，连程序路径都被拆断（`'""C:\ibnasty\patch' is not recognized`），且矩阵步骤 45 分钟超时被中途终止；最终改为经 Process API 以 ProcessStartInfo.Arguments 原样串启动 cmd.exe（不经 PowerShell 参数层），矩阵步骤超时同步放宽到 90 分钟。详见 `docs/脚本重检清单-1.1.0.md` §7。
- **R9 追加（同轮第三批，run 45389317819 暴露）**：矩阵引号修复后补丁首次真正跑通校验步，8 次补丁运行（N1×7 + N3-1.0.4）全部倒在 SHA256SUMS 自校验——三处 Get-Content 未指定编码，中文文件名乱码误报缺失（见 R9 行）。修复：apply-patch.bat 三处（校验 / rollback bundle 收集 / 拷入复验，行 186/222/265）统一加 `-Encoding UTF8`。负控：修复前文件跑新规恰 R9×3，修复后 12 文件 0 命中。同轮另两修：手动分支空输入（EOF 直接回车）在哨兵比较处仍报解析错——两处提示点改「预置标记值 `__IB_NONE__` + 哨兵后缀比较」，使 strip 与比较的展开永不为空值；矩阵 N3 夹具安装目录去掉空格（`fixture-<版本>`）——1.0.1 老 install.bat 自身在带空格路径下、choice 答复后仍有潜伏解析错（老版本脚本的历史局限，产品口径本就要求安装路径无空格；N3 的被测对象是补丁而非老安装器）。附带观察（未触发、未修，留档）：migrate-import 读 manifest.json 同为跨机文件，若迁移源/目标机默认编码不同且路径含中文有同类风险，后续版本可一并显式 UTF8。

## 六、修复轮 6 追加（2026-11-26，Kiro 终检阻塞项驱动）

背景：修复轮 5 给 rollback.bat 建的「结果文件退出码契约」被 Kiro 终检读码 + 字节偏移核算证明不成立（台账第 14 条⑧改写）：写结果的两行 `echo 1>"…"` / `echo 0>"…"` 是句柄重定向而非写数字；第一实例的 call 与退出分行书写，call 返回后续读的是已被覆回旧版的原文件。遂追加 R10/R11 两条规则（`tools/check-bat.py`）：

- **R10 命中（修复前）**：`rollback.bat:109`（副本进入写 1）、`rollback.bat:212`（完成写 0），共 2 条。修复：两处改重定向在前写法 `>"%IB_RB_RESULT%" echo 1/0`（if defined 守卫改 goto 结构）。
- **R11 命中（修复前）**：`rollback.bat:101`（`call "%IB_RB_SELF%" %IB_ARGS%` 与退出分行），共 1 条。修复：call 与退出合并为同一行 `call "%IB_RB_SELF%" %IB_ARGS% & (for /f … do exit /b %%R) & exit /b 1`，整行在执行前已被 cmd 完整解析；结果文件缺失/读不出时退出 1，不再回退 ERRORLEVEL。
- **负控**：对修复前版本（修复轮 5 终版 rollback.bat）跑新规，恰命中 R10×2 + R11×1，不多不少；修复后 `packaging/windows` 12 文件 **0 命中**。运行级负控由矩阵 N5F 承担（回滚失败用例：旧写法退出码为 0、结果文件内容为 ECHO 状态文本，两条断言均能拦住）。
