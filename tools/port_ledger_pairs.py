#!/usr/bin/env python3
"""Lists the top `client` and `seam` gaps of the port ledger with the vanilla method's facts beside the Rust.

The repository is public and never holds Mojang's code (tools/check_public_tree.py), so the vanilla side is
only what the ledger already knows from the bytecode: the method's file and line spans, the callees it
reaches (sorted, not in call order) with whether each has a port, and its ids and rare numbers. The Rust side is
this repository's own source, cut to --rust-lines per function.

    python3 tools/port_ledger_pairs.py --kinds client seam --limit 12 --rust-lines 60
"""

import argparse
from collections import OrderedDict
from pathlib import Path
import sys

try:
    from tools import port_ledger
except ImportError:
    import port_ledger

DEFAULT_KINDS = ("client", "seam")
MAX_CALLEES = 40
MAX_TOKENS = 20


def select_gaps(led, kinds, limit):
    """Per kind, the best-ranked gaps, one entry for each (vanilla method, Rust fn) with its callees gathered."""
    picked = []
    for kind in kinds:
        entries = OrderedDict()
        for g in sorted((g for g in led["gaps"] if g["kind"] == kind), key=port_ledger.gap_rank):
            key = (g.get("vanilla"), g.get("fn"))
            entry = entries.get(key)
            if entry is None:
                if len(entries) >= limit:
                    continue
                entry = entries[key] = {"kind": kind, "gap": g, "callees": []}
            if g.get("callee") and g["callee"] not in entry["callees"]:
                entry["callees"].append(g["callee"])
        picked.extend(entries.values())
    return picked


def java_facts(led, key):
    """What the ledger holds about a vanilla method, as names and numbers only."""
    m = led["methods"].get(key)
    if m is None:
        return None
    spans = ", ".join(f"{a}" if b in (None, a) else f"{a}-{b}" for a, b in m["lines"])
    callees = []
    for c in m.get("callees", [])[:MAX_CALLEES]:
        if c not in led["methods"]:
            continue
        cm = led["methods"][c]
        callees.append((port_ledger.short(cm["class"], cm["name"]), port_ledger.status_of(led, c)))
    tokens = sorted(m.get("tokens", []))
    return {
        "name": port_ledger.short(m["class"], m["name"]),
        "file": m["file"],
        "lines": spans,
        "client": bool(m.get("client")),
        "callees": callees,
        "callees_total": len(m.get("callees", [])),
        "ids": [t[3:] for t in tokens if t.startswith("id:")][:MAX_TOKENS],
        "numbers": [t[2:] for t in tokens if t.startswith("n:")][:MAX_TOKENS],
    }


def rust_excerpt(root, led, fid, max_lines):
    """(header, lines) of one Rust fn; the lines are empty when the file can't be read inside root."""
    if not isinstance(fid, int) or not 0 <= fid < len(led["fns"]):
        return f"fn id {fid!r} is not in the ledger", []
    fn = led["fns"][fid]
    header = port_ledger.fn_ref(led, fid)
    root = Path(root).resolve()
    path = (root / fn["file"]).resolve()
    if root not in path.parents or not path.is_file():
        return header, []
    start, end = fn["line"], fn["end"] or fn["line"]
    try:
        text = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeDecodeError):
        return header, []
    if not 1 <= start <= end <= len(text):
        return header + " (ledger span is past the end of the file: rebuild the ledger)", []
    body = [f"{n:>6}  {text[n - 1]}" for n in range(start, min(end, start + max_lines - 1) + 1)]
    if end - start + 1 > max_lines:
        body.append(f"        ... {end - start + 1 - max_lines} more lines in the file")
    return header, body


def render_entry(root, led, entry, rust_lines):
    g = entry["gap"]
    out = [f"### {port_ledger.gap_text(led, g)}", ""]
    facts = java_facts(led, g.get("vanilla"))
    if facts is None:
        out += ["Vanilla method is not in the ledger.", ""]
    else:
        out.append(f"**Vanilla `{facts['name']}`** ({facts['file']} lines {facts['lines']}), "
                   f"client-only effect: {'yes' if facts['client'] else 'no'}")
        if entry["callees"]:
            names = ", ".join(f"`{port_ledger.short(led['methods'][c]['class'], led['methods'][c]['name'])}`"
                              for c in entry["callees"] if c in led["methods"])
            out.append(f"- callee this port never reaches: {names}")
        shown = ", ".join(f"`{n}` ({s})" for n, s in facts["callees"])
        more = facts["callees_total"] - len(facts["callees"])
        out.append(f"- callees, sorted: {shown or 'none'}" + (f" (+{more} not shown)" if more > 0 else ""))
        if facts["ids"]:
            out.append("- ids: " + ", ".join(f"`{t}`" for t in facts["ids"]))
        if facts["numbers"]:
            out.append("- rare numbers: " + ", ".join(f"`{t}`" for t in facts["numbers"]))
        out.append("")
    fids = [g["fn"]] + ([g["sibling"]] if g.get("sibling") is not None else [])
    for label, fid in zip(("Rust port that misses it", "Rust path that reaches it"), fids):
        header, body = rust_excerpt(root, led, fid, rust_lines)
        out.append(f"**{label}:** `{header}`")
        out += ["```rust", *body, "```", ""] if body else ["(source not readable here)", ""]
    return "\n".join(out)


def render(root, led, kinds, limit, rust_lines, max_chars):
    chosen = select_gaps(led, kinds, limit)
    parts = ["Vanilla side is names and numbers from the bytecode only. No Mojang source is printed.", ""]
    size = sum(len(p) + 1 for p in parts)
    for i, entry in enumerate(chosen):
        text = render_entry(root, led, entry, rust_lines)
        if size + len(text) > max_chars:
            parts.append(f"Stopped after {i} of {len(chosen)} entries at the size limit.")
            break
        parts.append(text)
        size += len(text) + 1
    if not chosen:
        parts.append("No gaps of those kinds.")
    return "\n".join(parts) + "\n"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--kinds", nargs="+", default=list(DEFAULT_KINDS), choices=sorted(port_ledger.GAP_ORDER))
    ap.add_argument("--limit", type=int, default=12, help="entries per kind")
    ap.add_argument("--rust-lines", type=int, default=60, help="Rust lines shown per function")
    ap.add_argument("--max-chars", type=int, default=600_000)
    args = ap.parse_args(argv)
    if args.limit < 1 or args.rust_lines < 1:
        sys.exit("--limit and --rust-lines must be at least 1")
    sys.stdout.write(render(port_ledger.ROOT, port_ledger.load(), args.kinds, args.limit, args.rust_lines,
                            args.max_chars))


if __name__ == "__main__":
    main()
