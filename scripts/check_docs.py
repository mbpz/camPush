#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""CamPush 文档集一致性检查器。

用途：在 docs/ 下 00–11 及 README 完成后，做机器可校验的契约一致性巡检。
无第三方依赖，Python 3.8+ 可运行：

    python3 scripts/check_docs.py            # 检查并输出报告
    python3 scripts/check_docs.py --strict   # 有 ERROR 时以退出码 1 结束

检查项：
  1. 文件存在性、行数下限（对应各文档验收要求）
  2. 必需章节：目的与范围 / 验收检查点 / 开放问题 / 变更记录 / 元信息表
  3. Mermaid 图数量
  4. 文档内相对链接可达（./NN-xxx.md）
  5. PRD 章节引用合法（§1–§19）
  6. 错误码必须落在 00-conventions.md §9.2 定义的号段内（仅检查“错误码/code”上下文）
  7. 禁止出现真实域名、公网 IP、密钥样式字符串（占位域名与文档专用网段除外）
  8. “一个播放地址”不变量相关 URL 模板与 00-conventions.md §4.2 一致
"""

import argparse
import ipaddress
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"

TARGETS = {
    "README.md": 80,
    "docs/00-conventions.md": 200,
    "docs/01-architecture.md": 500,
    "docs/02-client-modules.md": 500,
    "docs/03-cloud-service.md": 500,
    "docs/04-interface-contracts.md": 500,
    "docs/05-lan-service.md": 350,
    "docs/06-playback-page.md": 350,
    "docs/07-security-privacy.md": 350,
    "docs/08-m0-verification.md": 350,
    "docs/09-task-plan.md": 300,
    "docs/10-observability.md": 300,
    "docs/11-risk-adr.md": 300,
    "docs/12-p1-implementation.md": 150,
    "docs/PRD-v1.0.md": 300,
}

REQUIRED_SECTIONS = ["验收检查点", "开放问题", "变更记录"]

# 00-conventions.md §9.2 定义的业务错误码（唯一合法集合）
KNOWN_CODES = {
    1001, 1002, 1003, 1004, 1005, 1006, 1007,
    2001, 2002, 2003, 2004, 2005, 2006, 2007, 2008, 2009,
    3001, 3002, 3003, 3004, 3005, 3006, 3007, 3008,
    4001, 4002, 4003, 4004, 4005, 4006,
    5001, 5002, 5003, 5004, 5005, 5006, 5007,
    6001, 6002, 6003, 6004, 6005, 6006, 6007,
    7001, 7002, 7003, 7004, 7005, 7006,
}

ALLOWED_HOSTS = {
    "play.example.com",
    "api.example.com",
    "ingest.example.com",
    "turn.example.com",
    "example.com",
    "localhost",
    "127.0.0.1",
    # 仓库自身的 GitHub 发布/CI 链接（非产品运行时域名）
    "github.com",
}

DOC_NETWORKS = [
    ipaddress.ip_network("192.0.2.0/24"),
    ipaddress.ip_network("198.51.100.0/24"),
    ipaddress.ip_network("203.0.113.0/24"),
    ipaddress.ip_network("127.0.0.0/8"),
    ipaddress.ip_network("10.0.2.0/24"),   # Android 模拟器 NAT 文档地址
]

# 00 §4.2 冻结的 URL 模板
FROZEN_TEMPLATES = [
    "{playHost}/s/{roomId}?token={pageToken}",
    "http://{lanIp}:{port}/s/{pageToken}",
    "{apiHost}/api/v1/",
    "{whipHost}/whip/{roomId}",
    "{whepHost}/whep/{roomId}",
    "{playHost}/live/{roomId}.flv?mt={mediaToken}",
    "{playHost}/hls/{roomId}/index.m3u8?mt={mediaToken}",
]

CODE_CTX_RE = re.compile(r"(错误码|error code|error_code|`code`|错误码为|错误码：)")
CODE_RE = re.compile(r"(?<![\d.])([1-7]\d{3})(?![\d.])")
LINK_RE = re.compile(r"\]\((\./[^)#\s]+\.md)(?:#[^)]*)?\)")
PRD_REF_RE = re.compile(r"PRD\s*§\s*(\d{1,2})")
HOST_RE = re.compile(r"https?://([A-Za-z0-9.\-]+)")
JWT_RE = re.compile(r"eyJ[A-Za-z0-9_\-]{30,}")
SECRET_RE = re.compile(r"(AKIA[0-9A-Z]{16}|sk-[A-Za-z0-9]{20,})")
TOKEN_ECHO_RE = re.compile(r"token=(?!\{)(eyJ[A-Za-z0-9._\-]{30,})")


def is_allowed_ip(host: str) -> bool:
    try:
        ip = ipaddress.ip_address(host)
    except ValueError:
        return False
    return any(ip in net for net in DOC_NETWORKS)


def check(strict: bool) -> int:
    errors: list[str] = []
    warnings: list[str] = []

    if not DOCS.is_dir():
        print(f"FATAL: 缺少目录 {DOCS}")
        return 2

    for rel, min_lines in TARGETS.items():
        path = ROOT / rel
        if not path.exists():
            errors.append(f"[缺失] {rel}")
            continue
        text = path.read_text(encoding="utf-8")
        lines = text.count("\n") + 1
        if lines < min_lines:
            errors.append(f"[行数不足] {rel}: {lines} < {min_lines}")

        fences = sum(1 for ln in text.splitlines() if ln.strip().startswith("```"))
        if fences % 2:
            errors.append(f"[代码块未闭合] {rel}: {fences} 个围栏（奇数）")

        if rel.startswith("docs/") and not rel.startswith(("docs/PRD", "docs/00")):
            for sec in REQUIRED_SECTIONS:
                if sec not in text:
                    errors.append(f"[缺章节] {rel}: 未找到「{sec}」")
            if "```mermaid" not in text:
                warnings.append(f"[无图] {rel}: 未检测到 Mermaid 图")
            if "文档状态" not in text:
                warnings.append(f"[元信息] {rel}: 未检测到文档状态元信息")

        # 链接可达
        for m in LINK_RE.finditer(text):
            target = (path.parent / m.group(1)).resolve()
            if not target.exists():
                errors.append(f"[坏链] {rel}: {m.group(1)} 不存在")

        # PRD 章节号
        for m in PRD_REF_RE.finditer(text):
            num = int(m.group(1))
            if not 1 <= num <= 19:
                errors.append(f"[PRD 引用越界] {rel}: §{num}")

        all_lines = text.splitlines()
        redact_window = {"脱敏前", "禁止落盘", "反例", "错误示例", "BAD"}

        def in_redaction_example(idx: int) -> bool:
            lo = max(0, idx - 4)
            return any(mark in ln for ln in all_lines[lo:idx + 1] for mark in redact_window)

        for lineno, line in enumerate(all_lines, 1):
            # 错误码（仅错误上下文行；剔除 JSON 字符串内的示例数字与年份）
            if CODE_CTX_RE.search(line):
                line_wo_json = re.sub(r'"[^"]*"', '""', line)
                line_wo_json = re.sub(r'RFC\s*\d+', 'RFC', line_wo_json, flags=re.I)
                for m in CODE_RE.finditer(line_wo_json):
                    code = int(m.group(1))
                    if code in KNOWN_CODES:
                        continue
                    if 2000 <= code <= 2099:   # 版本号/日期年份
                        continue
                    errors.append(f"[未定义错误码] {rel}:{lineno}: {code}")

            # 真实域名 / 公网 IP
            for m in HOST_RE.finditer(line):
                host = m.group(1).lower()
                if host in ALLOWED_HOSTS or host.startswith("{"):
                    continue
                if is_allowed_ip(host):
                    continue
                errors.append(f"[真实域名?] {rel}:{lineno}: {host}")

            # 密钥样式（截断展示或“脱敏前反例”上下文放行）
            if in_redaction_example(lineno - 1):
                continue
            for m in JWT_RE.finditer(line):
                if m.group(0)[-3:] in ("...",):
                    continue
                # 若该 token 在原文中紧跟省略号，视为示例
                tail = line[m.end():m.end() + 3]
                if tail.startswith(("…", "...")):
                    continue
                errors.append(f"[疑似密钥] {rel}:{lineno}: {m.group(0)[:12]}…")

            for m in SECRET_RE.finditer(line):
                errors.append(f"[疑似密钥] {rel}:{lineno}: {m.group(0)[:12]}…")

            for m in TOKEN_ECHO_RE.finditer(line):
                tail = line[m.end():m.end() + 3]
                if tail.startswith(("…", "...")):
                    continue
                errors.append(f"[疑似 token 明文] {rel}:{lineno}: {m.group(0)[:24]}…")

    # 冻结模板必须出现在 00
    conv = DOCS / "00-conventions.md"
    if conv.exists():
        conv_text = conv.read_text(encoding="utf-8")
        for tpl in FROZEN_TEMPLATES:
            if tpl not in conv_text:
                errors.append(f"[模板缺失] 00-conventions.md 未包含 {tpl}")

    # 编号唯一性
    seen: dict[str, str] = {}
    for p in sorted(DOCS.glob("[0-9][0-9]-*.md")):
        num = p.name[:2]
        if num in seen:
            errors.append(f"[编号重复] {num}: {seen[num]} 与 {p.name}")
        seen[num] = p.name

    print("=== CamPush 文档一致性检查 ===")
    print(f"文档根目录: {ROOT}")
    print(f"待检文件: {len(TARGETS)}")
    for w in warnings:
        print(f"WARN  {w}")
    for e in errors:
        print(f"ERROR {e}")
    print(f"\n结果: {len(errors)} error(s), {len(warnings)} warning(s)")
    if errors:
        print("结论: 未通过（请修复 ERROR 后重跑）")
        return 1 if strict else 0
    print("结论: 通过")
    return 0


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--strict", action="store_true", help="存在 ERROR 时返回非零退出码")
    sys.exit(check(ap.parse_args().strict))
