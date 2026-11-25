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
      零差异强制集（tools/sync-mandatory-identical.txt 的 glob 模式，
      覆盖迁移 SQL、补丁/安装脚本等「可执行或需要被执行的文件」）：
      命中文件必须在两仓都存在且逐字节一致，且不得同时出现在
      历史差异清单里——任一不满足 → 退出码 1。强制集源于修复轮 4
      裁定：已发布迁移文件连注释都必须冻结（Flyway 校验和覆盖全文，
      注释差异曾使公开仓构建的应用对存量库拒绝启动）。
  write-hashes <tree>
      按强制集把 <tree> 内各文件的 sha256 写入该树
      tools/sync-mandatory-hashes.txt（同步时以源仓为准生成，
      文件本身随同步逐字节复制到公开仓）。
  verify-hashes <tree>
      校验 <tree> 内强制集文件与 tools/sync-mandatory-hashes.txt
      登记的哈希逐一相符（缺失/多出/哈希不符 → 退出码 1）。
      公开仓 CI 无法直访源仓，故在 bat-check 档跑此命令：登记表是
      同步时由源仓生成、经 compare 把关后随仓发布的，CI 据此证明
      公开仓强制集文件未偏离同步时的源仓状态。
  selftest
      内置用例：干净树通过；故意放入假口令 / REDACTED-MARKER 标记必须被
      scan 拦截并指出文件行号；compare 必须能检出清单外差异。

