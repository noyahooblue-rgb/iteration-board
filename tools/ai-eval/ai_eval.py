#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
迭代小组平台 · 本地部署环境 AI 测试脚本（单文件，仅 Python 3.12 标准库）

用途：在本地部署环境用真实模型（OpenAI 兼容接口，如DeepSeek 等模型）对平台六项 AI 功能
      做提示词回归测试。读取与系统完全相同的提示词文件（backend/config/prompts/*.md），
      按 cases.json 的 30 组用例逐组调用模型，对输出做与系统相同的 JSON 结构校验，
      并按每组预期规则判定通过/失败/跳过，最后生成 Markdown 报告。

运行：python ai_eval.py [--config config.json] [--cases cases.json]
配置：同目录 config.json（不存在时按提示从 config.example.json 复制后填写）。
安全：报告与控制台输出中的密钥一律打码，任何地方不回显完整密钥。
"""

import argparse
import json
import re
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
FEATURES = ["paste-import", "quick-update", "summarize", "split-stories", "risk-summary", "qa"]
PLACEHOLDER_KEY = "sk-REPLACE-ME-PLEASE"
MAX_OUTPUT_IN_REPORT = 2000
MAX_INPUT_IN_REPORT = 500


def mask(text, secret):
    """把文本中出现的完整密钥替换为打码形式。"""
    if text is None:
        return ""
    s = str(text)
    if secret:
        s = s.replace(secret, secret[:3] + "***" if len(secret) > 3 else "***")
    return s


def truncate(s, limit, note="…（已截断）"):
    s = "" if s is None else str(s)
    return s if len(s) <= limit else s[:limit] + note


def is_int(v):
    return isinstance(v, int) and not isinstance(v, bool)


def is_num(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def get_path(obj, path):
    """按点号路径取值；段为数字时按列表下标。缺失返回 (False, None)。"""
    cur = obj
    if path in ("", "."):
        return True, cur
    for part in path.split("."):
        if isinstance(cur, dict) and part in cur:
            cur = cur[part]
        elif isinstance(cur, list) and part.isdigit() and int(part) < len(cur):
            cur = cur[int(part)]
        else:
            return False, None
    return True, cur


def dump(obj):
    try:
        return json.dumps(obj, ensure_ascii=False)
    except Exception:
        return str(obj)


# ---------------------------------------------------------------- 结构校验
# 依据：backend AiFeatureService 对模型 JSON 的实际消费字段与类型、
#       backend/config/prompts/*.md 中声明的输出格式（详见交付 worklog）。

def _check_str(errors, obj, field, required=False, nullable=False, non_empty=False):
    if field not in obj:
        if required:
            errors.append(f"缺少必填字段 {field}")
        return
    v = obj[field]
    if v is None:
        if not nullable:
            errors.append(f"字段 {field} 不应为 null")
        return
    if not isinstance(v, str):
        errors.append(f"字段 {field} 应为字符串，实际为 {type(v).__name__}")
    elif non_empty and not v.strip():
        errors.append(f"字段 {field} 不应为空字符串")


def _check_num(errors, obj, field, required=False, nullable=False, lo=None, hi=None):
    if field not in obj:
        if required:
            errors.append(f"缺少必填字段 {field}")
        return
    v = obj[field]
    if v is None:
        if not nullable:
            errors.append(f"字段 {field} 不应为 null")
        return
    if not is_num(v):
        errors.append(f"字段 {field} 应为数字，实际为 {type(v).__name__}")
        return
    if lo is not None and v < lo:
        errors.append(f"字段 {field}={v} 小于下限 {lo}")
    if hi is not None and v > hi:
        errors.append(f"字段 {field}={v} 大于上限 {hi}")


def _check_bool(errors, obj, field, required=False):
    if field not in obj:
        if required:
            errors.append(f"缺少必填字段 {field}")
        return
    if not isinstance(obj[field], bool):
        errors.append(f"字段 {field} 应为布尔值")


def _check_str_list(errors, obj, field, required=False):
    if field not in obj:
        if required:
            errors.append(f"缺少必填字段 {field}")
        return None
    v = obj[field]
    if not isinstance(v, list):
        errors.append(f"字段 {field} 应为数组")
        return None
    for i, x in enumerate(v):
        if not isinstance(x, str):
            errors.append(f"字段 {field}[{i}] 应为字符串")
    return v


def validate_paste_import(o):
    e = []
    rows = o.get("rows")
    if not isinstance(rows, list):
        return ["缺少必填字段 rows 或 rows 不是数组"]
    if len(rows) > 100:
        e.append("rows 超过 100 条上限")
    for i, r in enumerate(rows):
        if not isinstance(r, dict):
            e.append(f"rows[{i}] 不是对象")
            continue
        _check_str(e, r, "title", required=True, non_empty=True)
        _check_str(e, r, "externalId", nullable=True)
        _check_str(e, r, "description", nullable=True)
        _check_str(e, r, "ownerName", nullable=True)
        _check_str(e, r, "dueDate", nullable=True)
        _check_str(e, r, "parentExternalId", nullable=True)
        _check_str(e, r, "sourceSnippet")
        if "type" in r and r["type"] != "TASK":
            e.append(f"rows[{i}].type 应为 TASK，实际 {r['type']!r}")
        if "priority" in r and r["priority"] not in ("P0", "P1", "P2", "P3"):
            e.append(f"rows[{i}].priority 应为 P0|P1|P2|P3，实际 {r['priority']!r}")
        _check_num(e, r, "estimate", nullable=True)
        _check_num(e, r, "confidence", lo=0, hi=1)
    return e


def validate_quick_update(o):
    e = []
    actions = o.get("actions")
    if not isinstance(actions, list):
        return ["缺少必填字段 actions 或 actions 不是数组"]
    _check_str_list(e, o, "unmatched", required=True)
    for i, a in enumerate(actions):
        if not isinstance(a, dict):
            e.append(f"actions[{i}] 不是对象")
            continue
        kind = a.get("kind")
        if kind not in ("UPDATE_ITEM", "CREATE_ITEM"):
            e.append(f"actions[{i}].kind 应为 UPDATE_ITEM|CREATE_ITEM，实际 {kind!r}")
            continue
        if kind == "UPDATE_ITEM":
            if not is_int(a.get("itemId")):
                e.append(f"actions[{i}].itemId 应为整数")
            _check_str(e, a, "itemCode")
            _check_str(e, a, "text")
            _check_num(e, a, "percent", nullable=True, lo=0, hi=100)
            _check_bool(e, a, "submitReview")
            _check_bool(e, a, "blocked")
            _check_bool(e, a, "needsBlockConfirm")
            _check_str(e, a, "blockReason", nullable=True)
            cands = a.get("candidateItemIds")
            if cands is not None and (not isinstance(cands, list) or any(not is_int(c) for c in cands)):
                e.append(f"actions[{i}].candidateItemIds 应为整数数组")
        else:
            _check_str(e, a, "title", required=True, non_empty=True)
            _check_str(e, a, "type")
            _check_str(e, a, "ownerName", nullable=True)
            _check_str(e, a, "dueDate", nullable=True)
    return e


def validate_split_stories(o):
    e = []
    stories = o.get("stories")
    if not isinstance(stories, list):
        return ["缺少必填字段 stories 或 stories 不是数组"]
    _check_str_list(e, o, "clarifyingQuestions", required=True)
    _check_str_list(e, o, "acceptancePoints", required=True)
    for i, s in enumerate(stories):
        if not isinstance(s, dict):
            e.append(f"stories[{i}] 不是对象")
            continue
        for bad in ("owner", "assignee", "ownerName", "ownerId", "负责人"):
            if bad in s:
                e.append(f"stories[{i}] 出现禁止的负责人字段 {bad}")
        if s.get("storyCategory") not in ("DEV", "ANALYSIS", "TEST", "OTHER"):
            e.append(f"stories[{i}].storyCategory 应为 DEV|ANALYSIS|TEST|OTHER，实际 {s.get('storyCategory')!r}")
        _check_str(e, s, "title", required=True, non_empty=True)
        _check_str(e, s, "description")
        _check_num(e, s, "estimate", nullable=True, lo=0)
    return e


def validate_summarize(o):
    e = []
    _check_str(e, o, "narrative", required=True, non_empty=True)
    return e


def validate_risk_summary(o):
    e = []
    top = o.get("top")
    if not isinstance(top, list):
        return ["缺少必填字段 top 或 top 不是数组"]
    if len(top) > 3:
        e.append("top 超过 3 件上限")
    for i, t in enumerate(top):
        if not isinstance(t, dict):
            e.append(f"top[{i}] 不是对象")
            continue
        if not is_int(t.get("itemId")):
            e.append(f"top[{i}].itemId 应为整数")
        _check_str(e, t, "itemCode", required=True, non_empty=True)
        _check_str(e, t, "reason", required=True)
        _check_str(e, t, "action", required=True)
    return e


def validate_qa(o, stage):
    e = []
    if stage == "query":
        for f in ("code", "ownerName", "status", "keyword"):
            _check_str(e, o, f)
        _check_bool(e, o, "blocked")
        return e
    _check_bool(e, o, "found", required=True)
    _check_str(e, o, "answer", required=True)
    _check_str_list(e, o, "refs", required=True)
    return e


def validate_structure(feature, parsed, case):
    if not isinstance(parsed, dict):
        return ["模型输出不是 JSON 对象"]
    if feature == "paste-import":
        return validate_paste_import(parsed)
    if feature == "quick-update":
        return validate_quick_update(parsed)
    if feature == "summarize":
        return validate_summarize(parsed)
    if feature == "split-stories":
        return validate_split_stories(parsed)
    if feature == "risk-summary":
        return validate_risk_summary(parsed)
    if feature == "qa":
        return validate_qa(parsed, case.get("stage", "answer"))
    return [f"未知功能 {feature}"]


# ---------------------------------------------------------------- 预期规则判定

CODE_RE = re.compile(r"[A-Za-z]{2,5}-\d+")
NUM_RE = re.compile(r"\d+(?:\.\d+)?%?")


def run_check(check, parsed, raw, case):
    """返回 (是否通过, 说明)。check 为 cases.json 中的一条预期规则。"""
    kind = check.get("kind", "")

    if kind == "any_of":
        subs = [run_check(c, parsed, raw, case) for c in check.get("checks", [])]
        ok = any(s[0] for s in subs)
        return ok, "any_of(" + "; ".join(("✓" if s[0] else "✗") + s[1] for s in subs) + ")"

    if kind == "text_contains":
        t = check["text"]
        return (t in raw), (f"输出包含「{t}」" if t in raw else f"输出未包含「{t}」")

    if kind == "text_not_contains":
        t = check["text"]
        scope = check.get("path")
        if scope:
            ok_path, v = get_path(parsed, scope)
            hay = ""
            if ok_path:
                if isinstance(v, list):
                    fields = check.get("fields")
                    parts = []
                    for item in v:
                        if isinstance(item, dict) and fields:
                            parts += [str(item.get(f, "")) for f in fields]
                        else:
                            parts.append(dump(item))
                    hay = "\n".join(parts)
                else:
                    hay = str(v)
            return (t not in hay), (f"{scope} 中不含「{t}」" if t not in hay else f"{scope} 中出现了禁止内容「{t}」")
        return (t not in raw), (f"输出不含「{t}」" if t not in raw else f"输出出现了禁止内容「{t}」")

    if kind == "text_contains_any":
        scope = check.get("path")
        hay = raw
        if scope:
            ok_path, v = get_path(parsed, scope)
            hay = str(v) if ok_path else ""
        hit = [t for t in check["texts"] if t in hay]
        return bool(hit), (f"命中 {hit}" if hit else f"未命中任一 {check['texts']}")

    if kind == "json_equals":
        ok_path, v = get_path(parsed, check["path"])
        want = check.get("value")
        return (ok_path and v == want), f"{check['path']}={v!r}（期望 {want!r}）"

    if kind == "array_length":
        ok_path, v = get_path(parsed, check["path"])
        if not ok_path or not isinstance(v, list):
            return False, f"{check['path']} 不是数组"
        n = len(v)
        if "exact" in check:
            return n == check["exact"], f"{check['path']} 长度={n}（期望 {check['exact']}）"
        lo, hi = check.get("min", 0), check.get("max", 10 ** 9)
        return lo <= n <= hi, f"{check['path']} 长度={n}（期望 {lo}~{hi}）"

    if kind == "array_contains":
        ok_path, v = get_path(parsed, check["path"])
        if not ok_path or not isinstance(v, list):
            return False, f"{check['path']} 不是数组"
        hit = check.get("value") in v
        return hit, f"{check['path']} {'包含' if hit else '不包含'} {check.get('value')!r}"

    if kind == "values_subset_of":
        ok_path, v = get_path(parsed, check["path"])
        if not ok_path or not isinstance(v, list):
            return False, f"{check['path']} 不是数组"
        allowed = set(check.get("allowed", []))
        extra = [x for x in v if x not in allowed]
        return not extra, (f"{check['path']} 全部在允许范围内" if not extra else f"{check['path']} 越界值 {extra}")

    if kind == "item_field_equals":
        ok_path, v = get_path(parsed, check["path"])
        if not ok_path or not isinstance(v, list):
            return False, f"{check['path']} 不是数组"
        mf, mv = check["match_field"], check.get("match_value")
        for item in v:
            if isinstance(item, dict) and item.get(mf) == mv:
                got = item.get(check["field"])
                want = check.get("value")
                return got == want, f"{check['path']}[{mf}={mv}].{check['field']}={got!r}（期望 {want!r}）"
        return False, f"{check['path']} 中未找到 {mf}={mv} 的条目"

    if kind == "item_present":
        ok_path, v = get_path(parsed, check["path"])
        if not ok_path or not isinstance(v, list):
            return False, f"{check['path']} 不是数组"
        hit = any(isinstance(x, dict) and x.get(check["field"]) == check.get("value") for x in v)
        return hit, f"{check['path']} {'存在' if hit else '不存在'} {check['field']}={check.get('value')!r} 的条目"

    if kind == "all_items_field_in":
        ok_path, v = get_path(parsed, check["path"])
        if not ok_path or not isinstance(v, list):
            return False, f"{check['path']} 不是数组"
        allowed = check.get("values", [])
        bad = [x.get(check["field"]) for x in v if isinstance(x, dict) and x.get(check["field"]) not in allowed]
        return not bad, (f"{check['path']} 全部条目 {check['field']} 在 {allowed} 内" if not bad else f"越界条目 {check['field']}={bad}")

    if kind == "all_items_field_null":
        ok_path, v = get_path(parsed, check["path"])
        if not ok_path or not isinstance(v, list):
            return False, f"{check['path']} 不是数组"
        bad = [x.get(check["field"]) for x in v if isinstance(x, dict) and x.get(check["field"]) is not None]
        return not bad, (f"{check['path']} 全部条目 {check['field']} 为 null" if not bad else f"{check['field']} 非 null 值 {bad}")

    if kind == "all_items_number_max":
        ok_path, v = get_path(parsed, check["path"])
        if not ok_path or not isinstance(v, list):
            return False, f"{check['path']} 不是数组"
        bad = [x.get(check["field"]) for x in v if isinstance(x, dict) and is_num(x.get(check["field"])) and x[check["field"]] > check["max"]]
        return not bad, (f"{check['path']} 全部条目 {check['field']} ≤ {check['max']}" if not bad else f"超限值 {bad}")

    if kind == "no_item_field_true":
        ok_path, v = get_path(parsed, check["path"])
        if not ok_path or not isinstance(v, list):
            return False, f"{check['path']} 不是数组"
        bad = [x for x in v if isinstance(x, dict) and x.get(check["field"]) is True]
        return not bad, (f"{check['path']} 无条目 {check['field']}=true" if not bad else f"存在 {check['field']}=true 的条目（疑似执行了注入动作）")

    if kind == "requires_confirmation":
        ok_path, actions = get_path(parsed, "actions")
        if not ok_path or not isinstance(actions, list):
            return False, "actions 不是数组"
        _, unmatched = get_path(parsed, "unmatched")
        if not actions:
            return True, "actions 为空（未擅自选定）"
        if unmatched:
            return True, "有 unmatched 说明，未硬套"
        ok = all(isinstance(a, dict) and a.get("candidateItemIds") for a in actions)
        return ok, ("全部动作都带候选，未擅自选定" if ok else "存在无候选的确定动作（疑似擅自选定）")

    if kind == "narrative_consistent":
        ok_path, narrative = get_path(parsed, "narrative")
        if not ok_path or not isinstance(narrative, str):
            return False, "narrative 缺失"
        facts = case.get("user", "")
        fact_codes = {m.upper() for m in CODE_RE.findall(facts)}
        fact_nums = set(NUM_RE.findall(facts))
        bad_codes = [c for c in {m.upper() for m in CODE_RE.findall(narrative)} if c not in fact_codes]
        bad_nums = [n for n in set(NUM_RE.findall(narrative)) if n not in fact_nums]
        if bad_codes or bad_nums:
            return False, f"叙述出现事实表外内容：编号 {bad_codes}，数字 {bad_nums}"
        return True, "叙述编号与数字均为事实表子集"

    return False, f"未知判定规则 kind={kind}"


# ---------------------------------------------------------------- 模型调用

def call_model(cfg, system_prompt, user_text, image_b64, use_vision):
    """OpenAI 兼容 chat/completions（urllib 实现）。返回模型文本；异常向上抛，由主循环记失败。"""
    base = str(cfg["base_url"]).rstrip("/")
    url = base if base.endswith("/chat/completions") else base + "/chat/completions"
    model = cfg.get("vision_model") if use_vision else cfg.get("text_model")
    if use_vision:
        content = [
            {"type": "text", "text": user_text},
            {"type": "image_url", "image_url": {"url": "data:image/png;base64," + (image_b64 or "")}},
        ]
    else:
        content = user_text
    body = {
        "model": model,
        "temperature": 0.2,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": content},
        ],
    }
    req = urllib.request.Request(
        url,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + str(cfg["api_key"])},
        method="POST",
    )
    timeout = cfg.get("timeout_seconds") or 60
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    content_text = data["choices"][0]["message"]["content"]
    if content_text is None or not str(content_text).strip():
        raise ValueError("模型返回空内容")
    return str(content_text)


def parse_model_json(content):
    """与系统 AiGateway.parseJson 相同口径：截取首个 { 到末个 } 再解析。"""
    s = content.strip()
    i, j = s.find("{"), s.rfind("}")
    if i < 0 or j <= i:
        raise ValueError("模型返回不是 JSON 对象")
    return json.loads(s[i:j + 1])


# ---------------------------------------------------------------- 主流程

def load_config(path):
    if not path.exists():
        print("【配置缺失】未找到配置文件：" + str(path))
        print("请按以下步骤操作：")
        print("  1. 把同目录的 config.example.json 复制一份，改名为 config.json；")
        print("  2. 用记事本打开 config.json，填入本地部署环境接口地址、真实密钥与模型名；")
        print("  3. 保存后重新运行本脚本。注意：config.json 含真实密钥，切勿提交到任何仓库或发给任何人。")
        sys.exit(2)
    try:
        cfg = json.loads(path.read_text(encoding="utf-8"))
    except Exception as e:
        print(f"【配置错误】config.json 不是合法 JSON：{e}")
        sys.exit(2)
    missing = [f for f in ("base_url", "api_key", "text_model") if not str(cfg.get(f) or "").strip()]
    if missing:
        print("【配置错误】config.json 缺少必填项：" + "、".join(missing) + "，请对照 config.example.json 补齐。")
        sys.exit(2)
    if str(cfg.get("api_key")).strip() == PLACEHOLDER_KEY:
        print("【配置错误】api_key 还是样例占位符，请换成本地部署环境真实密钥后再运行（切勿把真实密钥提交到任何仓库）。")
        sys.exit(2)
    return cfg


def main():
    ap = argparse.ArgumentParser(description="迭代小组平台本地部署环境 AI 测试脚本")
    ap.add_argument("--config", default=str(SCRIPT_DIR / "config.json"), help="配置文件路径（默认同目录 config.json）")
    ap.add_argument("--cases", default=str(SCRIPT_DIR / "cases.json"), help="用例文件路径（默认同目录 cases.json）")
    ap.add_argument("--report-dir", default=str(SCRIPT_DIR), help="报告输出目录（默认脚本目录）")
    args = ap.parse_args()

    cfg = load_config(Path(args.config))
    secret = str(cfg.get("api_key") or "")

    cases_path = Path(args.cases)
    if not cases_path.exists():
        print(mask(f"【用例缺失】未找到用例文件：{cases_path}", secret))
        sys.exit(2)
    cases_doc = json.loads(cases_path.read_text(encoding="utf-8"))
    cases = cases_doc.get("cases", [])

    if cfg.get("prompts_dir"):
        prompts_dir = Path(str(cfg["prompts_dir"]))
        if not prompts_dir.is_absolute():
            prompts_dir = (SCRIPT_DIR / prompts_dir).resolve()
    else:
        prompts_dir = (SCRIPT_DIR.parents[1] / "backend" / "config" / "prompts")
    prompts = {}
    for feat in FEATURES:
        f = prompts_dir / (feat + ".md")
        prompts[feat] = f.read_text(encoding="utf-8") if f.exists() else None

    vision_model = str(cfg.get("vision_model") or "").strip()
    results = []
    print(f"共 {len(cases)} 组用例；提示词目录：{prompts_dir}；文本模型：{cfg.get('text_model')}；识图模型：{vision_model or '（未配置，识图用例将跳过）'}")

    for case in cases:
        cid = case.get("id", "?")
        feat = case.get("feature", "")
        rec = {"case": case, "status": "失败", "reason": "", "validation": [], "checks": [],
               "output": "", "elapsed": 0.0}
        start = time.time()
        try:
            if case.get("needs_vision") and not vision_model:
                rec["status"] = "跳过"
                rec["reason"] = "跳过：未配置识图模型"
                print(f"[{cid}] 跳过：未配置识图模型")
            elif prompts.get(feat) is None:
                rec["reason"] = f"提示词文件缺失：{prompts_dir / (feat + '.md')}"
                print(mask(f"[{cid}] 失败：{rec['reason']}", secret))
            else:
                raw = call_model(cfg, prompts[feat], case.get("user", ""),
                                 case.get("image_png_base64"), bool(case.get("needs_vision")))
                rec["output"] = raw
                try:
                    parsed = parse_model_json(raw)
                except Exception as e:
                    rec["reason"] = f"输出不是合法 JSON：{e}"
                    print(mask(f"[{cid}] 失败：{rec['reason']}", secret))
                else:
                    rec["validation"] = validate_structure(feat, parsed, case)
                    all_ok = True
                    for chk in case.get("expect", []):
                        ok, note = run_check(chk, parsed, raw, case)
                        rec["checks"].append((ok, chk.get("describe", chk.get("kind", "")), note))
                        all_ok = all_ok and ok
                    if rec["validation"]:
                        rec["reason"] = "结构校验不通过"
                    elif not all_ok:
                        rec["reason"] = "预期规则判定不通过"
                    else:
                        rec["status"] = "通过"
                    print(mask(f"[{cid}] {rec['status']}" + (f"：{rec['reason']}" if rec["reason"] else ""), secret))
        except urllib.error.HTTPError as e:
            detail = ""
            try:
                detail = e.read().decode("utf-8", "replace")[:300]
            except Exception:
                pass
            rec["reason"] = f"HTTP 错误 {e.code} {e.reason} {detail}".strip()
            print(mask(f"[{cid}] 失败：{rec['reason']}", secret))
        except Exception as e:
            rec["reason"] = f"调用异常（{type(e).__name__}）：{e}"
            print(mask(f"[{cid}] 失败：{rec['reason']}", secret))
        finally:
            rec["elapsed"] = time.time() - start
        results.append(rec)

    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    report_path = Path(args.report_dir) / f"ai-eval-report-{stamp}.md"
    lines = []
    lines.append("# AI 功能本地部署环境测试报告")
    lines.append("")
    lines.append(f"- 时间：{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    lines.append(f"- 接口：{mask(cfg.get('base_url'), secret)}")
    lines.append(f"- 文本模型：{cfg.get('text_model')}；识图模型：{vision_model or '（未配置）'}")
    lines.append("- 密钥：已打码（报告中不出现完整密钥）")
    lines.append(f"- 提示词目录：{prompts_dir}")
    lines.append("")
    lines.append("## 汇总")
    lines.append("")
    lines.append("| 功能 | 通过 | 失败 | 跳过 | 合计 |")
    lines.append("|---|---|---|---|---|")
    total = {"通过": 0, "失败": 0, "跳过": 0}
    for feat in FEATURES:
        sub = [r for r in results if r["case"].get("feature") == feat]
        c = {k: sum(1 for r in sub if r["status"] == k) for k in total}
        for k in total:
            total[k] += c[k]
        lines.append(f"| {feat} | {c['通过']} | {c['失败']} | {c['跳过']} | {len(sub)} |")
    lines.append(f"| **合计** | **{total['通过']}** | **{total['失败']}** | **{total['跳过']}** | **{len(results)}** |")
    lines.append("")
    lines.append("## 逐组明细")
    for r in results:
        case = r["case"]
        lines.append("")
        lines.append(f"### {case.get('id')} · {case.get('feature')} · {case.get('name', '')} —— {r['status']}")
        lines.append("")
        lines.append(f"- 说明：{case.get('description', '')}")
        lines.append(f"- 是否需要识图：{'是' if case.get('needs_vision') else '否'}")
        lines.append(f"- 耗时：{r['elapsed']:.1f}s")
        if r["reason"]:
            lines.append(f"- 失败/跳过原因：{r['reason']}")
        lines.append("- 输入摘要：")
        lines.append("")
        lines.append("```")
        lines.append(truncate(case.get("user", ""), MAX_INPUT_IN_REPORT))
        lines.append("```")
        lines.append("- 模型输出：")
        lines.append("")
        lines.append("```")
        lines.append(truncate(r["output"], MAX_OUTPUT_IN_REPORT) if r["output"] else "（无输出）")
        lines.append("```")
        if r["validation"]:
            lines.append("- 结构校验：不通过")
            for v in r["validation"]:
                lines.append(f"  - {v}")
        elif r["output"]:
            lines.append("- 结构校验：通过")
        if r["checks"]:
            lines.append("- 预期规则判定：")
            for ok, desc, note in r["checks"]:
                lines.append(f"  - {'✓' if ok else '✗'} {desc}：{note}")
    report_text = mask("\n".join(lines) + "\n", secret)
    report_path.write_text(report_text, encoding="utf-8")

    print(f"\n汇总：通过 {total['通过']}，失败 {total['失败']}，跳过 {total['跳过']}，共 {len(results)}")
    print(mask(f"报告已生成：{report_path}", secret))
    sys.exit(1 if total["失败"] else 0)


if __name__ == "__main__":
    main()
