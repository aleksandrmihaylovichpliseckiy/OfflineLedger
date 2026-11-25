#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
构建完整 i18n key 列表 = 代码中 tr("...")/I18n.t("...") 字面量
+ 通过变量传入的枚举 label（首页/支出/日报等）
+ Changelog 文本
然后输出 tools/i18n_keys.json，并检查翻译覆盖率。
"""
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS = os.path.join(ROOT, "tools")
KEYS_JSON = os.path.join(TOOLS, "i18n_keys.json")

EXTRA_KEYS = """
首页 明细 报表 设置
账户余额 收支趋势 最近流水 快捷操作
日报 周报 月报 年报
支出 收入
银行卡 微信 支付宝 现金 应用通知
银行短信 手动记账
跟随系统 简体中文 English 日本語 Русский
墨绿 钴蓝 暮紫 暖橙 樱花粉 黑白
跟随系统 日间 夜间 跟随主题 手动设置
自动托管（beta） 每次询问 关闭 应用通知
基金 基金余额 转账 退款 其他
紧凑 标准 宽松
按设定顺序 按余额降序 按流水降序
收支默认模式 月结模式
周一 周二 周三 周四 周五 周六 周日
今天 近7天 本月 全部
月趋势 年趋势 年际趋势
柱状图 折线图
未知账户 当前 查看 支付宝余额
""".split()


def main():
    # 先跑字面量提取脚本
    subprocess.run([sys.executable, os.path.join(TOOLS, "extract_i18n.py")],
                   cwd=ROOT, check=False, stdout=subprocess.DEVNULL)
    data = json.load(open(KEYS_JSON, encoding="utf-8"))
    keys = set(data["keys"])
    keys.update(EXTRA_KEYS)

    changelog = open(os.path.join(ROOT, "app", "src", "main", "java", "com", "ledger",
                                  "offline", "ui", "Changelog.kt"), encoding="utf-8").read()
    m = re.search(r'VERSION_TITLE\s*=\s*"([^"]+)"', changelog)
    if m:
        keys.add(m.group(1))
    for line in re.findall(r'^\s*"([^"]+)"\s*,?\s*$', changelog, re.M):
        keys.add(line)

    keys = sorted(keys)
    json.dump({"count": len(keys), "keys": keys}, open(KEYS_JSON, "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    print(f"完整 key 数量: {len(keys)}")

    for lang in ("en", "ja", "ru"):
        merged = {}
        for suffix in ("", "_round2", "_round3", "_round4", "_round5", "_round6"):
            path = os.path.join(TOOLS, f"translations_{lang}{suffix}.json")
            if os.path.isfile(path):
                merged.update(json.load(open(path, encoding="utf-8")))
        missing = [k for k in keys if not merged.get(k)]
        print(f"{lang}: 已翻译 {len(keys) - len(missing)}/{len(keys)}，缺失 {len(missing)}")
        for key in missing[:30]:
            print("   missing:", key)


if __name__ == "__main__":
    main()
