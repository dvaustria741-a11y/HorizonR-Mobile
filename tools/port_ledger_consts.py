#!/usr/bin/env python3
"""Prints the constants of a vanilla method in source order, each marked present or absent in its Rust port.

Only literals are printed: the line number, the literal as written, and the ledger's token for it (the form the
drift gaps compare). No other Mojang source is printed, so the output may stay in a public run summary.

    python3 tools/port_ledger_consts.py Player.tick
    python3 tools/port_ledger_consts.py --file tools/ledger_queries.txt   # one Class.method per line, # comments
    python3 tools/port_ledger_consts.py --all Player.tick                 # also the numbers the ledger skips
"""

import argparse
from pathlib import Path
import re
import sys

try:
    from tools import port_ledger
except ImportError:
    import port_ledger

STRING = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'')


def resolve(led, query):
    """Method keys for `Class.method` (every class the name matches); an unknown name gives an empty list."""
    if "." not in query:
        return []
    cls_q, meth = query.rsplit(".", 1)
    return [k for k in (port_ledger.key(cp, meth) for cp in port_ledger.match_classes(led, cls_q))
            if k in led["methods"]]


def read_source(source_dirs, relative):
    """The lines of a decompiled file from the first source directory that holds it inside that directory."""
    for base in source_dirs:
        root = Path(base).resolve()
        path = (root / relative).resolve()
        if root in path.parents and path.is_file():
            try:
                return path.read_text(encoding="utf-8").splitlines()
            except (OSError, UnicodeDecodeError):
                return None
    return None


def code_of(line, in_block):
    """The line without comments and string literals; whether a block comment is still open after it."""
    out, i = [], 0
    while i < len(line):
        if in_block:
            end = line.find("*/", i)
            if end < 0:
                return "".join(out), True
            i, in_block = end + 2, False
            continue
        start = line.find("/*", i)
        if start < 0:
            out.append(line[i:])
            break
        out.append(line[i:start])
        i, in_block = start + 2, True
    code = "".join(out)
    comment = code.find("//")
    if comment >= 0:
        code = code[:comment]
    return STRING.sub('""', code), in_block


def constants_in_order(lines, spans, include_all=False):
    """(line, literal, token) for each constant in the method's spans, in source order.

    token is the ledger's form ("n:0.07", "id:anvil_land"), or "" for a number the ledger skips (shown with all)."""
    found = []
    for a, b in sorted((s[0], s[1] or s[0]) for s in spans):
        in_block = False
        for n in range(max(a, 1), min(b, len(lines)) + 1):
            code, in_block = code_of(lines[n - 1], in_block)
            row = [(m.start(), m.group(0), "id:" + m.group(2).lower()) for m in port_ledger.JAVA_ID.finditer(code)]
            for m in port_ledger.NUMBER.finditer(code):
                token = port_ledger.number_token(m.group(0)) or ""
                if token or include_all:
                    row.append((m.start(), m.group(0), token))
            found += [(n, raw, token) for _, raw, token in sorted(row)]
    return found


def rust_tokens(root, led, fids):
    """The number tokens the Rust fns spell, and whether every fn could be read."""
    root = Path(root).resolve()
    tokens, readable = set(), True
    for fid in fids:
        if not isinstance(fid, int) or not 0 <= fid < len(led["fns"]):
            readable = False
            continue
        fn = led["fns"][fid]
        path = (root / fn["file"]).resolve()
        try:
            text = path.read_text(encoding="utf-8").splitlines() if root in path.parents else None
        except (OSError, UnicodeDecodeError):
            text = None
        if text is None or not 1 <= fn["line"] <= (fn["end"] or fn["line"]) <= len(text):
            readable = False
            continue
        for line in text[fn["line"] - 1:(fn["end"] or fn["line"])]:
            for m in port_ledger.NUMBER.finditer(STRING.sub('""', line.split("//")[0])):
                t = port_ledger.number_token(m.group(0))
                if t:
                    tokens.add(t)
    return tokens, readable


def render_method(root, led, source_dirs, k, include_all):
    m = led["methods"][k]
    name = port_ledger.short(m["class"], m["name"])
    lines = read_source(source_dirs, m["file"])
    out = [f"### {name} ({m['file']})", ""]
    if lines is None:
        return "\n".join(out + [f"Decompiled source for {m['file']} not found in the source directories.", ""])
    consts = constants_in_order(lines, m["lines"], include_all)
    fids = port_ledger.port_of(led, k)
    have, readable = rust_tokens(root, led, fids) if fids else (set(), True)
    status = port_ledger.status_of(led, k)
    if fids:
        ports = ", ".join(f"`{port_ledger.fn_ref(led, f)}`" for f in fids if isinstance(f, int) and 0 <= f < len(led["fns"]))
        out.append(f"Port ({status}): {ports}" + ("" if readable else " (some of it unreadable: marks below are unsure)"))
    else:
        out.append(f"No Rust port found (status {status}); marks below say only what the literal is.")
    out.append("")
    if not consts:
        return "\n".join(out + ["No constants in the method.", ""])
    out += ["| line | literal | token | in port fn |", "|---:|---|---|---|"]
    for n, raw, token in consts:
        if not fids or not token:
            mark = "-"
        elif token.startswith("n:"):
            mark = "yes" if token in have else "NO"
        else:
            mark = "ids: see drift"
        out.append(f"| {n} | `{raw}` | `{token or 'skipped'}` | {mark} |")
    out.append("")
    out.append("`yes` means the same number is a literal inside the port fn; a port may spell it as a named constant, "
               "a comment or a callee, so `NO` is a lead, not a verdict.")
    return "\n".join(out + [""])


def queries_from(path):
    lines = Path(path).read_text(encoding="utf-8").splitlines()
    return [l.split("#")[0].strip() for l in lines if l.split("#")[0].strip()]


def render(root, led, source_dirs, queries, include_all=False):
    parts = ["Constants only: no Mojang source is printed.", ""]
    for q in queries:
        keys = resolve(led, q)
        if not keys:
            parts += [f"### {q}", "", "Not a method of the pinned version (use `Class.method`).", ""]
        for k in keys:
            parts.append(render_method(root, led, source_dirs, k, include_all))
    return "\n".join(parts) + "\n"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("queries", nargs="*", help="Class.method")
    ap.add_argument("--file", help="a file with one Class.method per line")
    ap.add_argument("--all", action="store_true", help="also the numbers the ledger skips as trivial")
    args = ap.parse_args(argv)
    queries = list(args.queries) + (queries_from(args.file) if args.file else [])
    if not queries:
        sys.exit("Name a Class.method, or pass --file with a list.")
    dirs = port_ledger.default_sources()
    if not dirs:
        sys.exit("No decompiled sources: run `python3 tools/lab.py decompile` first.")
    sys.stdout.write(render(port_ledger.ROOT, port_ledger.load(), dirs, queries, args.all))


if __name__ == "__main__":
    main()
