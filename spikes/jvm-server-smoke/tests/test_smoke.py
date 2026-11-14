import json
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
import fetch_mods  # noqa: E402
import summarize  # noqa: E402

MANIFEST = json.loads((HERE / "mods.json").read_text())

LOG_OK = """[07:11:48] [main/INFO] (FabricLoader) Loading 2 mods:
\t- clumps 26.3.2
\t- fabricloader 0.20.1
[07:11:49] [Server thread/INFO]: Preparing level "world"
[07:11:50] [Server thread/INFO]: Done (1.426s)! For help, type "help"
"""
LOG_BAD = """[07:11:48] [main/ERROR]: boom
java.lang.IllegalStateException
[07:11:50] [Server thread/INFO]: Done (2.0s)! For help, type "help"
"""


class FetchTests(unittest.TestCase):
    def test_manifest_hashes_have_expected_shape(self):
        for name, mod in MANIFEST["mods"].items():
            self.assertEqual(len(mod["sha1"]), 40, name)
            self.assertEqual(len(mod["sha512"]), 128, name)

    def test_select_known_and_unknown(self):
        self.assertEqual([n for n, _ in fetch_mods.select(MANIFEST, "clumps")], ["clumps"])
        with self.assertRaises(KeyError):
            fetch_mods.select(MANIFEST, "nope")

    def test_pick_file_matches_by_sha512_only(self):
        version = {"files": [{"hashes": {"sha512": "a"}, "url": "u1"}, {"hashes": {"sha512": "b"}, "url": "u2"}]}
        self.assertEqual(fetch_mods.pick_file(version, "b")["url"], "u2")
        self.assertIsNone(fetch_mods.pick_file(version, "c"))
        self.assertIsNone(fetch_mods.pick_file({}, "a"))

    def test_cdn_url_check(self):
        self.assertTrue(fetch_mods.cdn_url_ok("https://cdn.modrinth.com/data/x/y.jar"))
        self.assertFalse(fetch_mods.cdn_url_ok("http://cdn.modrinth.com/x.jar"))
        self.assertFalse(fetch_mods.cdn_url_ok("https://cdn.modrinth.com.evil.example/x.jar"))


class SummaryTests(unittest.TestCase):
    def test_pass(self):
        r = summarize.summarize(LOG_OK.splitlines())
        self.assertTrue(r["passed"])
        self.assertEqual(r["done_seconds"], "1.426")
        self.assertEqual(r["mods"], ["- clumps 26.3.2", "- fabricloader 0.20.1"])

    def test_problem_fails_even_when_done(self):
        r = summarize.summarize(LOG_BAD.splitlines())
        self.assertFalse(r["passed"])
        self.assertEqual(len(r["problems"]), 2)

    def test_never_done_fails(self):
        self.assertFalse(summarize.summarize(["[x/INFO]: hi"])["passed"])

    def test_escaping(self):
        self.assertEqual(summarize.escape_data("a%b\nc"), "a%25b%0Ac")
        self.assertEqual(summarize.escape_property("a:b,c"), "a%3Ab%2Cc")


if __name__ == "__main__":
    unittest.main()
