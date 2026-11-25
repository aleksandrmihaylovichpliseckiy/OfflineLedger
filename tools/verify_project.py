#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
离线账本工程的静态一致性校验（无 JDK / Android SDK 环境下的替代检查）。

检查项：
 1. Kotlin 文件 UTF-8、package 与目录一致、括号配平、无 TODO 占位。
 2. 工程内 import（com.ledger.offline.*）都能解析到真实存在的包/声明。
 3. 单例对象（ReportCenter / ReportScheduler / ...）被调用的成员确实声明在对应文件里。
 4. R.string / R.drawable / R.color / R.mipmap / R.style 引用都有对应资源。
 5. AndroidManifest 中类名、权限、资源引用有效；不得出现 INTERNET 权限。
 6. XML 资源格式正确；字符串/颜色/样式资源不得重名。
 7. 禁止网络代码；ui/report/ingest 不得直接 import data 内部实现。

用法：python tools/verify_project.py
退出码：0 全部通过；1 存在错误。
"""

import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN_JAVA = os.path.join(ROOT, "app", "src", "main", "java")
TEST_JAVA = os.path.join(ROOT, "app", "src", "test", "java")
RES_DIR = os.path.join(ROOT, "app", "src", "main", "res")
MANIFEST = os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml")
PACKAGE = "com.ledger.offline"

errors = []
warnings = []


def err(msg):
    errors.append(msg)


def warn(msg):
    warnings.append(msg)


def rel(path):
    return os.path.relpath(path, ROOT).replace("\\", "/")


# ---------------------------------------------------------------- Kotlin 解析

def strip_kotlin(src: str) -> str:
    """去掉注释与字符串，保留换行，用于括号配平与声明扫描。"""
    out = []
    i, n = 0, len(src)
    mode = None  # line / block / string / char / raw
    while i < n:
        c = src[i]
        if mode is None:
            if src.startswith("//", i):
                mode, i = "line", i + 2
                continue
            if src.startswith("/*", i):
                mode, i = "block", i + 2
                continue
            if c == '"':
                if src.startswith('"""', i):
                    mode, i = "raw", i + 3
                else:
                    mode, i = "string", i + 1
                out.append(" ")
                continue
            if c == "'":
                mode, i = "char", i + 1
                out.append(" ")
                continue
            out.append(c)
            i += 1
        elif mode == "line":
            if c == "\n":
                mode = None
                out.append("\n")
            i += 1
        elif mode == "block":
            if src.startswith("*/", i):
                mode, i = None, i + 2
            else:
                if c == "\n":
                    out.append("\n")
                i += 1
        elif mode == "string":
            if c == "\\":
                i += 2
            elif c == '"':
                mode, i = None, i + 1
                out.append(" ")
            else:
                if c == "\n":
                    out.append("\n")
                i += 1
        elif mode == "char":
            if c == "\\":
                i += 2
            elif c == "'":
                mode, i = None, i + 1
                out.append(" ")
            else:
                i += 1
        elif mode == "raw":
            if src.startswith('"""', i):
                mode, i = None, i + 3
            else:
                if c == "\n":
                    out.append("\n")
                i += 1
    return "".join(out)


DECL_RE = re.compile(
    r"\b(?:class|interface|object|fun|val|var|typealias|constructor)\s+([A-Za-z_]\w*)"
)
PKG_RE = re.compile(r"^\s*package\s+([\w.]+)\s*$", re.M)
IMPORT_RE = re.compile(r"^\s*import\s+([\w.]+)\s*(?:as\s+\w+)?\s*$", re.M)

kt_files = []
for src_root in (MAIN_JAVA, TEST_JAVA):
    if not os.path.isdir(src_root):
        continue
    for dirpath, _, filenames in os.walk(src_root):
        for name in filenames:
            if name.endswith(".kt"):
                kt_files.append(os.path.join(dirpath, name))

