#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
sync-guard.py — 源仓 → 公开仓同步守门（台账第 13 条根治，2026-11-25）

背景：1.1.0 终验第二次失败的根因是同步时的「脱敏替换」把
packaging/windows/apply-patch.bat 第 339 行的变量引用 %DBPWD% 改成了
字面量 REDACTED-MARKER，pg_dump 拿到的口令恒为 "REDACTED-MARKER"，认证必然失败，
且只能在 Windows 终验时暴露。裁定（723dqcfg + 6456khuq）：
脱敏改为「发现即拦截」，绝不静默替换可执行代码。

本工具三件事：
  scan <tree>
      扫描代码文件，命中以下任一即以「文件:行号」报错并以退出码 1 中止：
      (a) 脱敏标记字面量 REDACTED-MARKER / MASKED-MARKER（任何代码文件）；
      (b) 脚本/配置（.bat .ps1 .sh .yml .yaml .properties）中疑似字面量
          口令/密钥赋值（password/api-key/secret/token 等后跟非变量、
          非占位符、非正则片段的字面值），且不在允许清单内。
      同步流程必须先过 scan，命中即中止同步、由人修源仓后再同步。
  compare <src> <dst>
      同步后逐文件 sha256 比对两仓代码文件：差异必须全部落在
      tools/sync-legacy-differences.txt 登记的历史脱敏差异集内；
      出现清单外的新差异 → 退出码 1（同步失败、不推送）。
      清单内文件若已变一致，会提示条目过期（不失败）。
  selftest
      内置用例：干净树通过；故意放入假口令 / REDACTED-MARKER 标记必须被
      scan 拦截并指出文件行号；compare 必须能检出清单外差异。

