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
        for lineno, rule, msg in check_file(f):
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