package_symbols = defaultdict(set)   # package -> declared symbol names
package_files = defaultdict(list)    # package -> files
file_packages = {}                   # file -> package
stripped_cache = {}

for path in kt_files:
    try:
        raw = open(path, "rb").read()
        src = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        err(f"{rel(path)}: 不是合法 UTF-8（{exc}）")
        continue

    if src.startswith("\ufeff"):
        src = src[1:]

    m = PKG_RE.search(src)
    if not m:
        err(f"{rel(path)}: 缺少 package 声明")
        continue
    pkg = m.group(1)
    file_packages[path] = pkg
    package_files[pkg].append(path)

    # package 与目录一致性
    expected_dir = os.path.dirname(path)
    rel_dir = os.path.relpath(expected_dir, MAIN_JAVA if path.startswith(MAIN_JAVA) else TEST_JAVA)
    expected_pkg = rel_dir.replace(os.sep, ".")
    if pkg != expected_pkg:
        err(f"{rel(path)}: package={pkg} 与目录不符（应为 {expected_pkg}）")

    stripped = strip_kotlin(src)
    stripped_cache[path] = stripped

    # 括号配平
    for open_ch, close_ch, label in (("{", "}", "花括号"), ("(", ")", "圆括号"), ("[", "]", "方括号")):
        if stripped.count(open_ch) != stripped.count(close_ch):
            err(
                f"{rel(path)}: {label}不配平 "
                f"({open_ch}={stripped.count(open_ch)}, {close_ch}={stripped.count(close_ch)})"
            )

    if "TODO(" in src or "TODO:" in src or "NotImplementedError" in src or "FIXME" in src:
        err(f"{rel(path)}: 存在 TODO/FIXME/NotImplementedError 占位")

    for name in DECL_RE.findall(stripped):
        package_symbols[pkg].add(name)

    # 顶层扩展函数（例如 `internal fun TransactionEntity.toDomain()`）也能被 import
    for name in re.findall(r"\bfun\s+(?:<[^>]*>\s*)?[\w.]+\.([A-Za-z_]\w*)\s*\(", stripped):
        package_symbols[pkg].add(name)

# 同一包内重复顶层声明（粗查：两个文件里出现同名 class/object 且都是顶层）
seen_top = {}
for path, pkg in file_packages.items():
    stripped = stripped_cache[path]
    # 只截取第一个类的声明之前的顶层内容不可靠，这里用“行首无缩进的 class/object”近似
    for m in re.finditer(r"^(?:data\s+|sealed\s+|abstract\s+|enum\s+|value\s+)?(?:class|object|interface)\s+([A-Za-z_]\w*)", stripped, re.M):
        key = (pkg, m.group(1))
        if key in seen_top:
            err(f"重复声明 {pkg}.{m.group(1)}：{rel(path)} 与 {rel(seen_top[key])}")
        else:
            seen_top[key] = path

# ---------------------------------------------------------------- import 解析

known_packages = set(package_symbols.keys())
generated = {f"{PACKAGE}.R"}
for path in kt_files:
    src = stripped_cache.get(path)
    if src is None:
        continue
    pkg = file_packages.get(path, "")
    for m in IMPORT_RE.finditer(src):
        fq = m.group(1)
        if not fq.startswith(PACKAGE + "."):
            continue
        if fq in generated:
            continue
        parts = fq.split(".")
        resolved = False
        for i in range(len(parts), 0, -1):
            pkg_candidate = ".".join(parts[:i])
            if pkg_candidate in known_packages:
                rest = parts[i:]
                if not rest:
                    resolved = True
                elif all(sym in package_symbols[pkg_candidate] for sym in rest):
                    resolved = True
                break
        if not resolved:
            err(f"{rel(path)}: 无法解析 import {fq}")

    # 分层依赖：非 data 模块不得直接引用 data 内部实现（仅 di 容器可用 RepoFactory）
    for m in IMPORT_RE.finditer(src):
        fq = m.group(1)
        if fq.startswith(PACKAGE + ".data.") and pkg not in (PACKAGE, PACKAGE + ".di",) and not pkg.startswith(PACKAGE + ".data"):
            warn(f"{rel(path)}: 跨层引用数据层实现 {fq}")

