# SPDX-License-Identifier: AGPL-3.0-only
"""Generate the Fleet Telemetry vs BLE signal matrix.

Cloud data comes from Tesla's Fleet Telemetry protos (teslamotors/fleet-telemetry,
vendored and pinned under tools/signal-matrix/upstream/fleet-telemetry/). BLE data
comes from the protos pinned in protocol/src/main/proto (our fork of teslamotors/vehicle-command).

The curated cross-map lives in mapping.json. This script validates it against both
proto catalogs and emits docs/reference/fleet-telemetry-vs-ble.md.

Usage:
  python tools/signal-matrix/generate.py            # regenerate the doc (offline)
  python tools/signal-matrix/generate.py --check    # fail if the doc is stale
  python tools/signal-matrix/generate.py --refresh  # update vendored fleet protos from upstream
  python tools/signal-matrix/generate.py --status   # print pinned, fork and upstream SHAs
  python tools/signal-matrix/generate.py --dump     # list parsed message fields
"""

import argparse
import difflib
import json
import os
import re
import sys
import urllib.request
from pathlib import Path

TOOL_DIR = Path(__file__).resolve().parent
REPO = TOOL_DIR.parents[1]
DOC = REPO / "docs" / "reference" / "fleet-telemetry-vs-ble.md"
MAPPING = TOOL_DIR / "mapping.json"
FT_DIR = TOOL_DIR / "upstream" / "fleet-telemetry"
FT_PIN = FT_DIR / "FLEET_TELEMETRY_COMMIT"
DOCS_TSV = TOOL_DIR / "upstream" / "tesla-docs" / "available-data.tsv"
TESLA_COMMIT = REPO / "protocol" / "src" / "main" / "proto" / "TESLA_COMMIT"

FT_API = "https://api.github.com/repos/teslamotors/fleet-telemetry"
# The pin points into our fork: comparing it with the fork tip says whether the pin is current.
VC_REPO = "opencartools/vehicle-command"
VC_API = f"https://api.github.com/repos/{VC_REPO}"
# Tesla upstream: printed as the next rebase target, never compared for equality
# (a fork commit can never equal an upstream SHA).
VC_UPSTREAM_API = "https://api.github.com/repos/teslamotors/vehicle-command"
VC_PROTO_PATH = "pkg/protocol/protobuf"

# ---------------------------------------------------------------------------
# Proto parsing
# ---------------------------------------------------------------------------

TOKEN = re.compile(
    r"(message|enum|oneof)\s+(\w+)\s*\{|\{|\}|([\w.]+)\s+(\w+)\s*=\s*(\d+)\s*;"
)


def split_comment(line, in_block):
    """Return (code, comment text, in_block), honouring // and /* ... */."""
    out, comments = [], []
    i = 0
    while i < len(line):
        if in_block:
            end = line.find("*/", i)
            if end == -1:
                comments.append(line[i:])
                return "".join(out), " ".join(comments).strip(), True
            comments.append(line[i:end])
            i = end + 2
            in_block = False
        else:
            block = line.find("/*", i)
            line_comment = line.find("//", i)
            if line_comment != -1 and (block == -1 or line_comment < block):
                out.append(line[i:line_comment])
                comments.append(line[line_comment + 2:])
                return "".join(out), " ".join(comments).strip(), False
            if block != -1:
                out.append(line[i:block])
                comments.append(line[block + 2:])
                i = block + 2
                in_block = True
            else:
                out.append(line[i:])
                break
    return "".join(out), " ".join(comments).strip(), in_block


PRECISION_RE = re.compile(r"\d+\s+decimals?|hundredths?|tenths?|seconds\s*/\s*datetime|precision", re.IGNORECASE)


def ble_precision_note(comment):
    """Return the comment when it documents precision/units, else empty."""
    if comment and PRECISION_RE.search(comment):
        return comment
    return ""


