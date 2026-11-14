# mod-api-scan (Step 3 tooling)

Reads Fabric mod jars and counts the Mojang (`net/minecraft`) API they reference. Pure Python 3, no dependencies.

    python3 mod_scan.py mod1.jar mod2.jar ...   # per mod: manifest, mixin shapes, class/method/field counts
    python3 api_scan.py fabric-api.jar          # classes referenced by every nested module of a jar-in-jar bundle

Counts come from the constant pool, so they are a lower bound: Mixin targets given as strings and method
signatures resolved only at runtime are not followed. `mod_scan.py` does not descend into nested jars (scan those
separately). Jars are inputs and are never committed.

    python3 mod_contract.py mod.jar > contract.md   # exact Mojang/Fabric surface: Mixin targets, injectors, @At, members

`mod_contract.py` reads only the mod jar. Its output (see docs/jvm-mod-runtime/contracts) lists what a replacement
class set must provide and which injection points it must contain.