# ---------------------------------------------------------------- 对象成员调用检查

OBJECT_MEMBERS = {}
for path, pkg in file_packages.items():
    stripped = stripped_cache[path]
    for m in re.finditer(r"^\s*object\s+([A-Za-z_]\w*)", stripped, re.M):
        obj = m.group(1)
        members = set(
            re.findall(r"\b(?:fun|val|var)\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?([A-Za-z_]\w*)", stripped)
        )
        # 常量：const val XXX
        members.update(re.findall(r"\bconst\s+val\s+([A-Za-z_]\w*)", stripped))
        OBJECT_MEMBERS.setdefault(obj, set()).update(members)

TRACKED_OBJECTS = {
    "ReportCenter", "ReportScheduler", "ReportNotifier", "LedgerIngest",
    "RepoFactory", "DataSeeder", "Money", "DateTimes", "SmsParser",
    "NotificationParser", "ReportFormatter",
}
CALL_RE = re.compile(r"\b([A-Z][A-Za-z0-9_]*)\.([A-Za-z_]\w*)\b")
for path in kt_files:
    src = stripped_cache.get(path)
    if src is None:
        continue
    for m in CALL_RE.finditer(src):
        obj, member = m.group(1), m.group(2)
        if obj not in TRACKED_OBJECTS:
            continue
        if obj not in OBJECT_MEMBERS:
            warn(f"{rel(path)}: 引用了未找到定义的对象 {obj}")
            continue
        if member not in OBJECT_MEMBERS[obj]:
            err(f"{rel(path)}: {obj}.{member} 在 {obj} 中没有声明")

# ---------------------------------------------------------------- 资源收集

res_strings = set()
res_colors = set()
res_styles = set()
res_arrays = set()
res_bools = set()
res_integers = set()
res_dimens = set()
res_drawables = set()
res_mipmaps = set()
res_xml = set()
dup_check = defaultdict(list)

if os.path.isdir(RES_DIR):
    for dirpath, _, filenames in os.walk(RES_DIR):
        for name in filenames:
            full = os.path.join(dirpath, name)
            folder = os.path.basename(dirpath)
            base = os.path.splitext(name)[0]
            if folder.startswith("drawable"):
                res_drawables.add(base)
                continue
            if folder.startswith("mipmap"):
                res_mipmaps.add(base)
                continue
            if folder.startswith("xml"):
                res_xml.add(base)
                continue
            if name.endswith(".xml"):
                try:
                    tree = ET.parse(full)
                except ET.ParseError as exc:
                    err(f"{rel(full)}: XML 解析失败 {exc}")
                    continue
                root = tree.getroot()
                if root.tag == "resources":
                    for node in root:
                        attr = node.get("name")
                        if not attr:
                            continue
                        tag = node.tag
                        if tag == "string":
                            res_strings.add(attr)
                            dup_check[(folder, "string", attr)].append(rel(full))
                        elif tag == "color":
                            res_colors.add(attr)
                            dup_check[(folder, "color", attr)].append(rel(full))
                        elif tag == "style":
                            res_styles.add(attr)
                            dup_check[(folder, "style", attr)].append(rel(full))
                        elif tag == "string-array":
                            res_arrays.add(attr)
                            dup_check[(folder, "string-array", attr)].append(rel(full))
                        elif tag == "bool":
                            res_bools.add(attr)
                        elif tag == "integer":
                            res_integers.add(attr)
                        elif tag == "dimen":
                            res_dimens.add(attr)

for (folder, kind, name), files in dup_check.items():
    if len(files) > 1:
        err(f"资源重名 {folder}/@{kind}/{name}：{', '.join(sorted(set(files)))}")