def parse_messages(paths):
    """Return {full_name: {"file": name, "fields": [{name,type,repeated,comment}]}}.

    full_name is dotted for nested messages; cataloguing uses top-level names.
    Field comments capture trailing `//` comments and preceding comment lines
    (Tesla documents precision that way, e.g. `// 2 decimals`).
    """
    messages = {}
    for path in paths:
        path = Path(path)
        lines = path.read_text(encoding="utf-8").splitlines()
        code_lines, line_comments = [], []
        in_block = False
        for line in lines:
            code, comment, in_block = split_comment(line, in_block)
            code_lines.append(code)
            line_comments.append(comment)
        code = "\n".join(code_lines)

        line_starts, offset = [], 0
        for code_line in code_lines:
            line_starts.append(offset)
            offset += len(code_line) + 1

        stack = []  # (kind, name)
        fields_by_line = {}
        line_index = 0
        for m in TOKEN.finditer(code):
            while line_index + 1 < len(line_starts) and line_starts[line_index + 1] <= m.start():
                line_index += 1
            if m.group(1):
                kind, name = m.group(1), m.group(2)
                stack.append((kind, name))
                if kind == "message":
                    full = ".".join(n for k, n in stack if k == "message")
                    messages.setdefault(full, {"file": path.name, "fields": []})
            elif m.group(0) == "{":
                stack.append(("block", None))
            elif m.group(0) == "}":
                if stack:
                    stack.pop()
            else:
                ftype, fname = m.group(3), m.group(4)
                if ftype in ("option", "reserved", "import", "syntax", "package"):
                    continue
                for kind, name in reversed(stack):
                    if kind == "message":
                        full = ".".join(n for k, n in stack if k == "message")
                        messages.setdefault(full, {"file": path.name, "fields": []})
                        field = {"name": fname, "type": ftype, "repeated": ftype == "repeated", "comment": ""}
                        messages[full]["fields"].append(field)
                        fields_by_line.setdefault(line_index, []).append(field)
                        break

        pending, pending_type, last_was_comment = [], None, False
        for line_index, code_line in enumerate(code_lines):
            comment = line_comments[line_index]
            line_fields = fields_by_line.get(line_index, [])
            prior_pending = list(pending)
            if not code_line.strip():
                if comment:
                    pending = pending + [comment] if last_was_comment else [comment]
                    last_was_comment = True
                else:
                    pending, pending_type, last_was_comment = [], None, False
                continue
            last_was_comment = False
            if re.match(r"\s*(message|enum|service)\b", code_line):
                pending, pending_type = [], None
                continue
            if line_fields:
                if comment:
                    for field in line_fields:
                        field["comment"] = comment
                    pending, pending_type = [], None
                    continue
                if prior_pending:
                    first_type = line_fields[0]["type"]
                    if pending_type is not None and first_type != pending_type:
                        pending, pending_type = [], None
                    else:
                        pending_type = pending_type or first_type
                        note = " ".join(prior_pending)
                        for field in line_fields:
                            field["comment"] = note
    return messages


def parse_field_enum(path):
    """Return [{name, number, comment}] for `enum Field` in vehicle_data.proto."""
    lines = Path(path).read_text(encoding="utf-8").splitlines()
    fields, in_enum = [], False
    entry = re.compile(r"^\s*(\w+)\s*=\s*(\d+)\s*;\s*(?://\s*(.*))?$")
    for line in lines:
        if re.match(r"^enum Field\s*\{", line):
            in_enum = True
            continue
        if in_enum and line.strip().startswith("}"):
            break
        if not in_enum:
            continue
        m = entry.match(line)
        if m and m.group(1) != "Unknown":
            fields.append(
                {"name": m.group(1), "number": int(m.group(2)), "comment": (m.group(3) or "").strip()}
            )
    return fields


def import_docs(markdown_path):
    """Convert the rendered Tesla 'Available Data' page (markdown) into a TSV.

    The developer.tesla.com page is client-rendered, so this import is manual:
    fetch the page (browser or webfetch), save the markdown, then run
    `generate.py --import-docs <file>`.
    """
    lines = Path(markdown_path).read_text(encoding="utf-8").splitlines()
    rows, in_table = [], False
    for line in lines:
        stripped = line.strip()
        if stripped.startswith("| Field | Category | Type |") and stripped.count("|") >= 6:
            in_table = True
            continue
        if not in_table:
            continue
        if not stripped.startswith("|"):
            if rows:
                break
            continue
        cells = [cell.strip() for cell in stripped.strip("|").split("|")]
        if len(cells) < 5 or cells[0].startswith("---") or not cells[0]:
            continue
        field, category, ftype, json_eq, description = cells[:5]
        if field == "Field":
            continue
        clean = lambda s: s.replace("\\_", "_").replace("\\.", ".").replace("\\>", ">")
        rows.append((field, clean(category), clean(ftype), clean(json_eq), clean(description)))
    if not rows:
        raise SystemExit(f"no signal table found in {markdown_path}")
    DOCS_TSV.parent.mkdir(parents=True, exist_ok=True)
    with DOCS_TSV.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write("field\tcategory\ttype\tjson_equivalent\tdescription\n")
        for row in rows:
            handle.write("\t".join(row) + "\n")
    return len(rows)