允许清单 tools/sync-guard-allowlist.txt：每行一个被放行的字面值
（测试虚构值/占位符），# 开头为注释。新增放行必须随代码评审说明理由。
结构性跳过（不算字面量口令）：值以 % $ { \\ 开头、含 $( 的表达式、
空值、true/false/null、__XXX__ 占位符、CHANGE_ME 类占位符。
"""
import hashlib
import pathlib
import re
import sys
import tempfile

CODE_EXTS = {'.bat', '.ps1', '.sh', '.java', '.ts', '.vue', '.yml', '.yaml',
             '.sql', '.mjs', '.js', '.py', '.xml', '.properties'}
SECRET_SCAN_EXTS = {'.bat', '.ps1', '.sh', '.yml', '.yaml', '.properties'}
EXCLUDE_DIRS = {'node_modules', 'target', 'dist', '.git', '.tmp', 'staging',
                'cache', '__pycache__', 'toolchain'}  # toolchain = 本机开发工具链，不进任何仓库、不参与同步
MARKERS = ('<redac' + 'ted>', '[MAS' + 'KED]')  # 拼接构造，避免工具自身含标记字面量被自检命中
SECRET_RE = re.compile(
    r'(?i)(pgpassword|password|passwd|api[-_]?key|secret|access[-_]?token)'
    r'\s*[:=]\s*"?([^\s"\'<>]+)')


def iter_code_files(tree):
    tree = pathlib.Path(tree)
    for p in sorted(tree.rglob('*')):
        if not p.is_file():
            continue
        rel = p.relative_to(tree)
        if EXCLUDE_DIRS & set(rel.parts):
            continue
        if p.suffix.lower() in CODE_EXTS:
            yield p, rel.as_posix()


def load_allowlist(tree):
    f = pathlib.Path(tree) / 'tools' / 'sync-guard-allowlist.txt'
    vals = set()
    if f.exists():
        for line in f.read_text(encoding='utf-8').splitlines():
            line = line.strip()
            if line and not line.startswith('#'):
                vals.add(line)
    return vals


def is_structural_skip(v):
    if v.startswith(('%', '$', '{', '\\')):
        return True
    if '$(' in v:
        return True
    if v in ('""', "''", 'true', 'false', 'null', '~'):
        return True
    if re.fullmatch(r'__.*__', v):
        return True
    if 'CHANGE_ME' in v:
        return True
    return False


def scan(tree, allowlist=None):
    if allowlist is None:
        allowlist = load_allowlist(tree)
    problems = []
    for p, rel in iter_code_files(tree):
        try:
            text = p.read_bytes().decode('utf-8', 'replace')
        except OSError:
            continue
        for i, line in enumerate(text.splitlines(), 1):
            for mk in MARKERS:
                if mk in line:
                    problems.append(
                        f'{rel}:{i}: 脱敏标记 {mk} 出现在代码文件中'
                        f'（同步禁止静默替换，须回源仓修复）')
            if p.suffix.lower() in SECRET_SCAN_EXTS:
                m = SECRET_RE.search(line)
                if m:
                    v = m.group(2)
                    if not is_structural_skip(v) and v not in allowlist:
                        problems.append(
                            f'{rel}:{i}: 疑似字面量口令/密钥赋值'
                            f'（{m.group(1)}，值长 {len(v)}）'
                            f'——同步拦截：口令必须运行时生成或经变量/环境注入，'
                            f'虚构测试值请登记允许清单并说明理由')
    return problems


def sha256(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def load_legacy(tree):
    f = pathlib.Path(tree) / 'tools' / 'sync-legacy-differences.txt'
    vals = set()
    if f.exists():
        for line in f.read_text(encoding='utf-8').splitlines():
            line = line.strip()
            if line and not line.startswith('#'):
                vals.add(line)
    return vals


def compare(src, dst):
    src, dst = pathlib.Path(src), pathlib.Path(dst)
    legacy = load_legacy(src)
    src_files = {rel: p for p, rel in iter_code_files(src)}
    dst_files = {rel: p for p, rel in iter_code_files(dst)}
    problems, stale = [], []
    for rel in sorted(set(src_files) & set(dst_files)):
        if sha256(src_files[rel]) != sha256(dst_files[rel]):
            if rel not in legacy:
                problems.append(
                    f'{rel}: 两仓代码文件不一致且不在历史差异清单内'
                    f'（同步不得改写代码文件，须逐字节一致）')
        elif rel in legacy:
            stale.append(rel)
    only_src = sorted(set(src_files) - set(dst_files))
    only_dst = sorted(set(dst_files) - set(src_files))
    return problems, stale, only_src, only_dst


def selftest():
    ok = True

    def check(name, cond):
        nonlocal ok
        print(('PASS' if cond else 'FAIL'), '-', name)
        ok = ok and cond

    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        (root / 'tools').mkdir()
        (root / 'tools' / 'sync-guard-allowlist.txt').write_text(
            '# test\nTest-Only-Pwd-0001\n', encoding='utf-8')
        (root / 'a.bat').write_text(
            'set "PGPASSWORD=%DBPWD%"\r\n', encoding='utf-8')
        (root / 'b.yml').write_text(
            'password: Test-Only-Pwd-0001\n', encoding='utf-8')
        check('干净树 scan 通过', scan(root) == [])

        (root / 'c.yml').write_text(
            'spring:\n  password: FakeSecret-12345\n', encoding='utf-8')
        probs = scan(root)
        check('假口令被拦截', any('c.yml:2' in x for x in probs))
        (root / 'c.yml').unlink()

        (root / 'd.bat').write_text(
            'set "PGPASSWORD=' + '<redac' + 'ted>"\r\n', encoding='utf-8')
        probs = scan(root)
        check('REDACTED-MARKER 标记被拦截', any('d.bat:1' in x for x in probs))
        (root / 'd.bat').unlink()

        other = root / 'other'
        (other / 'tools').mkdir(parents=True)
        (other / 'a.bat').write_text(
            'set "PGPASSWORD=%DBPWD%"\r\n', encoding='utf-8')
        (other / 'b.yml').write_text(
            'password: Test-Only-Pwd-0001\n', encoding='utf-8')
        probs, _, _, _ = compare(root, other)
        check('一致树 compare 通过', probs == [])
        (other / 'a.bat').write_text('rem changed\r\n', encoding='utf-8')
        probs, _, _, _ = compare(root, other)
        check('清单外差异被 compare 检出', any('a.bat' in x for x in probs))
    print('SELFTEST', 'ALL PASS' if ok else 'FAILED')
    return 0 if ok else 1


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    cmd = argv[1]
    if cmd == 'scan' and len(argv) == 3:
        probs = scan(argv[2])
        for x in probs:
            print('::error::' + x)
        if probs:
            print(f'sync-guard scan: {len(probs)} 处命中，同步中止')
            return 1
        print('sync-guard scan: 通过（无脱敏标记、无未登记字面量口令）')
        return 0
    if cmd == 'compare' and len(argv) == 4:
        probs, stale, only_src, only_dst = compare(argv[2], argv[3])
        for x in probs:
            print('::error::' + x)
        for rel in stale:
            print(f'提示： {rel} 已两仓一致，历史差异清单条目可移除')
        for rel in only_src:
            print(f'提示： 仅源仓有 {rel}')
        for rel in only_dst:
            print(f'提示： 仅公开仓有 {rel}')
        if probs:
            print(f'sync-guard compare: {len(probs)} 处清单外差异，同步失败')
            return 1
        print('sync-guard compare: 通过（差异均在历史清单内）')
        return 0
    if cmd == 'selftest':
        return selftest()
    print(__doc__)
    return 2


if __name__ == '__main__':
    sys.exit(main(sys.argv))
