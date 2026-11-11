import tempfile
import unittest
from pathlib import Path

from tools import port_ledger_consts as consts

JAVA = """package a;
public class Player {
    public void tick() {
        // 99.5 in a comment
        double d = Mth.clamp(this.getX(), -3.0E7, 3.0E7);
        String s = "7.25 in a string";
        /* 88.5
           77.5 */
        this.play(SoundEvents.ANVIL_LAND, 0.07F, 110);
        int small = 1;
    }
}
"""


def ledger():
    return {
        "classes": {"a/Player": {"file": "a/Player.java"}},
        "methods": {"a/Player#tick": {"class": "a/Player", "name": "tick", "file": "a/Player.java", "lines": [[3, 11]]}},
        "fns": [{"id": 0, "name": "guest_orbs", "owner": None, "file": "x.rs", "line": 1, "end": 3}],
        "ported": {"a/Player#tick": [0]}, "mentioned": {}, "inferred": {},
    }


class ConstsTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root / "src/a").mkdir(parents=True)
        (self.root / "src/a/Player.java").write_text(JAVA)
        (self.root / "x.rs").write_text("fn guest_orbs() {\n    let a = 0.07;\n}\n")
        self.dirs = [str(self.root / "src")]

    def tearDown(self):
        self.tmp.cleanup()

    def test_constants_come_in_source_order_without_comments_or_strings(self):
        lines = JAVA.splitlines()
        found = consts.constants_in_order(lines, [[3, 11]])
        self.assertEqual([(n, raw) for n, raw, _ in found],
                         [(5, "-3.0E7"), (5, "3.0E7"), (9, "SoundEvents.ANVIL_LAND"), (9, "0.07F"), (9, "110")])
        self.assertEqual(found[2][2], "id:anvil_land")

    def test_all_adds_the_numbers_the_ledger_skips(self):
        raws = [raw for _, raw, _ in consts.constants_in_order(JAVA.splitlines(), [[3, 11]], include_all=True)]
        self.assertIn("1", raws)
        self.assertNotIn("99.5", raws)
        self.assertNotIn("7.25", raws)

    def test_render_marks_constants_absent_from_the_port(self):
        text = consts.render(self.root, ledger(), self.dirs, ["Player.tick"])
        self.assertIn("| 5 | `-3.0E7` | `n:-30000000.0` | NO |", text)
        self.assertIn("| 9 | `0.07F` | `n:0.07` | yes |", text)
        self.assertNotIn("this.getX", text)

    def test_unknown_method_and_missing_source_are_said_not_skipped(self):
        self.assertIn("Not a method", consts.render(self.root, ledger(), self.dirs, ["Player.nope"]))
        self.assertIn("not found", consts.render(self.root, ledger(), [str(self.root / "elsewhere")], ["Player.tick"]))

    def test_source_path_outside_the_directory_is_refused(self):
        self.assertIsNone(consts.read_source(self.dirs, "../x.rs"))

    def test_queries_file_skips_blank_and_comment_lines(self):
        f = self.root / "q.txt"
        f.write_text("# why\nPlayer.tick  # now\n\n")
        self.assertEqual(consts.queries_from(f), ["Player.tick"])


if __name__ == "__main__":
    unittest.main()
