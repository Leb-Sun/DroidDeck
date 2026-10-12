"""droiddeckctl and droiddeck-scenario against a fake adb: the JSON contract and exit codes."""
import base64
import contextlib
import importlib.machinery
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import textwrap
import unittest

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))


def load(name):
    loader = importlib.machinery.SourceFileLoader(name.replace("-", "_"), str(TOOLS / name))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


ctl = load("droiddeckctl")
scenario = load("droiddeck-scenario")

# A stand-in for adb: answers the provider's content calls from a JSON config (a list answers
# successive calls), records every invocation, and fakes the few shell commands droiddeckctl uses.
FAKE_ADB = textwrap.dedent('''\
    #!/usr/bin/env python3
    import base64, json, os, sys
    config_path = os.environ["FAKE_ADB_CONFIG"]
    config = json.load(open(config_path))
    args = sys.argv[1:]
    if args[:1] == ["-s"]:
        args = args[2:]
    entry = {"args": args}
    if args[:1] == ["get-state"]:
        print("device"); sys.exit(0)
    if args[:3] == ["shell", "content", "call"]:
        method = args[args.index("--method") + 1]
        request = None
        if "--extra" in args:
            request = json.loads(base64.b64decode(args[args.index("--extra") + 1].split(":", 2)[2]))
        entry.update(method=method, request=request)
        answers = config["provider"].get(method, {"ok": False, "error": {"code": "UNKNOWN_COMMAND", "message": method}})
        if isinstance(answers, list):
            count = config.setdefault("counts", {}).get(method, 0)
            config["counts"][method] = count + 1
            answers = answers[min(count, len(answers) - 1)]
            json.dump(config, open(config_path, "w"))
        print("Result: Bundle[{json=" + json.dumps(answers) + "}]")
    elif args[:3] == ["shell", "dumpsys", "display"]:
        print(config.get("dumpsys_display", ""))
    elif args[:3] == ["shell", "dumpsys", "power"]:
        print("  mWakefulness=Awake")
    elif args[:2] == ["exec-out", "screencap"]:
        sys.stdout.buffer.write(b"\\x89PNG fake")
    with open(config["log"], "a") as log:
        log.write(json.dumps(entry) + "\\n")
''')

DUMPSYS_DISPLAY = textwrap.dedent('''\
    Display Devices: size=2
      DisplayDeviceInfo{"Built-in Screen": uniqueId="local:111", 1080 x 1920}
      DisplayDeviceInfo{"Screen-2": uniqueId="local:222", 1080 x 1240}
    Logical Displays: size=2
      Display 0:
        mDisplayId=0
        mPrimaryDisplayDevice=Built-in Screen
        mBaseDisplayInfo=DisplayInfo{"Built-in Screen", displayId 0", real 1080 x 1920, x}
      Display 4:
        mDisplayId=4
        mPrimaryDisplayDevice=Screen-2
        mBaseDisplayInfo=DisplayInfo{"Screen-2", displayId 4", real 1080 x 1240, x}
''')


def state(phase="READY", focused=None, focusable=()):
    focus = None if focused is None else {"focusedApp": focused, "focusableApps": list(focusable),
                                          "baselayerAppIds": [], "seq": 1, "t": 0}
    return {"ok": True, "schema": 2, "session": {"id": "s1", "phase": phase, "running": phase == "READY",
                                                  "focus": focus, "failure": None}}


class FakeAdbTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.dir = Path(self.temp.name)
        self.adb = self.dir / "adb"
        self.adb.write_text(FAKE_ADB)
        self.adb.chmod(self.adb.stat().st_mode | stat.S_IEXEC)
        self.config = self.dir / "config.json"
        self.log = self.dir / "log.jsonl"
        self.configure({})
        os.environ["FAKE_ADB_CONFIG"] = str(self.config)
        self.addCleanup(os.environ.pop, "FAKE_ADB_CONFIG", None)

    def configure(self, provider, **extra):
        self.config.write_text(json.dumps(dict({"provider": provider, "log": str(self.log)}, **extra)))

    def run_ctl(self, *argv):
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
            code = ctl.main(["--adb", str(self.adb), "--serial", "fake-1", *argv])
        return code, json.loads(out.getvalue())

    def calls(self, method=None):
        if not self.log.exists():
            return []
        entries = [json.loads(line) for line in self.log.read_text().splitlines()]
        return [e for e in entries if method is None or e.get("method") == method]