def load_docs():
    """Return {signal: {category, type, json, description}} from the vendored TSV."""
    if not DOCS_TSV.exists():
        return {}
    docs = {}
    lines = DOCS_TSV.read_text(encoding="utf-8").splitlines()
    for line in lines[1:]:
        cells = line.split("\t")
        if len(cells) < 5:
            continue
        docs[cells[0]] = {
            "category": cells[1],
            "type": cells[2],
            "json": cells[3],
            "description": cells[4],
        }
    return docs


# ---------------------------------------------------------------------------
# Upstream refresh / status
# ---------------------------------------------------------------------------


def gh_json(url):
    req = urllib.request.Request(
        url, headers={"User-Agent": "TeslaBatteryBLE-signal-matrix", "Accept": "application/vnd.github+json"}
    )
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=60) as response:
        return json.load(response)


def latest_path_commit(api, path):
    data = gh_json(f"{api}/commits?path={path}&per_page=1")
    return data[0]["sha"] if data else None


def download(url, dest):
    req = urllib.request.Request(url, headers={"User-Agent": "TeslaBatteryBLE-signal-matrix"})
    with urllib.request.urlopen(req, timeout=120) as response:
        dest.write_bytes(response.read())


def refresh_fleet():
    sha = latest_path_commit(FT_API, "protos")
    listing = gh_json(f"{FT_API}/contents/protos?ref={sha}")
    names = sorted(item["name"] for item in listing if item["name"].endswith(".proto"))
    FT_DIR.mkdir(parents=True, exist_ok=True)
    for name in names:
        download(
            f"https://raw.githubusercontent.com/teslamotors/fleet-telemetry/{sha}/protos/{name}",
            FT_DIR / name,
        )
    FT_PIN.write_text(sha, encoding="utf-8")
    return sha, names


def status():
    ft_pinned = FT_PIN.read_text(encoding="utf-8").strip()
    ft_latest = latest_path_commit(FT_API, "protos")
    vc_pinned = TESLA_COMMIT.read_text(encoding="utf-8").strip()
    vc_latest = latest_path_commit(VC_API, VC_PROTO_PATH)
    vc_upstream = latest_path_commit(VC_UPSTREAM_API, VC_PROTO_PATH)
    print(f"Fleet Telemetry: pinned `{ft_pinned}` · upstream latest `{ft_latest}`"
          f" ({'up to date' if ft_pinned == ft_latest else 'DRIFT'})")
    print(f"vehicle-command: pinned `{vc_pinned}` · fork latest touching {VC_PROTO_PATH} "
          f"`{vc_latest}` ({'up to date' if vc_pinned == vc_latest else 'DRIFT - bump the pin'})")
    print(f"  Tesla upstream latest touching {VC_PROTO_PATH}: `{vc_upstream}` "
          f"(rebase the fork when upstream moves)")
    return 0 if ft_pinned == ft_latest else 1


# ---------------------------------------------------------------------------
# Document generation
# ---------------------------------------------------------------------------


def field_ref(message, field):
    return f"{message}.{field}"


def ble_field_of(ble_messages, ref):
    message, _, field = ref.rpartition(".")
    for f in ble_messages.get(message, {}).get("fields", []):
        if f["name"] == field:
            return f
    return None


def ble_type_of(ble_messages, ref):
    field = ble_field_of(ble_messages, ref)
    return field["type"] if field else None


def normalize_type(type_name):
    """Normalize a cloud (docs) type name for comparison."""
    if not type_name:
        return "?"
    t = type_name.lower().strip()
    if "enum" in t:
        return "enum"
    if "timestamp" in t or t == "time":
        return "timestamp"
    if "location" in t:
        return "location"
    if "bool" in t:
        return "bool"
    if "float" in t or "double" in t or t == "real":
        return "real"
    if "string" in t:
        return "string"
    if "int" in t:
        return "int"
    return t


def ble_norm_type(type_name):
    """Normalize a BLE proto type name for comparison."""
    if not type_name:
        return "?"
    t = type_name.lower()
    if t in ("float", "double"):
        return "real"
    if t in ("int32", "uint32", "int64", "uint64", "sint32", "fixed32", "fixed64"):
        return "int"
    if t == "bool":
        return "bool"
    if t == "string":
        return "string"
    if t == "google.protobuf.timestamp":
        return "timestamp"
    if t == "latlong":
        return "location"
    return "enum"