允许清单 tools/sync-guard-allowlist.txt：每行一个被放行的字面值
（测试虚构值/占位符），# 开头为注释。新增放行必须随代码评审说明理由。
结构性跳过（不算字面量口令）：值以 % $ { \\ 开头、含 $( 的表达式、
空值、true/false/null、__XXX__ 占位符、CHANGE_ME 类占位符。
"""
import fnmatch
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


def load_mandatory_patterns(tree):
    f = pathlib.Path(tree) / 'tools' / 'sync-mandatory-identical.txt'
    pats = []
    if f.exists():
        for line in f.read_text(encoding='utf-8').splitlines():
            line = line.strip()
            if line and not line.startswith('#'):
                pats.append(line)
    return pats


def mandatory_files(tree, patterns=None):
    """返回 tree 内命中零差异强制集 glob 模式的相对路径集合。"""
    tree = pathlib.Path(tree)
    if patterns is None:
        patterns = load_mandatory_patterns(tree)
    out = set()
    if not patterns:
        return out
    for p in sorted(tree.rglob('*')):
        if not p.is_file():
            continue
        rel = p.relative_to(tree)
        if EXCLUDE_DIRS & set(rel.parts):
            continue
        posix = rel.as_posix()
        if any(fnmatch.fnmatch(posix, pat) for pat in patterns):
            out.add(posix)
    return out


def mandatory_problems(src, dst):
    """强制集校验：两仓命中文件必须都存在、逐字节一致、且不在历史差异清单。"""
    patterns = load_mandatory_patterns(src) or load_mandatory_patterns(dst)
    if not patterns:
        return []
    legacy = load_legacy(src)
    src_m = mandatory_files(src, patterns)
    dst_m = mandatory_files(dst, patterns)
    problems = []
    for rel in sorted(src_m | dst_m):
        if rel in legacy:
            problems.append(
                f'{rel}: 在零差异强制集内却同时登记于历史差异清单'
                f'（强制集文件必须逐字节一致，须先对齐并删清单条目）')
        sp = pathlib.Path(src) / rel
        dp = pathlib.Path(dst) / rel
        if rel not in src_m:
            problems.append(f'{rel}: 强制集文件仅公开仓有（源仓缺失）')
        elif rel not in dst_m:
            problems.append(f'{rel}: 强制集文件仅源仓有（公开仓缺失）')
        elif sha256(sp) != sha256(dp):
            problems.append(
                f'{rel}: 零差异强制集文件两仓不一致'
                f'（可执行/被执行文件必须逐字节一致，历史差异清单不豁免）')
    return problems


def hashes_path(tree):
    return pathlib.Path(tree) / 'tools' / 'sync-mandatory-hashes.txt'


def write_hashes(tree):
    tree = pathlib.Path(tree)
    lines = []
    for rel in sorted(mandatory_files(tree)):
        lines.append(f'{sha256(tree / rel)}  {rel}')
    hashes_path(tree).write_text('\n'.join(lines) + '\n', encoding='utf-8')
    return len(lines)


def verify_hashes(tree):
    tree = pathlib.Path(tree)
    f = hashes_path(tree)
    if not f.exists():
        return ['tools/sync-mandatory-hashes.txt 缺失（须由同步时 write-hashes 生成）']
    recorded = {}
    for line in f.read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        parts = line.split(None, 1)
        if len(parts) == 2:
            recorded[parts[1].strip()] = parts[0].strip()
    actual = mandatory_files(tree)
    problems = []
    for rel in sorted(set(recorded) | set(actual)):
        if rel not in recorded:
            problems.append(f'{rel}: 强制集文件未登记于哈希表（须重新 write-hashes）')
        elif rel not in actual:
            problems.append(f'{rel}: 哈希表登记的文件在树内缺失')
        elif recorded[rel] != sha256(tree / rel):
            problems.append(
                f'{rel}: 与哈希表登记不一致（公开仓强制集文件已偏离同步时的源仓状态）')
    return problems


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
    problems.extend(mandatory_problems(src, dst))
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

        # 零差异强制集：即使登记进历史差异清单，强制集文件不一致仍必须失败；
        # write-hashes / verify-hashes 往返一致，被篡改必须检出。
        # 用独立的同级双树（嵌套树会让 *.bat 之类模式跨树命中，失真）。
        with tempfile.TemporaryDirectory() as td2:
            mroot = pathlib.Path(td2) / 'src'
            mother = pathlib.Path(td2) / 'dst'
            for base in (mroot, mother):
                (base / 'tools').mkdir(parents=True)
                (base / 'tools' / 'sync-mandatory-identical.txt').write_text(
                    '*.bat\n', encoding='utf-8')
                (base / 'a.bat').write_text(
                    'set "PGPASSWORD=%DBPWD%"\r\n', encoding='utf-8')
            (mroot / 'tools' / 'sync-legacy-differences.txt').write_text(
                'a.bat\n', encoding='utf-8')
            (mother / 'a.bat').write_text('rem changed\r\n', encoding='utf-8')
            probs, _, _, _ = compare(mroot, mother)
            check('强制集文件不一致被检出（历史清单不豁免）',
                  any('强制集' in x and 'a.bat' in x for x in probs))
            (mother / 'a.bat').write_bytes((mroot / 'a.bat').read_bytes())
            (mroot / 'tools' / 'sync-legacy-differences.txt').write_text(
                '# empty\n', encoding='utf-8')
            probs, _, _, _ = compare(mroot, mother)
            check('强制集对齐后 compare 通过', probs == [])
            write_hashes(mroot)
            check('哈希表往返校验通过', verify_hashes(mroot) == [])
            (mroot / 'a.bat').write_text('rem tampered\r\n', encoding='utf-8')
            check('哈希表检出强制集文件被篡改', verify_hashes(mroot) != [])
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
    if cmd == 'write-hashes' and len(argv) == 3:
        n = write_hashes(argv[2])
        print(f'sync-guard write-hashes: 已登记 {n} 个强制集文件哈希')
        return 0
    if cmd == 'verify-hashes' and len(argv) == 3:
        probs = verify_hashes(argv[2])
        for x in probs:
            print('::error::' + x)
        if probs:
            print(f'sync-guard verify-hashes: {len(probs)} 处不一致，构建失败')
            return 1
        print('sync-guard verify-hashes: 通过（强制集文件与登记哈希全相符）')
        return 0
    if cmd == 'selftest':
        return selftest()
    print(__doc__)
    return 2


if __name__ == '__main__':
    sys.exit(main(sys.argv))
