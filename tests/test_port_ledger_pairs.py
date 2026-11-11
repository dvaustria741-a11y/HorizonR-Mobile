import tempfile
import unittest
from pathlib import Path

from tools import port_ledger_pairs as pairs


def ledger():
    return {
        "methods": {
            "a/Camera.alignWithEntity": {"class": "a/Camera", "name": "alignWithEntity", "file": "a/Camera.java",
                                         "lines": [[10, 30]], "client": True, "tokens": ["id:happy_villager", "n:0.07"],
                                         "callees": ["a/Camera.getMaxZoom", "a/Camera.missing"]},
            "a/Camera.getMaxZoom": {"class": "a/Camera", "name": "getMaxZoom", "file": "a/Camera.java",
                                    "lines": [[40, 44]], "client": False, "tokens": [], "callees": []},
        },
        "fns": [
            {"id": 0, "name": "remote_cam", "owner": None, "file": "crates/x/src/remote.rs", "line": 2, "end": 5},
            {"id": 1, "name": "local_cam", "owner": None, "file": "crates/x/src/local.rs", "line": 1, "end": 2},
        ],
        "ported": {}, "mapped": {}, "absent": {}, "skipped": {}, "mentioned": {}, "inferred": {},
        "gaps": [
            {"kind": "seam", "vanilla": "a/Camera.alignWithEntity", "callee": "a/Camera.getMaxZoom", "fn": 0, "sibling": 1,
             "cluster": [0]},
            {"kind": "seam", "vanilla": "a/Camera.alignWithEntity", "callee": "a/Camera.missing", "fn": 0, "sibling": 1,
             "cluster": [0]},
            {"kind": "client", "vanilla": "a/Camera.alignWithEntity", "fn": 1},
            {"kind": "drift", "vanilla": "a/Camera.alignWithEntity", "fn": 1, "ids": [], "numbers": []},
        ],
    }


class PairsTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        (root / "crates/x/src").mkdir(parents=True)
        (root / "crates/x/src/remote.rs").write_text("// head\nfn remote_cam() {\n    a();\n    b();\n}\n")
        (root / "crates/x/src/local.rs").write_text("fn local_cam() {\n}\n")
        self.root = root

    def tearDown(self):
        self.tmp.cleanup()

    def test_one_entry_per_method_and_fn_with_callees_gathered(self):
        chosen = pairs.select_gaps(ledger(), ("seam", "client"), 5)
        self.assertEqual([e["kind"] for e in chosen], ["seam", "client"])
        self.assertEqual(sorted(chosen[0]["callees"]), ["a/Camera.getMaxZoom", "a/Camera.missing"])

    def test_limit_applies_per_kind(self):
        led = ledger()
        led["gaps"].append(dict(led["gaps"][0], vanilla="a/Camera.getMaxZoom", callee="a/Camera.missing"))
        self.assertEqual(len(pairs.select_gaps(led, ("seam",), 1)), 1)

    def test_require_numbers_keeps_only_drift_that_names_constants_most_first(self):
        led = ledger()
        led["gaps"] += [
            {"kind": "drift", "vanilla": "a/Camera.getMaxZoom", "fn": 1, "ids": [], "numbers": ["n:0.07"]},
            {"kind": "drift", "vanilla": "a/Camera.alignWithEntity", "fn": 0, "ids": ["id:x"], "numbers": ["n:0.1", "n:0.2"]},
        ]
        chosen = pairs.select_gaps(led, ("drift",), 5, require_numbers=True)
        self.assertEqual([e["gap"]["vanilla"] for e in chosen], ["a/Camera.alignWithEntity", "a/Camera.getMaxZoom"])
        self.assertEqual(len(pairs.select_gaps(led, ("drift",), 5)), 3)

    def test_java_facts_are_names_and_numbers_only(self):
        facts = pairs.java_facts(ledger(), "a/Camera.alignWithEntity")
        self.assertEqual(facts["lines"], "10-30")
        self.assertEqual(facts["ids"], ["happy_villager"])
        self.assertEqual(facts["numbers"], ["0.07"])
        self.assertEqual([n for n, _ in facts["callees"]], ["Camera.getMaxZoom"])
        self.assertIsNone(pairs.java_facts(ledger(), "nope"))

    def test_rust_excerpt_cuts_long_functions_and_refuses_paths_outside_root(self):
        header, body = pairs.rust_excerpt(self.root, ledger(), 0, 2)
        self.assertIn("remote.rs:2-5 remote_cam", header)
        self.assertEqual(len(body), 3)
        self.assertIn("2 more lines", body[-1])
        led = ledger()
        led["fns"][0]["file"] = "../outside.rs"
        self.assertEqual(pairs.rust_excerpt(self.root, led, 0, 10)[1], [])
        self.assertEqual(pairs.rust_excerpt(self.root, ledger(), 7, 10)[1], [])

    def test_stale_span_is_reported_not_zero_filled(self):
        led = ledger()
        led["fns"][0]["end"] = 99
        header, body = pairs.rust_excerpt(self.root, led, 0, 10)
        self.assertEqual(body, [])
        self.assertIn("rebuild the ledger", header)

    def test_render_stops_at_size_limit_and_prints_no_vanilla_source(self):
        full = pairs.render(self.root, ledger(), ("seam", "client"), 5, 10, 600_000)
        self.assertIn("fn remote_cam", full)
        self.assertIn("never reaches", full)
        cut = pairs.render(self.root, ledger(), ("seam", "client"), 5, 10, 300)
        self.assertIn("Stopped after", cut)


if __name__ == "__main__":
    unittest.main()
