#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
check-bat.py - 批处理脚本静态检查（迭代小组平台，1.0.3 起进 CI）。

背景：Windows 实测四轮里，安装脚本的缺陷全是 cmd 解析层面的坑，
在 Linux 开发环境里跑不出来，只能靠静态规则拦。本脚本扫描
packaging/windows 下的 .bat，发现以下已知坑即报错（退出码 1）：

  paren-in-block        if/else/for 代码块内的 echo 文本含未转义括号：
                        cmd 解析块时把 echo 里的 ) 当作块结束，脚本
                        必然闪退（install.bat 两处实测闪退）。
  amp-chain-in-block    单行括号块里用 & 串联命令：& 后面的 if 会把
                        exit /b 吞进内层条件，失败被掩盖成成功
                        （start.bat 实测掩盖应用启动失败）。
  echo-pipe-test        用 echo %VAR% | findstr 判断变量：echo 会把
                        竖线前的空格一并输出，判断永远命中
                        （1.0.1 实测：无空格路径误报有空格）。
  ps-same-file-pipeline PowerShell 里 Get-Content 'x' | ... |
                        Set-Content 'x' 对同一文件边读边写：报
                        「文件正由另一进程使用」且静默不替换
                        （__INSTALL_DIR__ 未替换致服务起不来）。
                        读入必须先用括号完整求值：(Get-Content 'x') | ...
  non-ascii             脚本含非 ASCII 字节：cmd 按本机代码页读脚本，
                        内容会乱码；脚本一律纯 ASCII（既定约定）。
  lf-line-endings       只有 LF 没有 CRLF：cmd 对 goto/标签解析不可靠。

用法：python3 tools/check-bat.py <file-or-dir> [more...]
目录会递归扫 *.bat。无问题退出 0，有发现退出 1 并逐行打印。

2026-11-24 脚本重检追加规则（R1-R6，详见 docs/脚本重检清单-1.1.0.md
与 docs/check-bat-新规则清单与命中修复记录.md）：

  R1 block-path-var     括号块内出现路径/用户输入变量展开
                        （%TARGET% %ROOT% %PATCHROOT% %NEWROOT% %BKROOT%
                        %CURVER% %SEL% %SRC% %DEST% %BUNDLE% %DBBACKUP%
                        %WORK% %ZIP% %PKG% %IB_ROOT% %IB_LOG% %IB_SELF%
                        %IB_DIR% %IB_DET_1% %IB_DET_2% %LOGINARG%
                        %~dp0 %1-%9 %~1-%~9 %*）：块在解析时整块展开，
                        值里的 ) 会提前结束块导致闪退（台账第 12 条）。
                        块的界定用括号配平扫描（引号/转义已剔除）。
  R2 exit-no-guard      exit / exit /b 前无 pause 且文件无 IB_AUTO
                        豁免标记：近似规则——文件含 IB_AUTO 字面量即
                        整体豁免（统一契约下 IB_AUTO=1 不 pause、
                        人工运行有 IB_WRAPPED 外壳 + :ib_pause）；
                        否则 exit 前 3 行内无 pause 字样即报，标注
                        人工复核。宁可多报，不误杀：子程序 exit 多为
                        误报，靠 IB_AUTO 标记整体消解。
  R3 errorlevel-in-block 括号块内出现 if errorlevel 写法：块内
                        errorlevel 在整块解析时已被定死，必须先存
                        变量或改 goto 结构（台账第 3/5 条）。
  R4 encoding-crlf      非 ASCII 字节（原 non-ascii）或存在非 CRLF
                        换行（原 lf-line-endings 的加强版：混合换行
                        也报，逐文件一条并给出坏行数）。
  R5 temp-literal       非注释行出现 %TEMP% 字面量：临时目录必须用
                        脚本旁/安装目录下专属目录（tmp\），中文 TEMP
                        与权限不可控（冒烟档实测口径）。
  R6 unquoted-path      路径变量未加引号的高风险用法（近似规则）：
                        token starts with a path variable followed by a backslash,
                        且不以引号开头、且该行不是 set 赋值行，即报。
                        引号内的拼接、for 变量 %%X、纯变量引用不在
                        此列——近似性见重检清单文档。

