import io
import os
import sys
import tarfile
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import stage_runtime as s  # noqa: E402
import verify_apk as v  # noqa: E402


class FindPrefix(unittest.TestCase):
    def test_root_at_top(self):
        self.assertEqual(s.find_prefix(["lib/libjli.so", "release"]), "")

    def test_one_folder_above(self):
        self.assertEqual(s.find_prefix(["jre/", "jre/lib/modules", "jre/release"]), "jre/")

    def test_dot_slash_is_ignored(self):
        self.assertEqual(s.find_prefix(["./lib/server/libjvm.so"]), "")

    def test_shortest_wins(self):
        self.assertEqual(s.find_prefix(["a/b/release", "a/release"]), "a/")

    def test_no_marker(self):
        self.assertIsNone(s.find_prefix(["readme.txt", "bin/java"]))


class Paths(unittest.TestCase):
    def test_safe_relative(self):
        self.assertEqual(s.safe_relative("lib/./x"), "lib/x")
        for bad in ("", ".", "/etc/passwd", "..", "../x", "a/../../x"):
            self.assertIsNone(s.safe_relative(bad), bad)

    def test_manifest_lines_sorted(self):
        self.assertEqual(s.manifest_lines([("b", 2), ("a", 10)]), ["10\ta", "2\tb"])


def add(tar, name, data=b"", kind=tarfile.REGTYPE, link=""):
    info = tarfile.TarInfo(name)
    info.type = kind
    info.linkname = link
    info.size = len(data) if kind == tarfile.REGTYPE else 0
    tar.addfile(info, io.BytesIO(data) if kind == tarfile.REGTYPE else None)


class Extract(unittest.TestCase):
    def test_overlay_and_symlink(self):
        with tempfile.TemporaryDirectory() as tmp:
            uni, arm, out = (os.path.join(tmp, n) for n in ("u.tar.xz", "a.tar.xz", "out"))
            with tarfile.open(uni, "w:xz") as t:
                add(t, "jre/lib/modules", b"M")
                add(t, "jre/release", b"R")
                add(t, "jre/lib/alias.so", kind=tarfile.SYMTYPE, link="libjli.so")
            with tarfile.open(arm, "w:xz") as t:
                add(t, "jre/lib/libjli.so", b"J")
                add(t, "jre/lib/server/libjvm.so", b"V")
                add(t, "jre/../../escape", b"X")
            os.makedirs(out)
            links = s.extract(uni, out) + s.extract(arm, out)
            self.assertEqual(s.resolve_links(out, links), [])
            files = dict(s.walk_files(out))
            self.assertEqual(set(files), {"lib/modules", "release", "lib/libjli.so", "lib/server/libjvm.so", "lib/alias.so"})
            self.assertEqual(files["lib/alias.so"], 1)
            self.assertFalse(os.path.exists(os.path.join(tmp, "escape")))

    def test_dangling_link_is_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = os.path.join(tmp, "out")
            os.makedirs(out)
            self.assertEqual(s.resolve_links(out, [("lib/x.so", "nothing.so")]), [("lib/x.so", "nothing.so")])


class VerifyApk(unittest.TestCase):
    def test_check(self):
        manifest = v.parse_manifest("3\tlib/a\n5\trelease\n")
        names = {"assets/jre25/lib/a", "assets/jre25/release", *v.EXTRA}
        sizes = {"assets/jre25/lib/a": 3, "assets/jre25/release": 4}
        self.assertEqual(v.check(names, sizes, manifest), ([], ["release"]))
        self.assertEqual(v.check(names - {"assets/jre25/lib/a", "assets/probe.jar"}, sizes, manifest)[0], ["lib/a", "assets/probe.jar"])


if __name__ == "__main__":
    unittest.main()