def render(mapping, cloud_fields, ble_messages, docs):
    ft_pin = FT_PIN.read_text(encoding="utf-8").strip()
    row_by_cloud = {row["cloud"]: row for group in mapping["cross_map"] for row in group["rows"]}
    vc_pin = TESLA_COMMIT.read_text(encoding="utf-8").strip()
    cloud_names = {f["name"] for f in cloud_fields}

    errors = []
    reviewed_cloud = set()
    ble_refs = set()
    for group in mapping["cross_map"]:
        for row in group["rows"]:
            cloud = row["cloud"].split(".")[-1]
            if cloud not in cloud_names:
                errors.append(f"mapping: cloud signal `{cloud}` not in Fleet Telemetry Field enum")
            reviewed_cloud.add(cloud)
            if row.get("ble"):
                ref = row["ble"]
                message, _, field = ref.rpartition(".")
                if message not in ble_messages or field not in {f["name"] for f in ble_messages[message]["fields"]}:
                    errors.append(f"mapping: BLE ref `{ref}` not found in pinned protos")
                ble_refs.add(ref)
    if errors:
        for error in errors:
            print(f"ERROR {error}", file=sys.stderr)
        raise SystemExit(2)

    mapped_count = sum(1 for g in mapping["cross_map"] for r in g["rows"] if r.get("ble"))
    cloud_only_count = sum(1 for g in mapping["cross_map"] for r in g["rows"] if not r.get("ble"))

    ignore_prefixes = tuple(mapping.get("cloud_ignore_prefixes", []))
    ignore_comments = tuple(mapping.get("cloud_ignore_comment_contains", []))
    ignored = {
        f["name"]
        for f in cloud_fields
        if f["name"].startswith(ignore_prefixes)
        or any(c in f["comment"] for c in ignore_comments)
    }
    unmapped = [
        f for f in cloud_fields if f["name"] not in reviewed_cloud and f["name"] not in ignored
    ]

    catalog_messages = mapping["ble_catalog_messages"]
    ble_field_total = sum(
        len(ble_messages[m]["fields"]) for m in catalog_messages if m in ble_messages
    )
    ble_only = {}
    for message in catalog_messages:
        fields = ble_messages.get(message, {}).get("fields", [])
        rest = [f["name"] for f in fields if field_ref(message, f["name"]) not in ble_refs]
        if rest:
            ble_only[message] = rest

    documented = [f for f in cloud_fields if f["name"] in docs]
    undocumented = [f for f in cloud_fields if f["name"] not in docs and f["name"] not in ignored]

    type_diffs = []
    for row in (r for g in mapping["cross_map"] for r in g["rows"] if r.get("ble")):
        cloud = row["cloud"]
        info = docs.get(cloud)
        if not info:
            continue
        cloud_t = normalize_type(info["type"])
        ble_t = ble_norm_type(ble_type_of(ble_messages, row["ble"]))
        if cloud_t != "?" and cloud_t != ble_t:
            type_diffs.append((cloud, info["type"], row["ble"], ble_type_of(ble_messages, row["ble"])))

    out = []
    w = out.append
    w("# Fleet Telemetry vs BLE — signal matrix")
    w("")
    w("> Generated by `tools/signal-matrix/generate.py` from pinned protos. "
      "Do not edit by hand — update `tools/signal-matrix/mapping.json` and regenerate.")
    w(f"> Pins: Fleet Telemetry [`{ft_pin[:12]}`](https://github.com/teslamotors/fleet-telemetry/tree/{ft_pin}) · "
      f"vehicle-command BLE protos [`{vc_pin[:12]}`](https://github.com/{VC_REPO}/tree/{vc_pin})")
    w("")
    w("**Scope.** Cloud side: signals defined in Tesla's Fleet Telemetry protos "
      "(`teslamotors/fleet-telemetry/protos`, the data streamed to a telemetry server and the "
      "same vehicle data the Fleet API exposes as JSON). BLE side: messages in Tesla's "
      "vehicle-command protobufs as pinned in `protocol/src/main/proto`, which is what this app can "
      "read locally without any cloud connection.")
    w("")
    for note in mapping.get("summary_notes", []):
        w(note)
        w("")
    w("## Summary")
    w("")
    w("| Metric | Count |")
    w("|---|---|")
    w(f"| Fleet Telemetry signals (excluding deprecated/experimental) | "
      f"{len(cloud_fields) - len(ignored)} |")
    w(f"| Mapped to a BLE equivalent | {mapped_count} |")
    w(f"| Cloud-only (no BLE equivalent, reviewed) | {cloud_only_count} |")
    w(f"| Unreviewed upstream signals (drift watch) | {len(unmapped)} |")
    w(f"| Documented in Tesla's signals table | {len(documented)} |")
    w(f"| In protos but missing from Tesla's table | {len(undocumented)} |")
    w(f"| BLE fields catalogued | {ble_field_total} |")
    w("")

    w("## Fidelity")
    w("")
    for note in mapping.get("fidelity_notes", []):
        w(note)
        w("")
    if type_diffs:
        w("Where the same physical value is typed differently on each transport:")
        w("")
        w("| Cloud signal | Cloud type | BLE field | BLE type | Note |")
        w("|---|---|---|---|---|")
        for cloud, ctype, ble_ref, btype in type_diffs:
            note = row_by_cloud.get(cloud, {}).get("note", "")
            w(f"| `{cloud}` | {ctype} | `{ble_ref}` | {btype} | {note} |")
        w("")
    precision_rows, seen = [], set()
    for row in (r for g in mapping["cross_map"] for r in g["rows"] if r.get("ble")):
        field = ble_field_of(ble_messages, row["ble"])
        note = ble_precision_note(field["comment"]) if field else ""
        if note and row["ble"] not in seen:
            seen.add(row["ble"])
            precision_rows.append((row["ble"], field["type"], note))
    if precision_rows:
        w("Precision annotations Tesla documents in the BLE proto comments for mapped fields:")
        w("")
        w("| BLE field | BLE type | Annotation |")
        w("|---|---|---|")
        for ref, btype, note in precision_rows:
            w(f"| `{ref}` | {btype} | {note} |")
        w("")

    for group in mapping["cross_map"]:
        w(f"## {group['title']}")
        w("")
        if group.get("note"):
            w(group["note"])
            w("")
        w("| Cloud signal | Cloud type | Fleet API `vehicle_data` JSON | BLE equivalent | BLE type | Notes |")
        w("|---|---|---|---|---|---|")
        for row in group["rows"]:
            cloud = row["cloud"]
            info = docs.get(cloud, {})
            ctype = info.get("type", "—")
            json_eq = (info.get("json") or "—").replace("|", "/")
            if row.get("ble"):
                ble = f"`{row['ble']}`"
                field = ble_field_of(ble_messages, row["ble"])
                btype = field["type"] if field else "—"
                annotation = ble_precision_note(field["comment"]) if field else ""
                if annotation:
                    btype = f"{btype} ({annotation})"
            else:
                ble, btype = "—", "—"
            w(f"| `{cloud}` | {ctype} | {json_eq} | {ble} | {btype} | {row.get('note', '')} |")
        w("")

    w("## Unreviewed upstream signals (drift watch)")
    w("")
    if unmapped:
        w("These Fleet Telemetry signals are neither mapped nor explicitly ignored in "
          "`mapping.json`. If Tesla adds a battery-relevant signal it lands here first — "
          "review it and either map it, add it to a cloud-only group, or add it to the "
          "ignore list.")
        w("")
        w("| Signal | # | Upstream comment |")
        w("|---|---|---|")
        for f in sorted(unmapped, key=lambda x: x["number"]):
            w(f"| `{f['name']}` | {f['number']} | {f['comment']} |")
    else:
        w("None — every signal is either mapped or explicitly ignored.")
    w("")

    if undocumented:
        w("## In protos but missing from Tesla's docs table")
        w("")
        w("Signals that exist in the pinned Fleet Telemetry protos but not yet in "
          "developer.tesla.com's table (documentation lag). Types are unknown until "
          "Tesla documents them.")
        w("")
        w("| Signal | # | Upstream comment |")
        w("|---|---|---|")
        for f in sorted(undocumented, key=lambda x: x["number"]):
            w(f"| `{f['name']}` | {f['number']} | {f['comment']} |")
        w("")

    w("## BLE fields with no cloud mapping")
    w("")
    w(f"<details><summary>{sum(len(v) for v in ble_only.values())} BLE fields not referenced "
      "by the cross-map (usually internal, identity, or semi-specific fields)</summary>")
    w("")
    for message, fields in ble_only.items():
        w(f"**{message}** ({len(fields)}): " + ", ".join(f"`{f}`" for f in fields))
        w("")
    w("</details>")
    w("")

    w("## Commands")
    w("")
    w("| Action | Fleet API (signed command / HTTPS) | BLE | Notes |")
    w("|---|---|---|---|")
    for row in mapping["commands"]:
        w(f"| {row['action']} | {row['fleet']} | {row['ble']} | {row.get('note', '')} |")
    w("")

    w("## Full cloud catalog (Fleet Telemetry `Field` enum)")
    w("")
    w(f"<details><summary>{len(cloud_fields)} signals incl. deprecated/experimental</summary>")
    w("")
    w("| Signal | # | Upstream comment |")
    w("|---|---|---|")
    for f in sorted(cloud_fields, key=lambda x: x["number"]):
        w(f"| `{f['name']}` | {f['number']} | {f['comment']} |")
    w("</details>")
    w("")

    w("## Full BLE catalog (pinned state messages)")
    w("")
    for message in catalog_messages:
        if message not in ble_messages:
            continue
        fields = ble_messages[message]["fields"]
        w(f"<details><summary>{message} — {len(fields)} fields</summary>")
        w("")
        w("| Field | Type | Proto comment |")
        w("|---|---|---|")
        for f in fields:
            comment = f.get("comment", "").replace("|", "/")
            w(f"| `{f['name']}` | {f['type']} | {comment} |")
        w("</details>")
        w("")

    w("## How this stays current")
    w("")
    w("- A scheduled workflow (`.github/workflows/signal-matrix.yml`) refreshes the vendored "
      "Fleet Telemetry protos weekly, regenerates this document, and opens a PR when anything "
      "changes.")
    w("- New upstream signals appear under *Unreviewed upstream signals* until mapping.json is "
      "updated — that is the review queue.")
    w("- BLE protos are **not** auto-bumped: `protocol/src/main/proto` is pinned to `TESLA_COMMIT` "
      "and upgrades are deliberate, tested changes. The workflow reports when upstream moves "
      "ahead of the pin.")
    w("- Regenerate locally: `python tools/signal-matrix/generate.py`; "
      "verify in CI: `--check`.")
    w("")
    return "\n".join(out) + "\n"


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="fail if the committed doc is stale")
    parser.add_argument("--refresh", action="store_true", help="update vendored fleet protos from upstream")
    parser.add_argument("--status", action="store_true", help="print pinned, fork and upstream SHAs")
    parser.add_argument("--dump", action="store_true", help="dump parsed message fields")
    parser.add_argument("--import-docs", metavar="MARKDOWN",
                        help="import a rendered Tesla Available Data page (markdown)")
    args = parser.parse_args()

    if args.import_docs:
        count = import_docs(args.import_docs)
        print(f"imported {count} signals into {DOCS_TSV.relative_to(REPO)}")
        return 0
    if args.refresh:
        sha, names = refresh_fleet()
        print(f"refreshed fleet-telemetry protos to {sha} ({len(names)} files)")
    if args.status:
        return status()

    mapping = json.loads(MAPPING.read_text(encoding="utf-8"))
    cloud_fields = parse_field_enum(FT_DIR / "vehicle_data.proto")
    docs = load_docs()
    ble_files = [REPO / p for p in mapping["ble_files"]]
    ble_messages = parse_messages(ble_files)

    if args.dump:
        for message in mapping["ble_catalog_messages"]:
            fields = ble_messages.get(message, {}).get("fields", [])
            print(f"== {message} ({len(fields)})")
            for f in fields:
                print(f"  {f['name']} : {f['type']}")
        return 0

    doc = render(mapping, cloud_fields, ble_messages, docs)
    if args.check:
        current = DOC.read_text(encoding="utf-8") if DOC.exists() else ""
        if current == doc:
            print("signal matrix is up to date")
            return 0
        diff = list(difflib.unified_diff(current.splitlines(), doc.splitlines(), lineterm=""))
        print("\n".join(diff[:120]), file=sys.stderr)
        print(f"signal matrix is stale ({sum(1 for _ in diff)} diff lines); run generate.py", file=sys.stderr)
        return 1

    DOC.parent.mkdir(parents=True, exist_ok=True)
    DOC.write_text(doc, encoding="utf-8", newline="\n")
    print(f"wrote {DOC.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