class DroiddeckctlTest(FakeAdbTest):
    def test_oversized_requests_are_refused_before_adb(self):
        with self.assertRaises(ctl.ControlError) as caught:
            ctl.provider_call(str(self.adb), "fake-1", "guest", request={"stdin": "x" * ctl.REQUEST_CAP})
        self.assertEqual("INVALID_REQUEST", caught.exception.code)
        self.assertFalse(self.calls())

    def test_state_passes_through_with_the_serial(self):
        self.configure({"state": state()})
        code, out = self.run_ctl("state")
        self.assertEqual(0, code)
        self.assertEqual("READY", out["session"]["phase"])
        self.assertEqual("fake-1", out["deviceSerial"])

    def test_launch_sends_the_app_id_and_waits_for_focus(self):
        self.configure({
            "launch": {"ok": True, "command": "launch", "appId": "489830"},
            "state": [state(focused=769, focusable=[769]), state(focused=489830, focusable=[769])],
        })
        # Focus alone is not enough: the game must also be in the list Steam orders from.
        code, out = self.run_ctl("launch", "489830", "--wait", "--timeout", "1")
        self.assertEqual(5, code)
        self.assertEqual("TIMEOUT", out["error"]["code"])
        self.assertIn("focused app 489830", out["error"]["message"])
        self.assertEqual({"appId": "489830"}, self.calls("launch")[0]["request"])

        self.configure({
            "launch": {"ok": True, "command": "launch", "appId": "489830"},
            "state": [state(focused=769, focusable=[769]), state(focused=489830, focusable=[489830, 769])],
        })
        code, out = self.run_ctl("launch", "489830", "--wait", "--timeout", "5")
        self.assertEqual(0, code)
        self.assertEqual(489830, out["focus"]["focusedApp"])

    def test_start_reuse_keeps_a_running_session(self):
        running = state(focused=769)
        running["session"]["mode"] = "steam"
        self.configure({"state": running})
        code, out = self.run_ctl("start", "steam", "--reuse", "--wait")
        self.assertEqual(0, code)
        self.assertTrue(out["reused"])
        self.assertFalse([c for c in self.calls() if c["args"][:2] == ["shell", "am"]])

    def test_start_without_reuse_refuses_a_running_session(self):
        running = state()
        running["session"]["mode"] = "steam"
        self.configure({"state": running})
        code, out = self.run_ctl("start", "steam")
        self.assertEqual(4, code)
        self.assertEqual("SESSION_ACTIVE", out["error"]["code"])

    def test_wait_game_ignores_the_client(self):
        self.configure({"state": [state(focused=769), state(focused=752590, focusable=[752590])]})
        code, out = self.run_ctl("wait", "game", "--timeout", "5")
        self.assertEqual(0, code)
        self.assertEqual(752590, out["session"]["focus"]["focusedApp"])

    def test_a_failed_session_ends_a_wait(self):
        failed = state(phase="FAILED")
        failed["session"]["failure"] = {"code": "GUEST_EXIT", "message": "boom"}
        self.configure({"state": failed})
        code, out = self.run_ctl("wait", "focused", "1", "--timeout", "5")
        self.assertEqual(4, code)
        self.assertEqual("GUEST_EXIT", out["error"]["code"])

    def test_disabled_agent_commands_exit_7(self):
        self.configure({"guest": {"ok": False, "error": {"code": "AGENT_COMMANDS_DISABLED", "message": "off"}}})
        code, out = self.run_ctl("guest", "--", "true")
        self.assertEqual(7, code)
        self.assertEqual("AGENT_COMMANDS_DISABLED", out["error"]["code"])

    def test_run_checks_access_before_launching_an_activity(self):
        self.configure({"access": {"ok": True, "commands": False}})
        code, out = self.run_ctl("run", "/usr/bin/sh", "--", "-c", "true")
        self.assertEqual(7, code)
        self.assertEqual("AGENT_COMMANDS_DISABLED", out["error"]["code"])
        self.assertIn("Debugging tools", out["error"]["message"])
        self.assertEqual(["access"], [c.get("method") for c in self.calls()])

    def test_override_checks_names_before_pushing_any_file(self):
        self.configure({"access": {"ok": True, "commands": True, "inbox": "/tmp/inbox"}})
        source = self.dir / "binary"
        source.write_bytes(b"test")
        code, out = self.run_ctl("override", "../outside", str(source))
        self.assertEqual(2, code)
        self.assertEqual("INVALID_NAME", out["error"]["code"])
        self.assertEqual(["access"], [c.get("method") for c in self.calls()])

    def test_guest_request_and_check(self):
        self.configure({"guest": {"ok": True, "exitCode": 3, "stdout": {"text": ""}, "stderr": {"text": "nope"}}})
        code, out = self.run_ctl("guest", "--timeout", "7", "--env", "A=1", "--check", "--", "xprop", "-root")
        self.assertEqual(4, code)
        self.assertEqual("GUEST_EXIT", out["error"]["code"])
        self.assertEqual({"argv": ["xprop", "-root"], "timeout": 7.0, "env": {"A": "1"}},
                         self.calls("guest")[0]["request"])

    def test_screenshot_resolves_the_logical_display(self):
        self.configure({}, dumpsys_display=DUMPSYS_DISPLAY)
        target = self.dir / "shot.png"
        code, out = self.run_ctl("screenshot", str(target), "--display", "4")
        self.assertEqual(0, code)
        self.assertTrue(target.read_bytes().startswith(b"\x89PNG"))
        shot = [c for c in self.calls() if c["args"][:2] == ["exec-out", "screencap"]][0]
        self.assertEqual(["exec-out", "screencap", "-p", "-d", "222"], shot["args"])

    def test_displays(self):
        self.configure({}, dumpsys_display=DUMPSYS_DISPLAY)
        code, out = self.run_ctl("displays")
        self.assertEqual(0, code)
        self.assertEqual([0, 4], [d["display"] for d in out["displays"]])
        self.assertEqual("222", out["displays"][1]["physicalId"])
        self.assertEqual([1080, 1240], out["displays"][1]["size"])

    def test_unknown_display_is_an_error(self):
        self.configure({}, dumpsys_display=DUMPSYS_DISPLAY)
        code, out = self.run_ctl("screenshot", str(self.dir / "x.png"), "--display", "9")
        self.assertEqual(2, code)
        self.assertEqual("NO_SUCH_DISPLAY", out["error"]["code"])

    def test_input_requests(self):
        self.configure({"input": {"ok": True, "command": "input"}})
        self.run_ctl("input", "tap", "0.5", "0.25", "--space", "fraction")
        self.run_ctl("input", "key", "enter", "--mod", "CTRL")
        self.run_ctl("input", "pad", "a", "--hold", "50")
        requests = [c["request"] for c in self.calls("input")]
        self.assertEqual({"type": "tap", "x": 0.5, "y": 0.25, "space": "fraction", "holdMs": 60}, requests[0])
        self.assertEqual({"type": "key", "key": "enter", "action": "tap", "modifiers": ["CTRL"], "holdMs": 40}, requests[1])
        self.assertEqual({"type": "pad", "button": "a", "action": "tap", "holdMs": 50}, requests[2])

    def test_ui_click_by_tag_or_match(self):
        self.configure({"ui": {"ok": True, "command": "ui"}})
        self.run_ctl("ui", "click", "rail-steam")
        self.run_ctl("ui", "click", "--match", "Play", "--index", "1")
        requests = [c["request"] for c in self.calls("ui")]
        self.assertEqual({"op": "click", "index": 0, "tag": "rail-steam"}, requests[0])
        self.assertEqual({"op": "click", "index": 1, "match": "Play"}, requests[1])

    def test_cdp_targets_and_eval_requests(self):
        self.configure({"cdp": {"ok": True, "targets": []}})
        self.assertEqual(0, self.run_ctl("cdp", "targets")[0])
        self.assertEqual(0, self.run_ctl("cdp", "eval", "1+1", "--target", "Big Picture")[0])
        requests = [c["request"] for c in self.calls("cdp")]
        self.assertEqual({"op": "targets"}, requests[0])
        self.assertEqual({"op": "eval", "expression": "1+1", "awaitPromise": True, "timeout": 15.0,
                          "target": "Big Picture"}, requests[1])

    def test_a_bug_still_answers_json(self):
        self.configure({"state": {"ok": True, "session": "not an object"}})
        code, out = self.run_ctl("wait", "game", "--timeout", "1")
        self.assertEqual(2, code)
        self.assertEqual("INTERNAL_ERROR", out["error"]["code"])

    def test_wake(self):
        code, out = self.run_ctl("wake")
        self.assertEqual(0, code)
        self.assertEqual("Awake", out["wakefulness"])
        self.assertIn(["shell", "input", "keyevent", "KEYCODE_WAKEUP"], [c["args"] for c in self.calls()])

    def test_override_pushes_to_the_inbox_first(self):
        self.configure({
            "access": {"ok": True, "commands": True, "inbox": "/sdcard/Android/data/x/files/agent-inbox"},
            "override": {"ok": True, "installed": "/opt/droiddeck-agent/bin/gamescope"},
        })
        binary = self.dir / "gamescope"
        binary.write_bytes(b"\x7fELF")
        code, out = self.run_ctl("override", "gamescope", str(binary))
        self.assertEqual(0, code)
        push = [c for c in self.calls() if c["args"][:1] == ["push"]][0]
        self.assertEqual("/sdcard/Android/data/x/files/agent-inbox/gamescope", push["args"][2])
        self.assertEqual({"name": "gamescope"}, self.calls("override")[0]["request"])

    def test_package_names_the_provider_and_the_activity(self):
        self.configure({"state": state(phase="IDLE")})
        code, out = self.run_ctl("--package", "com.droiddeck.env", "start", "steam")
        self.assertEqual(4, code)  # the fake adb's am start does not answer "Status: ok"
        self.assertEqual("ACTIVITY_START_FAILED", out["error"]["code"])
        content = [c["args"] for c in self.calls() if c["args"][:3] == ["shell", "content", "call"]]
        self.assertTrue(content)
        self.assertTrue(all(a[a.index("--uri") + 1] == "content://com.droiddeck.env.agent" for a in content))
        start = [c["args"] for c in self.calls() if c["args"][:2] == ["shell", "am"]][0]
        # The renamed apk keeps the app's classes: the activity is named in full.
        self.assertIn("com.droiddeck.env/com.droiddeck.launcher.agent.AgentStartActivity", start)

    def test_package_from_the_environment_and_the_default(self):
        self.configure({"state": state()})
        os.environ["DROIDDECK_PACKAGE"] = "com.droiddeck.env"
        try:
            self.assertEqual(0, self.run_ctl("state")[0])
        finally:
            del os.environ["DROIDDECK_PACKAGE"]
        self.assertEqual(0, self.run_ctl("state")[0])
        uris = [c["args"][c["args"].index("--uri") + 1] for c in self.calls("state")]
        self.assertEqual(["content://com.droiddeck.env.agent", "content://com.droiddeck.launcher.agent"], uris)

    def test_a_bad_package_is_refused_before_adb(self):
        code, out = self.run_ctl("--package", "com.droiddeck.env;rm", "state")
        self.assertEqual(2, code)
        self.assertEqual("INVALID_PACKAGE", out["error"]["code"])
        self.assertFalse(self.calls("state"))

    def test_perf_writes_the_report_where_asked(self):
        report = {"schema": "droiddeck.perf/1", "times": {"readyS": 20.0}}
        self.configure({"perf": {"ok": True, "command": "perf", "sessionId": "s1", "summary": "READY 20.0 s\n",
                                 "report": report}})
        code, out = self.run_ctl("perf", "--out", str(self.dir / "baseline"))
        self.assertEqual(0, code)
        self.assertEqual("READY 20.0 s\n", (self.dir / "baseline" / "perf-report.txt").read_text())
        self.assertEqual(report, json.loads((self.dir / "baseline" / "perf-report.json").read_text()))
        self.configure({"perf": {"ok": False, "error": {"code": "NO_PERF_REPORT", "message": "none"}}})
        code, out = self.run_ctl("perf")
        self.assertEqual(6, code)
        self.assertEqual("NO_PERF_REPORT", out["error"]["code"])

    def test_override_refused_before_pushing_when_commands_are_off(self):
        self.configure({"access": {"ok": True, "commands": False, "inbox": "/x"}})
        binary = self.dir / "gamescope"
        binary.write_bytes(b"x")
        code, out = self.run_ctl("override", "gamescope", str(binary))
        self.assertEqual(7, code)
        self.assertFalse([c for c in self.calls() if c["args"][:1] == ["push"]])


