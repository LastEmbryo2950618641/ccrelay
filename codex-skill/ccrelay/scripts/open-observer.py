#!/usr/bin/env python3
"""Start or resolve the CC center and open the read-only observer UI."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import webbrowser
from pathlib import Path
from typing import Optional


OBSERVER_PATH = "ccrelay-observer.html"


class ObserverLaunchError(RuntimeError):
    pass


def parse_args(argv: Optional[list[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="启动 CC center 并打开只读 Agent 观测界面。")
    parser.add_argument("--no-browser", action="store_true", help="只启动服务并输出界面地址，不打开浏览器。")
    return parser.parse_args(argv)


def configure_stdio() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")


def resolve_center() -> dict:
    cli_path = Path(__file__).resolve().with_name("ccrelay-cli.py")
    environment = os.environ.copy()
    environment["PYTHONUTF8"] = "1"
    completed = subprocess.run(
        [sys.executable, str(cli_path), "center", "resolve"],
        check=False,
        text=True,
        encoding="utf-8",
        errors="replace",
        capture_output=True,
        env=environment,
    )
    if completed.returncode != 0:
        detail = completed.stderr.strip() or completed.stdout.strip() or "unknown error"
        raise ObserverLaunchError(f"CC center 启动或解析失败: {detail}")
    try:
        payload = json.loads(completed.stdout)
    except json.JSONDecodeError as exc:
        raise ObserverLaunchError("CC center 返回了无法识别的结果。") from exc
    center_url = str(payload.get("centerUrl") or "").strip()
    if not center_url:
        raise ObserverLaunchError("CC center 结果中缺少 centerUrl。")
    return payload


def observer_url(center_url: str) -> str:
    return f"{center_url.rstrip('/')}/{OBSERVER_PATH}"


def main(argv: Optional[list[str]] = None) -> int:
    configure_stdio()
    args = parse_args(argv)
    try:
        center = resolve_center()
        url = observer_url(str(center["centerUrl"]))
        browser_opened = False if args.no_browser else bool(webbrowser.open_new_tab(url))
        print(json.dumps({
            "status": "OBSERVER_READY",
            "centerMode": center.get("mode"),
            "centerUrl": center.get("centerUrl"),
            "observerUrl": url,
            "browserOpened": browser_opened,
            "message": "浏览器未自动打开，请手工访问 observerUrl。" if not browser_opened else "观测界面已打开。",
        }, ensure_ascii=False, indent=2))
        return 0
    except ObserverLaunchError as exc:
        print(f"open-observer: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