RES_SETS = {
    "string": res_strings,
    "color": res_colors,
    "style": res_styles,
    "array": res_arrays,
    "string-array": res_arrays,
    "bool": res_bools,
    "integer": res_integers,
    "dimen": res_dimens,
    "drawable": res_drawables,
    "mipmap": res_mipmaps,
    "xml": res_xml,
}

# Kotlin 里 R.xxx.name 引用
R_REF_RE = re.compile(r"\bR\.(string|color|style|drawable|mipmap|bool|integer|dimen|array|string-array)\.([A-Za-z_]\w*)")
for path in kt_files:
    src = stripped_cache.get(path)
    if not src:
        continue
    for m in R_REF_RE.finditer(src):
        kind, name = m.group(1), m.group(2)
        if name not in RES_SETS.get(kind, set()):
            err(f"{rel(path)}: R.{kind}.{name} 不存在")

# XML 里 @type/name 引用
REF_RE = re.compile(r"@(string|color|style|drawable|mipmap|bool|integer|dimen|array|string-array)/([A-Za-z_][\w.]*)")
xml_files = []
if os.path.isdir(RES_DIR):
    for dirpath, _, filenames in os.walk(RES_DIR):
        for name in filenames:
            if name.endswith(".xml"):
                xml_files.append(os.path.join(dirpath, name))
xml_files.append(MANIFEST)

for path in xml_files:
    if not os.path.isfile(path):
        continue
    try:
        text = open(path, "rb").read().decode("utf-8")
    except UnicodeDecodeError:
        err(f"{rel(path)}: 不是合法 UTF-8")
        continue
    for m in REF_RE.finditer(text):
        kind, name = m.group(1), m.group(2)
        if name not in RES_SETS.get(kind, set()):
            err(f"{rel(path)}: 引用不存在的资源 @{kind}/{name}")

# ---------------------------------------------------------------- Manifest 检查

if os.path.isfile(MANIFEST):
    try:
        root = ET.parse(MANIFEST).getroot()
    except ET.ParseError as exc:
        err(f"AndroidManifest.xml 解析失败：{exc}")
        root = None
    if root is not None:
        android_ns = "{http://schemas.android.com/apk/res/android}"
        permissions = [p.get(android_ns + "name", "") for p in root.findall("uses-permission")]
        if any(p == "android.permission.INTERNET" for p in permissions):
            err("Manifest 声明了 INTERNET 权限，违反完全离线要求")
        pkg_name = root.get("package") or PACKAGE
        app = root.find("application")
        if app is not None:
            for child in app:
                tag = child.tag
                name = child.get(android_ns + "name")
                if not name or not name.startswith("."):
                    continue
                if tag == "activity-alias":
                    # alias 不是类，只检查它指向的 targetActivity 是否存在
                    target = child.get(android_ns + "targetActivity")
                    if target and target.startswith("."):
                        cls = pkg_name + target
                        hits = [p for p, pkg in file_packages.items()
                                if f"{pkg}.{target[1:]}" == cls]
                        if not hits:
                            err(f"AndroidManifest.xml: activity-alias targetActivity {cls} 不存在")
                    continue
                cls = pkg_name + name
                hits = [p for p, pkg in file_packages.items()
                        if f"{pkg}.{name[1:]}" == cls]
                if not hits:
                    err(f"AndroidManifest.xml: 类 {cls} 不存在")
else:
    err("缺少 AndroidManifest.xml")

# ---------------------------------------------------------------- 网络代码检查

NET_RE = re.compile(r"\b(HttpURLConnection|OkHttpClient|Retrofit|java\.net\.|OkHttp|WebView)\b")
for path in kt_files:
    src = stripped_cache.get(path)
    if src and NET_RE.search(src):
        err(f"{rel(path)}: 出现网络相关代码（违反完全离线要求）")

