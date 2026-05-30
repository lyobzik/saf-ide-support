#!/usr/bin/env python3
"""Generate keywords.json for the SmartApp DSL IDEA plugin.

Parses smart_kit/resources/__init__.py (the authoritative registry of the
framework) via the `ast` module and collects every `type`-string keyword
registered inside the `init_*` methods. AST parsing (not regex) is used so that
`dict.update({...})`, single quotes, multiline literals and alias dicts are all
handled.

Usage:
    python tools/generate_keywords.py [path-to-resources__init__.py] [-o out.json]

If no path is given, the vendored copy under tools/vendor/ is used so the
result is reproducible offline.
"""
import argparse
import ast
import datetime
import hashlib
import json
import os
import sys

# Maps the registry dict variable name (module prefix stripped) -> plugin category.
DICT_TO_CATEGORY = {
    "actions": "action",
    "requirements": "requirement",
    "field_filler_description": "filler",
    "field_requirements": "field_requirement",
    "scenarios": "scenario",
    "form_descriptions": "form_description",
    "field_descriptions": "field_description",
    "classifiers": "classifier",
    "operators": "operator",
    "comparators": "comparator",
    "answer_items": "sdk_item",
}

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_SRC = os.path.join(HERE, "vendor", "resources__init__.py")
DEFAULT_OUT = os.path.join(
    HERE, "..", "src", "main", "resources", "keywords", "keywords.json"
)


def _subscript_base_name(target):
    """Return the dict variable name for a Subscript target, prefix stripped.

    Handles both `actions[...]` (ast.Name) and `ffd.field_filler_description[...]`
    (ast.Attribute).
    """
    value = target.value
    if isinstance(value, ast.Attribute):
        return value.attr
    if isinstance(value, ast.Name):
        return value.id
    return None


def _const_str(node):
    """Return the string value of a constant subscript key, else None.

    Tolerates the 3.8 ast.Index wrapper and ignores non-string keys such as
    `dict[None] = ...` (defaults) or `dict[SomeClass] = ...`.
    """
    if isinstance(node, ast.Index):  # py3.8
        node = node.value
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        return node.value
    return None


def _collect_from_dict_literal(dict_node):
    """Yield string keys from a `{...}` literal (used by dict.update(...))."""
    if not isinstance(dict_node, ast.Dict):
        return
    for key in dict_node.keys:
        if isinstance(key, ast.Constant) and isinstance(key.value, str):
            yield key.value


def collect_keywords(source):
    tree = ast.parse(source)
    result = {cat: set() for cat in DICT_TO_CATEGORY.values()}

    for node in ast.walk(tree):
        if not (isinstance(node, ast.FunctionDef) and node.name.startswith("init_")):
            continue
        for stmt in ast.walk(node):
            # Pattern A: dict["kw"] = Class
            if isinstance(stmt, ast.Assign):
                for tgt in stmt.targets:
                    if not isinstance(tgt, ast.Subscript):
                        continue
                    base = _subscript_base_name(tgt)
                    cat = DICT_TO_CATEGORY.get(base)
                    if cat is None:
                        continue
                    kw = _const_str(tgt.slice)
                    if kw is not None:
                        result[cat].add(kw)
            # Pattern B: dict.update({...})
            elif isinstance(stmt, ast.Call):
                func = stmt.func
                if isinstance(func, ast.Attribute) and func.attr == "update":
                    base = _subscript_base_name_from_attr(func.value)
                    cat = DICT_TO_CATEGORY.get(base)
                    if cat is None:
                        continue
                    for arg in stmt.args:
                        for kw in _collect_from_dict_literal(arg):
                            result[cat].add(kw)
    return result


def _subscript_base_name_from_attr(value):
    if isinstance(value, ast.Attribute):
        return value.attr
    if isinstance(value, ast.Name):
        return value.id
    return None


def _reproducible_timestamp():
    """ISO-время из SOURCE_DATE_EPOCH или None (для воспроизводимого вывода)."""
    epoch = os.environ.get("SOURCE_DATE_EPOCH")
    if not epoch:
        return None
    ts = datetime.datetime.fromtimestamp(int(epoch), datetime.timezone.utc)
    return ts.replace(microsecond=0).isoformat()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", nargs="?", default=DEFAULT_SRC,
                        help="path to smart_kit/resources/__init__.py")
    parser.add_argument("-o", "--out", default=DEFAULT_OUT,
                        help="output keywords.json path")
    args = parser.parse_args(argv)

    with open(args.source, "rb") as fh:
        raw = fh.read()
    source = raw.decode("utf-8")
    keywords = collect_keywords(source)

    categories = {cat: sorted(vals) for cat, vals in keywords.items() if vals}
    counts = {cat: len(vals) for cat, vals in categories.items()}
    total_unique = len(set().union(*categories.values())) if categories else 0

    meta = {
        "source_file": "smart_kit/resources/__init__.py",
        "source_sha256": hashlib.sha256(raw).hexdigest(),
        "counts": counts,
        "total_unique_keywords": total_unique,
    }
    # Воспроизводимость: timestamp пишется только при заданном SOURCE_DATE_EPOCH,
    # иначе одинаковый вход всегда даёт байт-в-байт одинаковый файл.
    generated_at = _reproducible_timestamp()
    if generated_at is not None:
        meta["generated_at"] = generated_at

    payload = {
        "_meta": meta,
        "categories": categories,
    }

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=2)
        fh.write("\n")

    print("wrote", args.out)
    for cat in sorted(counts):
        print("  {:18s} {}".format(cat, counts[cat]))
    print("  total unique:", total_unique)
    if total_unique == 0:
        print("WARNING: no keywords found — check the source/AST shape", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
