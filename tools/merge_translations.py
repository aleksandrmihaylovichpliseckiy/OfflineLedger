#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 tools/translations_{lang}[_round2].json 合并生成 Kotlin 翻译表。"""
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS = os.path.join(ROOT, "tools")
OUT = os.path.join(ROOT, "app", "src", "main", "java", "com", "ledger", "offline", "i18n")

LANGS = {
    "en": ("TranslationsEn", "English"),
    "ja": ("TranslationsJa", "Japanese"),
    "ru": ("TranslationsRu", "Russian"),
}

# 人工修正个别不够自然的译法
OVERRIDES = {
    "ja": {
        "柱状图": "棒グラフ",
    },
}


def load(name):
    path = os.path.join(TOOLS, name)
    if not os.path.isfile(path):
        return {}
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def kt_escape(text: str) -> str:
    return (
        text.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("\n", "\\n")
        .replace("$", "\\$")
    )


def main():
    keys = json.load(open(os.path.join(TOOLS, "i18n_keys.json"), encoding="utf-8"))["keys"]
    for lang, (obj, label) in LANGS.items():
        mapping = {}
        mapping.update(load(f"translations_{lang}.json"))
        mapping.update(load(f"translations_{lang}_round2.json"))
        mapping.update(load(f"translations_{lang}_round3.json"))
        mapping.update(load(f"translations_{lang}_round4.json"))
        mapping.update(load(f"translations_{lang}_round5.json"))
        mapping.update(load(f"translations_{lang}_round6.json"))
        mapping.update(OVERRIDES.get(lang, {}))
        missing = [k for k in keys if not mapping.get(k)]
        entries = []
        for key in sorted(keys):
            value = mapping.get(key) or key
            entries.append(f'        "{kt_escape(key)}" to "{kt_escape(value)}",')
        content = (
            "package com.ledger.offline.i18n\n\n"
            f"/**\n * {label}本地化表：key 为中文原文，value 为{label}。\n"
            " * 由 tools/merge_translations.py 生成；带参数的文案沿用 %1$s / %1$d 占位符。\n */\n"
            f"internal object {obj} {{\n"
            "    val MAP: Map<String, String> = mapOf(\n"
            + "\n".join(entries)
            + "\n    )\n}\n"
        )
        dest = os.path.join(OUT, f"{obj}.kt")
        with open(dest, "w", encoding="utf-8", newline="\n") as f:
            f.write(content)
        print(f"{lang}: {len(keys) - len(missing)}/{len(keys)} translated, missing={len(missing)} -> {dest}")
        if missing:
            for key in missing[:20]:
                print("   missing:", key)


if __name__ == "__main__":
    main()
