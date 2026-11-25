#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
扫描工程里所有 tr("...") / I18n.t("...") 的中文 key，输出去重列表，
并粗略报告“含中文但没走 tr/I18n.t”的字符串字面量，供 i18n 翻译与漏翻审计。
"""
import os
import re
import json
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src", "main", "java")

KEY_RE = re.compile(r'(?:I18n\.t|tr)\(\s*"((?:[^"\\]|\\.)*)"')
CJK = re.compile(r'[\u4e00-\u9fff]')
LITERAL_RE = re.compile(r'"((?:[^"\\]|\\.)*)"')


def unescape(s: str) -> str:
    return s.replace('\\"', '"').replace("\\n", "\n").replace("\\$", "$")


keys = {}
unwrapped = []

for dirpath, _, filenames in os.walk(SRC):
    for name in filenames:
        if not name.endswith(".kt"):
            continue
        path = os.path.join(dirpath, name)
        try:
            text = open(path, encoding="utf-8").read()
        except UnicodeDecodeError:
            continue
        rel = os.path.relpath(path, ROOT).replace("\\", "/")
        for m in KEY_RE.finditer(text):
            key = unescape(m.group(1))
            if CJK.search(key):
                keys[key] = keys.get(key, 0) + 1
        # 审计：含中文但没有被 tr(/I18n.t( 包住的字面量
        for m in LITERAL_RE.finditer(text):
            val = unescape(m.group(1))
            if not CJK.search(val):
                continue
            start = max(0, m.start() - 12)
            prefix = text[start:m.start()]
            if "tr(" in prefix or "I18n.t(" in prefix or "t(" in prefix:
                continue
            line = text.count("\n", 0, m.start()) + 1
            unwrapped.append(f"{rel}:{line}: {val[:60]}")

out = {
    "count": len(keys),
    "keys": sorted(keys.keys()),
}
dest = os.path.join(ROOT, "tools", "i18n_keys.json")
os.makedirs(os.path.dirname(dest), exist_ok=True)
with open(dest, "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, indent=1)
print(f"提取到 {len(keys)} 个待翻译 key -> {dest}")
if unwrapped:
    print(f"\n疑似未走 tr/I18n.t 的中文字面量（{len(unwrapped)} 处，需人工确认）：")
    for item in unwrapped[:120]:
        print("  " + item)
    if len(unwrapped) > 120:
        print(f"  ... 其余 {len(unwrapped) - 120} 处省略")
sys.exit(0)
