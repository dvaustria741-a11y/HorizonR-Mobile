#!/usr/bin/env python3
"""Run 1.21.11's oracles on the inputs of the engine's parity tests and compare with the engine (26.3 behaviour).

For each `tools/test_<name>_parity.py` that keeps to the common shape (a `corpus()` of inputs, an oracle taking
`<mode> <dir>` and reading `<dir>/inputs.txt`, a `world_probe` fixture writing `<dir>/rust.txt`) this writes the corpus,
compiles the oracle (patched copy first, see port_oracles_1_21_11.py), runs it on the remapped 1.21.11 jar and the
fixture on the engine, and counts the differing output lines. Equal output means 1.21.11 behaves as the engine does on
those inputs; different output is a gameplay difference or a porting fault in the oracle: read the lines.

  oracle_diff_1_21_11.py --jar mojmap.jar --libraries DIR --probe world_probe --patched DIR --work DIR [--only NAME]
"""
import argparse, importlib, json, os, pathlib, re, shutil, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
TOOLS = ROOT / "tools"
TEST = ROOT / "mods/horizon/src/test"
PARITY = "dev/horizonlab/parity"


def shape(script):
    text = (TOOLS / script).read_text(encoding="utf-8")
    oracle = re.search(r"parity/(Vanilla\w+Oracle)\.java", text)
    fixture = re.search(r"run\(\[binary,'(\w+)',build\]", text)
    simple = "def corpus" in text and "java(side,side,build)" in text and "inputs.txt" in text
    return (oracle[1], fixture[1]) if oracle and fixture and simple else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", required=True)
    ap.add_argument("--libraries", required=True)
    ap.add_argument("--probe", required=True)
    ap.add_argument("--patched", required=True)
    ap.add_argument("--work", required=True)
    ap.add_argument("--only")
    ap.add_argument("--keep", action="store_true", help="keep each probe's inputs and outputs (they can be large)")
    a = ap.parse_args()
    work = pathlib.Path(a.work)
    work.mkdir(parents=True, exist_ok=True)
    libs = sorted(str(j) for j in pathlib.Path(a.libraries).rglob("*.jar"))
    cp = os.pathsep.join([a.jar, *libs])
    sys.path.insert(0, str(TOOLS))
    results = {}
    report = work / "report.json"
    for script in sorted(p.name for p in TOOLS.glob("test_*_parity.py")):
        found = shape(script)
        if not found or (a.only and a.only not in script):
            continue
        oracle, fixture = found
        d = work / script[5:-10]
        d.mkdir(exist_ok=True)
        entry = results[script] = {"oracle": oracle, "fixture": fixture}
        try:
            module = importlib.import_module(script[:-3])
            n = 0
            with (d / "inputs.txt").open("w", encoding="utf-8") as f:
                for row in module.corpus():
                    f.write(json.dumps(row, separators=(",", ":")) + "\n")
                    n += 1
            entry["inputs"] = n
            source = next(p for p in (pathlib.Path(a.patched) / PARITY / f"{oracle}.java",
                                      TEST / "java-1.21.11" / PARITY / f"{oracle}.java",
                                      TEST / "java" / PARITY / f"{oracle}.java") if p.is_file())
            classes = d / "classes"
            classes.mkdir(exist_ok=True)
            subprocess.run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "-proc:none", "-encoding", "UTF-8",
                            "-nowarn", "-cp", cp, "-sourcepath",
                            f"{a.patched}:{TEST / 'java-1.21.11'}:{TEST / 'java'}", "-d", str(classes), str(source)],
                           check=True, capture_output=True, timeout=300)
            run = subprocess.run(["java", "-Xmx512M", "-cp", os.pathsep.join([str(classes), cp]),
                                  f"dev.horizonlab.parity.{oracle}", "server", str(d)],
                                 capture_output=True, text=True, timeout=600)
            if run.returncode != 0 or not (d / "server.txt").is_file():
                # A crashed oracle leaves a short output: that is not a difference in behaviour.
                lines = (run.stdout + run.stderr).strip().splitlines()
                entry["status"] = "oracle failed: " + (lines[-1][:200] if lines else f"exit {run.returncode}")
            else:
                subprocess.run([a.probe, fixture, str(d)], check=True, capture_output=True, timeout=600)
                x = (d / "server.txt").read_text(encoding="utf-8").split("\n")
                y = (d / "rust.txt").read_text(encoding="utf-8").split("\n")
                differ = sum(1 for p, q in zip(x, y) if p != q) + abs(len(x) - len(y))
                entry.update(status="equal" if not differ else "differs", lines=len(x), differing_lines=differ)
        except subprocess.CalledProcessError as e:
            entry["status"] = "failed: " + ((e.stderr or b"").decode(errors="replace").strip().splitlines() or [str(e)])[-1][:200]
        except Exception as e:  # a script that does not keep to the shape, a timeout
            entry["status"] = f"failed: {type(e).__name__}: {str(e)[:160]}"
        if not a.keep:
            for leftover in list(d.glob("*.txt")):
                if entry.get("status") == "equal" or leftover.stat().st_size > 2_000_000:
                    leftover.unlink()
            shutil.rmtree(d / "classes", ignore_errors=True)
        print(script, entry["status"], flush=True)
        report.write_text(json.dumps(results, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
