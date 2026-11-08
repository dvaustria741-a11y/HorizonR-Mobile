#!/usr/bin/env python3
"""Run the Java exporters against Minecraft 1.21.11, whose jars are obfuscated.

  export_1_21_11.py --client-jar 1.21.11.jar --mappings client.txt --libraries DIR --out OUT [--exporters A,B]

1. Remaps the client jar to Mojang's names with `tools/RemapJar.java` (the JDK's built-in ASM; JDK 21).
2. Compiles the exporters with the 1.21.11 overlay (`mods/horizon/src/test/java-1.21.11`, searched before the
   26.3 sources) against the remapped jar.
3. Runs them; each writes its file into OUT.

`--libraries` is the folder of library jars: the `libraries` folder a server's data generator unpacks
(`java -DbundlerMainClass=net.minecraft.data.Main -jar server.jar --reports --server --output out`). The
mappings file is Mojang's `client_mappings` (lines that are not mappings, such as a licence header, are skipped).
Mojang's files and the outputs are not part of this repository.
"""
import argparse
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
TESTS = ROOT / "mods/horizon/src/test"
PARITY = "dev/horizonlab/parity"
# exporter -> (output file, arguments before the output path): `VanillaHeightmapOracle export` writes the
# registry's `facts.json`; the gameplay exporter writes `gameplay-facts.json`.
EXPORTERS = {
    "VanillaHeightmapOracle": ("facts.json", ["export"]),
    "VanillaGameplayFactsExporter": ("gameplay-facts.json", []),
    "VanillaEntityTypesExporter": ("entity-types.json", []),
    "VanillaBlockBehaviourExporter": ("block-behaviours.json", []),
    "VanillaStructureBlockExporter": ("structure-blocks.json", []),
    "Vanilla1_21_11MotionTagsExporter": ("motion-tags.json", []),
    "VanillaCreativeTabsExporter": ("creative-tabs.json", []),
    "VanillaCommandSuggestionsExporter": ("command-suggestions.tsv", []),
}
ASM = [f"--add-exports=java.base/jdk.internal.org.objectweb.asm{s}=ALL-UNNAMED" for s in ("", ".commons")]


def run(cmd, **kw):
    # The classpath is long; show the command without it.
    shown, skip = [], False
    for part in cmd:
        shown.append("<classpath>" if skip else str(part))
        skip = part == "-cp"
    print("+", " ".join(shown), flush=True)
    subprocess.run(cmd, check=True, **kw)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--client-jar", required=True)
    p.add_argument("--mappings", required=True)
    p.add_argument("--libraries", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--exporters", default=",".join(EXPORTERS))
    a = p.parse_args()
    out = pathlib.Path(a.out)
    (out / "classes").mkdir(parents=True, exist_ok=True)
    mapped = out / "client-mojmap.jar"
    run(["java", *ASM, str(ROOT / "tools/RemapJar.java"), a.mappings, a.client_jar, str(mapped)])
    libs = sorted(str(j) for j in pathlib.Path(a.libraries).rglob("*.jar"))
    cp = ":".join([str(mapped), *libs])
    names = a.exporters.split(",")
    # 26.3 sources that compile against 1.21.11 after mechanical renames are patched copies (port_oracles_1_21_11.py).
    run(["python3", str(ROOT / "tools/port_oracles_1_21_11.py"), "--out", str(out / "patched")])
    sources = []
    for n in names:
        for candidate in (out / "patched" / PARITY / f"{n}.java", TESTS / "java-1.21.11" / PARITY / f"{n}.java",
                          TESTS / "java" / PARITY / f"{n}.java"):
            if candidate.is_file():
                sources.append(str(candidate))
                break
    run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "-proc:none", "-encoding", "UTF-8", "-nowarn",
         "-cp", cp, "-sourcepath", f"{out / 'patched'}:{TESTS / 'java-1.21.11'}:{TESTS / 'java'}", "-d", str(out / "classes"), *sources])
    for n in names:
        file, before = EXPORTERS[n]
        run(["java", "-Xmx1500m", "-cp", f"{out / 'classes'}:{cp}", f"dev.horizonlab.parity.{n}", *before, str(out / file)])
    return 0


if __name__ == "__main__":
    sys.exit(main())