class ScenarioTest(FakeAdbTest):
    def write(self, body):
        path = self.dir / "scenario.json"
        path.write_text(json.dumps(body))
        return path

    def run_scenario(self, path, *extra):
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
            code = scenario.main([str(path), "--adb", str(self.adb), "--serial", "fake-1",
                                  "--out", str(self.dir / "runs"), *extra])
        return code, json.loads(out.getvalue())

    def test_variables_asserts_and_repeats(self):
        self.configure({"state": state(focused=489830, focusable=[489830])})
        path = self.write({
            "name": "t", "vars": {"appId": 1}, "repeat": 2,
            "steps": [["state"], {"assert": "session.focus.focusedApp", "equals": "${appId}"}],
        })
        code, report = self.run_scenario(path, "--var", "appId=489830")
        self.assertEqual(0, code)
        self.assertEqual(2, report["passed"])
        output = Path(report["out"])
        self.assertEqual(0o700, output.stat().st_mode & 0o777)
        for artifact in output.rglob("*.json"):
            self.assertEqual(0o600, artifact.stat().st_mode & 0o777, str(artifact))
        for run in report["runs"]:
            self.assertEqual(0o700, Path(run["folder"]).stat().st_mode & 0o777)

    def test_a_failed_assert_fails_the_run_and_collects_evidence(self):
        self.configure({"state": state(focused=769, focusable=[769]), "focus": {"ok": True, "focusedApp": 769}},
                       dumpsys_display=DUMPSYS_DISPLAY)
        path = self.write({"name": "t", "repeat": 3,
                           "steps": [["state"], {"assert": "session.focus.focusedApp", "equals": 489830}]})
        code, report = self.run_scenario(path)
        self.assertEqual(1, code)
        self.assertEqual(1, len(report["runs"]))  # stops at the first failure without --keep-going
        self.assertIn("is 769", report["runs"][0]["error"])
        evidence = json.loads((Path(report["runs"][0]["folder"]) / "evidence.json").read_text())
        self.assertEqual(769, evidence["focus"]["focusedApp"])

    def test_the_package_reaches_every_step(self):
        self.configure({"state": state(focused=7, focusable=[7])})
        path = self.write({"name": "t", "steps": [["state"], {"assert": "session.focus.focusedApp", "equals": 7}]})
        code, report = self.run_scenario(path, "--package", "com.droiddeck.env")
        self.assertEqual(0, code)
        self.assertEqual(["content://com.droiddeck.env.agent"],
                         [c["args"][c["args"].index("--uri") + 1] for c in self.calls("state")])

    def test_the_baseline_scenario_is_droiddeckctl_commands(self):
        body = scenario.substitute(json.loads((TOOLS / "scenarios/perf-baseline.json").read_text()),
                                   {"appId": 7, "idle": 0, "play": 0})
        parser = ctl.build_parser()
        steps = [s for s in body["setup"] + body["steps"] + body["teardown"] if isinstance(s, list)]
        self.assertIn(["perf"], steps)
        for step in steps:
            parser.parse_args(["--serial", "x"] + [str(w) for w in step])

    def ab_reports(self, *sessions, teardown=1200):
        return [{"ok": True, "command": "perf", "sessionId": sid, "summary": f"{sid}\n",
                 "report": {"schema": "droiddeck.perf/2", "teardownMs": teardown, "times": {"readyS": 20.0}}}
                for sid in sessions]

    def test_ab_runs_a_b_b_a_with_the_preference_set_before_each(self):
        self.configure({"state": state(), "prefs": {"ok": True, "command": "prefs"},
                        "perf": self.ab_reports("s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8")})
        path = self.write({"name": "t", "repeat": 5, "steps": [["state"]]})
        code, report = self.run_scenario(path, "--ab", "gameStateHints=false,true", "--repeat", "2", "--cooldown", "0")
        self.assertEqual(0, code)
        self.assertEqual("ABBAABBA", report["ab"]["order"])
        self.assertEqual(8, len(report["runs"]))
        sets = [c["request"] for c in self.calls("prefs")]
        self.assertEqual(["false", "true", "true", "false"] * 2, [r["value"] for r in sets])
        self.assertEqual({("set", "session", "gameStateHints", "bool")}, {(r["op"], r["file"], r["key"], r["type"]) for r in sets})
        for i, run in enumerate(report["runs"]):
            folder = Path(run["folder"])
            ab = json.loads((folder / "ab.json").read_text())
            self.assertEqual(("ABBAABBA"[i], True), (ab["arm"], ab["ok"]))
            self.assertEqual(f"s{i + 1}", run["report"]["sessionId"])
            self.assertEqual(1200, json.loads((folder / "perf-report.json").read_text())["teardownMs"])
            self.assertEqual(f"s{i + 1}\n", (folder / "perf-report.txt").read_text())
            for name in ("ab.json", "perf-report.json", "perf-report.txt"):
                self.assertEqual(0o600, (folder / name).stat().st_mode & 0o777, name)
            # The preference is set first, before the scenario's own steps.
            self.assertEqual(["prefs", "set", "session", "gameStateHints"], json.loads((folder / "steps.json").read_text())[0]["what"][:4])
        # What perf-compare reads: the arms from ab.json.
        with contextlib.redirect_stdout(io.StringIO()) as out:
            self.assertEqual(0, load("perf-compare").main(["--json", report["out"]]))
        compared = json.loads(out.getvalue())
        self.assertEqual(("gameStateHints", "false", "true"), (compared["key"], compared["a"]["value"], compared["b"]["value"]))
        self.assertEqual(4, len(compared["b"]["runs"]))

    def test_ab_waits_for_the_teardown_and_fails_a_run_without_a_report_of_its_own(self):
        self.addCleanup(setattr, scenario, "REPORT_WAIT_S", scenario.REPORT_WAIT_S)
        self.addCleanup(setattr, scenario, "REPORT_POLL_S", scenario.REPORT_POLL_S)
        scenario.REPORT_WAIT_S, scenario.REPORT_POLL_S = 0.5, 0.01
        # The first answer is the run's report before its teardown time is in; then the same session again.
        perf = self.ab_reports("s1", teardown=None) + self.ab_reports("s1")
        self.configure({"state": state(), "prefs": {"ok": True, "command": "prefs"}, "perf": perf})
        path = self.write({"name": "t", "steps": [["state"]]})
        code, report = self.run_scenario(path, "--ab", "auditScope=proton,client", "--cooldown", "0", "--keep-going")
        self.assertEqual(1, code)
        self.assertEqual(1200, report["runs"][0]["report"]["teardownMs"])
        self.assertIn("no new performance report", report["runs"][1]["error"])
        self.assertFalse(json.loads((Path(report["runs"][1]["folder"]) / "ab.json").read_text())["ok"])
        self.assertEqual({"string"}, {c["request"]["type"] for c in self.calls("prefs")})

    def test_a_bad_ab_is_refused_before_adb(self):
        path = self.write({"name": "t", "steps": [["state"]]})
        for spec in ("gameStateHints=true", "gameStateHints=true,true", "=a,b", "k=a,b,c"):
            code, report = self.run_scenario(path, "--ab", spec)
            self.assertEqual(2, code, spec)
            self.assertIn("--ab wants KEY=A,B", report["error"])
        self.assertFalse(self.calls())

    def test_after_steps_run_after_a_failure(self):
        self.configure({"state": state(focused=769), "quit": {"ok": True, "remaining": []}})
        path = self.write({"name": "t", "repeat": 2, "vars": {"appId": 7},
                           "steps": [["state"], {"assert": "session.focus.focusedApp", "equals": 7}],
                           "after": [["quit", "${appId}"]]})
        code, report = self.run_scenario(path, "--keep-going")
        self.assertEqual(1, code)
        self.assertEqual(2, len(report["runs"]))
        self.assertEqual([{"appId": 7, "timeout": 15.0}] * 2, [c["request"] for c in self.calls("quit")])


if __name__ == "__main__":
    unittest.main()