# ---------------------------------------------------------------- 接口实现 / DAO 调用检查

# 1) 仓库接口方法 -> 实现类必须 override
INTERFACE_METHODS = {}
for path, pkg in file_packages.items():
    if not pkg.endswith(".repo"):
        continue
    stripped = stripped_cache[path]
    for m in re.finditer(r"interface\s+([A-Za-z_]\w*)", stripped):
        iface = m.group(1)
        methods = set(re.findall(r"\bfun\s+([A-Za-z_]\w*)\s*\(", stripped))
        methods.update(re.findall(r"\bval\s+([A-Za-z_]\w*)", stripped))
        INTERFACE_METHODS[f"{pkg}.{iface}"] = (methods, path)

for path in kt_files:
    stripped = stripped_cache.get(path)
    if not stripped:
        continue
    for m in re.finditer(r"class\s+([A-Za-z_]\w*)[^{]*?:\s*([^{]+?)\{", stripped, re.S):
        impl = m.group(1)
        supers = [s.strip() for s in m.group(2).split(",")]
        for sup in supers:
            sup_name = sup.split("(")[0].strip().split(".")[-1]
            hit = None
            for key, (methods, iface_path) in INTERFACE_METHODS.items():
                if key.split(".")[-1] == sup_name:
                    hit = (key, methods, iface_path)
                    break
            if not hit:
                continue
            key, methods, iface_path = hit
            overrides = set(re.findall(r"override\s+(?:suspend\s+)?fun\s+([A-Za-z_]\w*)", stripped))
            overrides.update(re.findall(r"override\s+val\s+([A-Za-z_]\w*)", stripped))
            missing = methods - overrides
            if missing:
                err(
                    f"{rel(path)}: 实现 {key} 时缺少成员 {sorted(missing)}"
                    f"（接口定义见 {rel(iface_path)}）"
                )

# 2) 仓库里对 DAO 的方法调用必须声明在对应 DAO 接口中
DAO_METHODS = {}
for path, pkg in file_packages.items():
    stripped = stripped_cache[path]
    for m in re.finditer(r"interface\s+([A-Za-z_]\w*Dao)\b", stripped):
        DAO_METHODS[m.group(1)] = set(re.findall(r"\bfun\s+([A-Za-z_]\w*)\s*\(", stripped))

for path in kt_files:
    stripped = stripped_cache.get(path)
    if not stripped:
        continue
    for m in re.finditer(r"private\s+val\s+(\w+)\s*:\s*([A-Za-z_]\w*Dao)\b", stripped):
        var, dao_type = m.group(1), m.group(2)
        if dao_type not in DAO_METHODS:
            continue
        for call in re.finditer(rf"\b{re.escape(var)}\.([A-Za-z_]\w*)\s*\(", stripped):
            if call.group(1) not in DAO_METHODS[dao_type]:
                err(f"{rel(path)}: 调用了 {dao_type} 中不存在的方法 {call.group(1)}")

# 3) Room @Database entities 中的类必须存在
for path, pkg in file_packages.items():
    stripped = stripped_cache.get(path)
    if not stripped or "@Database" not in stripped:
        continue
    for m in re.finditer(r"entities\s*=\s*\[([^\]]*)\]", stripped, re.S):
        for cls in re.findall(r"([A-Za-z_]\w*)::class", m.group(1)):
            if cls not in package_symbols[pkg]:
                err(f"{rel(path)}: @Database 引用的实体 {cls} 不存在于 {pkg}")

# ---------------------------------------------------------------- 输出

print("=" * 70)
print(f"离线账本静态校验：{len(kt_files)} 个 Kotlin 文件")
print(f"错误 {len(errors)} 个，警告 {len(warnings)} 个")
print("=" * 70)
for item in errors:
    print("[ERROR] " + item)
for item in warnings:
    print("[WARN ] " + item)
if not errors:
    print("全部检查通过。")
sys.exit(1 if errors else 0)