豁免：行尾或上一行加注释 rem IB-CHECK-EXEMPT <规则号> 可豁免
该行该规则（命令本身不能带尾随注释时用上一行形式），必须在
重检清单里逐条写理由。
"""
import re
import sys
from pathlib import Path

ESC = "\x00"  # ^x 转义后的占位符


def unescape(line: str) -> str:
    """把 ^x 转义序列替换成占位符，使 ^( ^) 不参与括号计数。"""
    out = []
    i = 0
    while i < len(line):
        if line[i] == "^" and i + 1 < len(line):
            out.append(ESC)
            i += 2
        else:
            out.append(line[i])
            i += 1
    return "".join(out)


def strip_quotes(line: str) -> str:
    """去掉双引号内的内容（引号内的括号不参与 cmd 块结构计数）。"""
    out = []
    in_q = False
    for ch in line:
        if ch == '"':
            in_q = not in_q
            out.append(" ")
        elif in_q:
            out.append(" ")
        else:
            out.append(ch)
    return "".join(out)


def is_comment(line: str) -> bool:
    t = line.strip().lower()
    return t.startswith("rem ") or t == "rem" or t.startswith("::")


def check_file(path: Path):
    raw_bytes = path.read_bytes()
    findings = []
    try:
        text = raw_bytes.decode("ascii")
    except UnicodeDecodeError:
        findings.append((0, "non-ascii", "file contains non-ASCII bytes"))
        text = raw_bytes.decode("ascii", errors="replace")
    if b"\n" in raw_bytes and b"\r\n" not in raw_bytes:
        findings.append((0, "lf-line-endings", "LF-only line endings; batch files need CRLF"))

    lines = text.replace("\r\n", "\n").split("\n")
    depth = 0
    for lineno, line in enumerate(lines, 1):
        if is_comment(line):
            continue
        un = unescape(line)
        noq = strip_quotes(un)

        # --- echo-pipe-test: echo ... | ... -------------------
        if re.search(r"(?i)\becho\b[^|]*\|", un) and "powershell" not in line.lower():
            findings.append((lineno, "echo-pipe-test",
                             "echo piped into another command; test variables without echo (its trailing space corrupts the test)"))

        # --- ps-same-file-pipeline ----------------------------
        m = re.search(r"Get-Content\s+'([^']+)'\s*\|", line)
        if m and "Set-Content" in line:
            target = m.group(1)
            before = line[:m.start()].rstrip()
            if not before.endswith("(") and ("Set-Content" in line and target in line.split("Set-Content", 1)[1]):
                findings.append((lineno, "ps-same-file-pipeline",
                                 f"Get-Content '{target}' piped into Set-Content on the same file without a parenthesized full read"))

        # --- amp-chain-in-block: ( ... & ... ) on one line -----
        if re.search(r"\([^)]*&", noq):
            findings.append((lineno, "amp-chain-in-block",
                             "'&' chain inside a single-line parenthesized block; split into a multi-line block so exit/if cannot be swallowed"))

        # --- paren-in-block: echo text with bare parens --------
        stripped = un.lstrip("@ ").lower()
        if depth >= 1 and re.search(r"(?i)\becho\b", un):
            after = re.split(r"(?i)\becho\b", un, maxsplit=1)[1]
            if "(" in after or ")" in after:
                findings.append((lineno, "paren-in-block",
                                 "echo text contains unescaped parentheses inside a parenthesized block; cmd will end the block here and the script aborts"))
        # single-line block: ( ... echo ... ( ... )
        m2 = re.search(r"\(", noq)
        if m2 and depth == 0:
            rest = un[m2.start():]
            em = re.search(r"(?i)\becho\b", rest)
            if em:
                after = rest[em.end():]
                # one '(' is the block closer pair; a second bare '(' in echo text is a defect
                if after.count("(") >= 1 and ")" in after:
                    tail = after
                    # exclude the common "echo ...(text)..." where parens are balanced inside the text itself is still a defect in cmd blocks
                    if re.search(r"\([^)]*\)", tail):
                        findings.append((lineno, "paren-in-block",
                                         "echo text contains parentheses inside a single-line block; escape as ^( ^) or drop them"))

        # --- update block depth from quote-stripped line -------
        depth += noq.count("(") - noq.count(")")
        if depth < 0:
            depth = 0
    return findings


R1_VARS = {
    "TARGET", "ROOT", "PATCHROOT", "NEWROOT", "BKROOT", "CURVER",
    "SEL", "SRC", "DEST", "BUNDLE", "DBBACKUP", "WORK", "ZIP", "PKG",
    "IB_ROOT", "IB_LOG", "IB_SELF", "IB_DIR", "IB_DET_1", "IB_DET_2",
    "LOGINARG",
}
R1_RE = re.compile(r"%([A-Za-z_][A-Za-z0-9_]*)%|%~dp0|%~[1-9]|%[1-9]|%\*",
                   re.IGNORECASE)


def r1_hit(line: str) -> bool:
    for m in R1_RE.finditer(line):
        if m.group(1) is None:
            return True  # %~dp0 / %~1 / %1 / %*
        if m.group(1).upper() in R1_VARS:
            return True
    return False


def exempt(line: str, rule: str, prev: str = "") -> bool:
    # prev may carry several recent lines (a comment block); any of
    # them holding the marker exempts this line.
    for cand in (line, prev):
        m = re.search(r"IB-CHECK-EXEMPT\s+([A-Z0-9 ]+)", cand)
        if m and rule in m.group(1).split():
            return True
    return False


def check_file_v2(path: Path):
    """R1-R6 (2026-11-24). Returns findings like check_file."""
    raw = path.read_bytes()
    findings = []
    # R4: mixed / LF-only line endings (non-ascii is reported by v1 rules)
    total_lf = raw.count(b"\n")
    total_crlf = raw.count(b"\r\n")
    if total_lf != total_crlf:
        findings.append((0, "R4",
                         f"{total_lf - total_crlf} line(s) do not end with CRLF"))
    text = raw.decode("ascii", errors="replace")
    lines = text.replace("\r\n", "\n").split("\n")
    has_ib_auto = "IB_AUTO" in text
    depth = 0
    prev_lines = []
    for lineno, line in enumerate(lines, 1):
        prev_line = "\n".join(prev_lines[-4:])
        if is_comment(line):
            # comments still count for nothing; skip all rules
            un = unescape(line)
            noq = strip_quotes(un)
            depth += noq.count("(") - noq.count(")")
            if depth < 0:
                depth = 0
            prev_lines.append(line)
            continue
        un = unescape(line)
        noq = strip_quotes(un)
        # R5: %TEMP% literal
        if "%temp%" in line.lower() and not exempt(line, "R5", prev_line):
            findings.append((lineno, "R5",
                             "%TEMP% literal; use a dedicated tmp dir next to the script/install instead"))
        # R1: path/user-input variable expanded inside a paren block
        if depth >= 1 and r1_hit(un) and not exempt(line, "R1", prev_line):
            findings.append((lineno, "R1",
                             "path/user-input variable expanded inside a parenthesized block; move it out (goto/call) or store a flag first"))
        # R3: if errorlevel inside a block
        if depth >= 1 and re.search(r"(?i)\bif\s+(not\s+)?errorlevel\b", un) and not exempt(line, "R3", prev_line):
            findings.append((lineno, "R3",
                             "if errorlevel inside a parenthesized block reads the value frozen at block parse time; store it in a variable first or restructure with goto"))
        # R2: exit without a nearby pause and no IB_AUTO marker in file
        if not has_ib_auto and re.match(r"(?i)\s*exit(\s+/b)?(\s|$)", line) and not exempt(line, "R2", prev_line):
            prev = "\n".join(lines[max(0, lineno - 4):lineno - 1]).lower()
            if "pause" not in prev:
                findings.append((lineno, "R2",
                                 "exit without a preceding pause and no IB_AUTO exemption marker in file (manual review: subroutine exits are expected false positives)"))
        # R6: unquoted path-variable token (approximation, see docstring)
        stripped = line.strip()
        if not re.match(r"(?i)set\s", stripped) and not exempt(line, "R6", prev_line):
            for tok in noq.split():
                t = tok.lstrip("(")
                if re.match(r"%[A-Za-z_~][^%]*%\\", t):
                    findings.append((lineno, "R6",
                                     "path variable used unquoted in a command token (approximation: quoted spans and set-lines are excluded)"))
                    break
        depth += noq.count("(") - noq.count(")")
        if depth < 0:
            depth = 0
        prev_lines.append(line)
    return findings


def main(argv):
    files = []
    for arg in argv[1:]:
        p = Path(arg)
        if p.is_dir():
            files.extend(sorted(p.rglob("*.bat")))
        elif p.is_file():
            files.append(p)
        else:
            print(f"check-bat: not found: {arg}", file=sys.stderr)
            return 2
    if not files:
        print("check-bat: no .bat files given", file=sys.stderr)
        return 2
    total = 0
    for f in files:
        for lineno, rule, msg in check_file(f) + check_file_v2(f):
            total += 1
            loc = f"{f}:{lineno}" if lineno else str(f)
            print(f"{loc}: [{rule}] {msg}")
    if total:
        print(f"check-bat: {total} finding(s) in {len(files)} file(s)")
        return 1
    print(f"check-bat: OK - {len(files)} file(s) clean")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
