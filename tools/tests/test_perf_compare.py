"""tools/perf-compare: grouping an A/B's runs, medians and spreads, and the win and regression rule."""
import contextlib
import importlib.machinery
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parents[1]


def load(name):
    loader = importlib.machinery.SourceFileLoader(name.replace("-", "_"), str(TOOLS / name))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


compare = load("perf-compare")


def report(ready, fps=None, p99=None, mw=None, toggles=None, schema="droiddeck.perf/2", plugged=False, game=None):
    """A report with READY at [ready] s and, in game, [fps], [p99] ms, [mw] and a game tracer's [game] stops/s."""
    in_game = {"name": "in_game", "seconds": 120.0, "stopsPerSecond": 900.0, "tracerPct": 4.0, "treePct": 120.0}
    if fps is not None:
        in_game["frames"] = {"count": 7000, "fps": fps, "p50Ms": 16.7, "p95Ms": 18.0, "p99Ms": p99, "stutters": 6}
    if mw is not None:
        in_game["power"] = {"meanMw": mw, "peakMw": mw + 1000, "plugged": plugged, "samples": 120}
    if game is not None:
        in_game["tracers"] = [{"role": "session", "stopsPerSecond": 100.0}, {"role": "game", "stopsPerSecond": game}]
    return {"schema": schema, "toggles": toggles if toggles is not None else {"prootFastPath": True},
            "times": {"readyS": ready, "bigPictureS": ready + 8, "games": [{"appId": 7, "launchToFocusS": 40.0}]},
            "phases": [{"name": "startup", "seconds": ready, "stopsPerSecond": 5000.0}, in_game]}


class PerfCompareTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.dir = Path(self.temp.name)

    def run_dir(self, name, report_body, ab=None, steps=False):
        folder = self.dir / name
        folder.mkdir(parents=True)
        if steps:
            (folder / "steps.json").write_text(json.dumps([
                {"step": 0, "what": ["stop"], "result": {"ok": True, "command": "stop"}},
                {"step": 1, "what": ["perf"], "result": {"ok": True, "command": "perf", "report": report_body}},
            ]))
        else:
            (folder / "perf-report.json").write_text(json.dumps(report_body))
        if ab is not None:
            (folder / "ab.json").write_text(json.dumps(ab))
        return folder

    def run_compare(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = compare.main([str(a) for a in argv])
        return code, out.getvalue(), err.getvalue()

    def rows(self, *argv):
        code, out, err = self.run_compare("--json", *argv)
        self.assertNotEqual(2, code, err)
        result = json.loads(out)
        return code, result, {row["metric"]: row for row in result["rows"]}

    def scenario(self, a_reports, b_reports, key="gameStateHints"):
        """A droiddeck-scenario --ab output folder: A B B A, then A B B A again."""
        out = self.dir / "perf-baseline-1"
        a_left, b_left = list(a_reports), list(b_reports)
        run = 0
        for arm in "ABBAABBA":
            pool = a_left if arm == "A" else b_left
            if not pool:
                continue
            run += 1
            value = "false" if arm == "A" else "true"
            body = pool.pop(0)
            body["toggles"] = {"prootFastPath": True, key: value == "true"}
            self.run_dir(f"perf-baseline-1/run-{run:02d}", body,
                         {"key": key, "value": value, "arm": arm, "a": "false", "b": "true", "ok": True})
        (out / "report.json").write_text(json.dumps({"ab": {"key": key, "a": "false", "b": "true", "order": "ABBAABBA"}}))
        return out

    def test_a_win_beyond_five_percent_and_the_spread(self):
        out = self.scenario([report(20.0), report(20.5), report(19.8), report(20.2)],
                            [report(18.0), report(18.2), report(17.9), report(18.1)])
        code, result, rows = self.rows(out)
        self.assertEqual(0, code)
        self.assertEqual(("gameStateHints", "false", "true"), (result["key"], result["a"]["value"], result["b"]["value"]))
        ready = rows["READY s"]
        self.assertEqual(4, ready["a"]["n"])
        self.assertAlmostEqual(20.1, ready["a"]["median"])
        self.assertAlmostEqual(0.7, ready["a"]["spread"])
        self.assertAlmostEqual(18.05 - 20.1, ready["delta"])
        self.assertEqual("win", ready["verdict"])
        # launch to focus did not move: no verdict.
        self.assertEqual("", rows["launch to focus s"]["verdict"])

    def test_a_change_within_as_spread_is_no_win(self):
        out = self.scenario([report(20.0), report(23.0), report(19.0)], [report(19.0), report(19.2), report(18.9)])
        _, _, rows = self.rows(out)
        # 1 s better, but A alone spread over 4 s.
        self.assertEqual("", rows["READY s"]["verdict"])

    def test_a_guarded_metric_that_gets_worse_is_a_regression(self):
        out = self.scenario([report(20, fps=60.0, p99=20.0, mw=4000), report(20, fps=59.0, p99=21.0, mw=4100),
                             report(20, fps=60.0, p99=20.0, mw=4050)],
                            [report(20, fps=50.0, p99=20.5, mw=4060), report(20, fps=51.0, p99=20.4, mw=4000),
                             report(20, fps=50.0, p99=20.0, mw=4100)])
        code, _, rows = self.rows(out)
        self.assertEqual(1, code)
        self.assertEqual("regression", rows["in game fps"]["verdict"])
        self.assertEqual("", rows["in game p99 ms"]["verdict"])
        self.assertEqual("", rows["in game power mW"]["verdict"])
        _, text, _ = self.run_compare(out)
        self.assertIn("REGRESSION", text)
        # Worse on a metric nobody guards is shown, not flagged.
        a = [self.run_dir(f"worse-traps/a{i}", report(20, game=g, toggles={"x": False})) for i, g in enumerate([100, 102, 101])]
        b = [self.run_dir(f"worse-traps/b{i}", report(20, game=g, toggles={"x": True})) for i, g in enumerate([200, 210, 205])]
        code, _, rows = self.rows(*a, *b)
        self.assertEqual(0, code)
        self.assertEqual("", rows["in game game t/s"]["verdict"])
        self.assertGreater(rows["in game game t/s"]["delta"], 90)

    def test_schema_1_reports_are_read_and_others_skipped(self):
        a = [self.run_dir(f"a{i}", report(r, toggles={"prootFastPath": False}, schema="droiddeck.perf/1"))
             for i, r in enumerate([20, 21, 20])]
        b = [self.run_dir(f"b{i}", report(r, toggles={"prootFastPath": True}, schema="droiddeck.perf/1"))
             for i, r in enumerate([15, 15.5, 15])]
        odd = self.run_dir("odd", dict(report(1), schema="droiddeck.perf/9"))
        code, result, rows = self.rows(*a, *b, odd)
        self.assertEqual(0, code)
        # The key is the one toggle that differs; a boolean's A is false.
        self.assertEqual(("prootFastPath", "false"), (result["key"], result["a"]["value"]))
        self.assertEqual("win", rows["READY s"]["verdict"])
        self.assertNotIn("in game fps", rows)
        self.assertTrue(any("odd" in n and "skipped" in n for n in result["notes"]))

    def test_the_key_must_be_clear(self):
        a = self.run_dir("a", report(20, toggles={"x": False, "y": False}))
        b = self.run_dir("b", report(20, toggles={"x": True, "y": True}))
        code, _, err = self.run_compare(a, b)
        self.assertEqual(2, code)
        self.assertIn("x, y differ", err)
        _, result, _ = self.rows("--key", "y", a, b)
        self.assertEqual("y", result["key"])
        # A key set in one run and never in the other is "unset" there.
        c = self.run_dir("c", report(20, toggles={}))
        d = self.run_dir("d", report(20, toggles={"auditScope": "client"}))
        _, result, _ = self.rows(c, d)
        self.assertEqual(("auditScope", "unset", "client"), (result["key"], result["a"]["value"], result["b"]["value"]))

    def test_a_run_folder_from_before_ab_holds_its_report_in_steps(self):
        a = [self.run_dir(f"a{i}", report(20, toggles={"k": "proton"}), steps=True) for i in range(3)]
        b = [self.run_dir(f"b{i}", report(30, toggles={"k": "client"}), steps=True) for i in range(3)]
        code, result, rows = self.rows("--a", "proton", *a, *b)
        self.assertEqual(1, code)
        self.assertEqual("regression", rows["READY s"]["verdict"])
        self.assertEqual("proton", result["a"]["value"])

    def test_failed_runs_and_mixed_power_are_called_out(self):
        out = self.scenario([report(20, mw=4000, plugged=True), report(20, mw=4000)], [report(20, mw=4000), report(20, mw=4000)])
        (out / "run-02" / "ab.json").write_text(json.dumps({"key": "gameStateHints", "value": "true", "arm": "B", "ok": False}))
        _, result, _ = self.rows(out)
        notes = "\n".join(result["notes"])
        self.assertIn("run-02: skipped, the run failed", notes)
        self.assertIn("plugged in and some not", notes)
        self.assertIn("fewer than 3 runs", notes)


if __name__ == "__main__":
    unittest.main()
