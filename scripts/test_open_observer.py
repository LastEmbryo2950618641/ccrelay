from __future__ import annotations

import importlib.util
import io
import json
import subprocess
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "codex-skill" / "ccrelay" / "scripts" / "open-observer.py"
SPEC = importlib.util.spec_from_file_location("open_observer", SCRIPT)
open_observer = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(open_observer)


class OpenObserverTest(unittest.TestCase):

    def test_observer_url_preserves_center_base_path(self):
        self.assertEqual(
            "http://center.example/cc/ccrelay-observer.html",
            open_observer.observer_url("http://center.example/cc/"),
        )

    @patch.object(open_observer.webbrowser, "open_new_tab", return_value=True)
    @patch.object(open_observer.subprocess, "run")
    def test_main_resolves_center_and_opens_browser(self, run, open_new_tab):
        run.return_value = subprocess.CompletedProcess(
            args=[],
            returncode=0,
            stdout=json.dumps({"centerUrl": "http://127.0.0.1:18191", "mode": "LOCAL"}),
            stderr="",
        )
        output = io.StringIO()

        with redirect_stdout(output):
            exit_code = open_observer.main([])

        self.assertEqual(0, exit_code)
        open_new_tab.assert_called_once_with("http://127.0.0.1:18191/ccrelay-observer.html")
        self.assertTrue(json.loads(output.getvalue())["browserOpened"])
        self.assertEqual("center", run.call_args.args[0][-2])
        self.assertEqual("resolve", run.call_args.args[0][-1])

    @patch.object(open_observer.webbrowser, "open_new_tab")
    @patch.object(open_observer.subprocess, "run")
    def test_no_browser_only_prints_url(self, run, open_new_tab):
        run.return_value = subprocess.CompletedProcess(
            args=[],
            returncode=0,
            stdout=json.dumps({"centerUrl": "http://center.example:18191", "mode": "REMOTE"}),
            stderr="",
        )
        output = io.StringIO()

        with redirect_stdout(output):
            exit_code = open_observer.main(["--no-browser"])

        self.assertEqual(0, exit_code)
        open_new_tab.assert_not_called()
        payload = json.loads(output.getvalue())
        self.assertFalse(payload["browserOpened"])
        self.assertEqual("http://center.example:18191/ccrelay-observer.html", payload["observerUrl"])


if __name__ == "__main__":
    unittest.main()
