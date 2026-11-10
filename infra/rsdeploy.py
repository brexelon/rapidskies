#!/usr/bin/env python3
"""rsdeploy: apply RapidSkies config profiles and plugin jars to Pterodactyl servers.

Configs are kept as *patches*, not whole files. Each server generates its own
default configs on first start; rsdeploy reads the live file, sets only the keys
the profiles name, and writes it back. That keeps the repo small, survives
upstream config-format changes, and never commits secrets.

Profile layout (infra/profiles/<profile>/...), paths mirror the server root:
  <file>.patch   YAML mapping of dotted.key.path -> value, applied to <file>.
                 Supported targets: .properties, .yml/.yaml, .toml
                 A mapping value replaces the whole section (no deep merge).
                 Live lines that conflict with the patch are replaced: duplicate keys
                 collapse to one (the patched value, or else the one the server reads),
                 and a scalar or list standing where a patched section goes is replaced.
                 Later profiles override earlier ones key by key, and a section set by a
                 later profile drops an earlier profile's keys inside it.
  <file>         Any other file is written verbatim.
rapid-* plugin configs also get the keys their bundled config.yml (in build/dist/*.jar) has
and the live file lacks, so a server that first ran an older version gets new settings and
sections (see fill_defaults).
Both may contain ${NAME} placeholders, resolved from infra/secrets.env, the
server's `vars` in network.yml, SERVER_NAME, and ${addr:<server>}.

Usage:
  python infra/rsdeploy.py check                   # offline validation (CI)
  python infra/rsdeploy.py servers                # show servers found in the panel
  python infra/rsdeploy.py diff  [server ...]      # show what `apply` would change
  python infra/rsdeploy.py apply [server ...] [--restart]
  python infra/rsdeploy.py plugins [server ...] [--dry-run] [--restart]
  python infra/rsdeploy.py schedules [server ...] [--dry-run]   # backup schedules
  python infra/rsdeploy.py worlds [server ...] [--replace] [--dry-run]  # pre-built worlds
  add --local DIR to work on DIR/<server>/ folders instead of the panel API.
"""
from __future__ import annotations

import argparse
import copy
import difflib
import fnmatch
import hashlib
import io
import json
import os
import re
import shutil
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

from collections.abc import Mapping

try:
    import tomlkit
    from ruamel.yaml import YAML
    from ruamel.yaml.comments import CommentedMap, CommentedSeq
    from ruamel.yaml.constructor import RoundTripConstructor
    from ruamel.yaml.error import CommentMark, YAMLError
    from ruamel.yaml.tokens import CommentToken
    from tomlkit.exceptions import TOMLKitError
except ImportError:  # pragma: no cover
    sys.exit("Missing dependencies: pip install -r infra/requirements.txt")

INFRA = Path(__file__).resolve().parent
REPO = INFRA.parent
PROFILES = INFRA / "profiles"
LOCAL_PLUGINS = REPO / "plugins"
# Custom rapid-* jars, built by `./gradlew dist`. Plugin set entries starting with "dist/"
# are matched here instead of in plugins/.
DIST = REPO / "build" / "dist"
DIST_PREFIX = "dist/"
PLACEHOLDER = re.compile(r"\$\{([A-Za-z0-9_:.\-]+)\}")
# Pre-built worlds: only their chunk storage is shipped (see `world_dimension_dir`).
WORLD_FOLDER = re.compile(r"[a-z0-9_.\-]+(/dimensions/[a-z0-9_.\-]+/[a-z0-9_.\-/]+)?")
WORLD_BACKUPS = "world-backups"
# Per-dimension chunk storage: blocks, entities (item frames, armor stands...), points of interest.
WORLD_STORAGE = ("region", "entities", "poi")
PTERO_ID = re.compile(r"[0-9a-f]{8}(-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})?")
INTERNAL_PREFIXES = ("172.", "10.", "192.168.", "127.")


class DeployError(Exception):
    pass


# --------------------------------------------------------------------------- config

def load_env(path: Path) -> dict[str, str]:
    env: dict[str, str] = {}
    if not path.exists():
        return env
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        env[key.strip()] = value
    return env


def yaml_rt() -> YAML:
    y = YAML()
    y.preserve_quotes = True
    y.width = 4096
    return y


def ptero_id(srv: dict) -> str:
    return str(srv.get("ptero_id") or "").strip().lower()


def unset_servers(inv: dict) -> set[str]:
    """Servers with no ptero_id yet. Every command skips them."""
    return {name for name, srv in inv["servers"].items() if not ptero_id(srv)}


def load_inventory(path: Path) -> dict:
    data = YAML(typ="safe").load(path.read_text(encoding="utf-8"))
    for name, srv in data["servers"].items():
        srv.setdefault("profiles", [])
        srv.setdefault("plugins", [])
        srv.setdefault("vars", {})
        srv.setdefault("schedules", [])
        srv["worlds"] = srv.get("worlds") or {}
        for folder, world in srv["worlds"].items():
            if world not in (data.get("worlds") or {}):
                raise DeployError(f"{name}: world '{world}' (for {folder}) not defined under `worlds`")
        for s in srv["schedules"]:
            if s not in data.get("schedules", {}):
                raise DeployError(f"{name}: schedule '{s}' not defined")
        for p in srv["profiles"]:
            if not (PROFILES / p).is_dir():
                raise DeployError(f"{name}: profile '{p}' not found in {PROFILES}")
        for s in srv["plugins"]:
            if s not in data.get("plugin_sets", {}):
                raise DeployError(f"{name}: plugin set '{s}' not defined")
    return data


@dataclass
class FilePlan:
    verbatim: bytes | None = None
    patch: dict = field(default_factory=dict)


def collect_profile_files(profiles: list[str]) -> dict[str, FilePlan]:
    """Merge every profile's files, in order, into one plan per target path."""
    plans: dict[str, FilePlan] = {}
    for profile in profiles:
        root = PROFILES / profile
        for f in sorted(root.rglob("*")):
            if not f.is_file():
                continue
            rel = f.relative_to(root).as_posix()
            if rel.endswith(".patch"):
                target = rel[: -len(".patch")]
                body = YAML(typ="safe").load(f.read_text(encoding="utf-8")) or {}
                if not isinstance(body, dict):
                    raise DeployError(f"{f}: patch must be a mapping")
                _merge_patch(plans.setdefault(target, FilePlan()).patch, body,
                             nested=not target.endswith(".properties"))
            else:
                plans.setdefault(rel, FilePlan()).verbatim = f.read_bytes()
    return plans


# --------------------------------------------------------------------------- substitution

class Resolver:
    def __init__(self, env: dict[str, str], server_vars: dict, addr_lookup, skip: set[str] = frozenset()):
        self.env = env
        self.vars = {k: str(v) for k, v in server_vars.items()}
        self.addr_lookup = addr_lookup
        self.skip = skip  # servers without a ptero_id

    def _skipped_addr(self, v) -> bool:
        return isinstance(v, str) and any(
            ref.startswith("addr:") and ref[5:] in self.skip for ref in PLACEHOLDER.findall(v))

    def lookup(self, name: str) -> str:
        if name.startswith("addr:"):
            return self.addr_lookup(name[5:])
        if name in self.vars:
            return self.vars[name]
        if name in self.env:
            return self.env[name]
        raise DeployError(f"unresolved placeholder ${{{name}}} (add it to secrets.env or the server's vars)")

    def text(self, s: str) -> str:
        return PLACEHOLDER.sub(lambda m: self.lookup(m.group(1)), s)

    def value(self, v):
        if isinstance(v, str):
            return self.text(v)
        if isinstance(v, list):
            return [self.value(x) for x in v]
        if isinstance(v, dict):
            # An entry whose value is ${addr:<skipped server>} is dropped, and so is that
            # entry's key from sibling lists (e.g. Velocity's `servers` table and `try` order).
            dropped = {k for k, x in v.items() if self._skipped_addr(x)}
            out = {k: self.value(x) for k, x in v.items() if k not in dropped}
            if dropped:
                for k, x in out.items():
                    if isinstance(x, list):
                        out[k] = [i for i in x if i not in dropped]
            return out
        return v


# --------------------------------------------------------------------------- patchers

def _same(a, b) -> bool:
    """Deep equality that also matches types, so `1` doesn't count as `true` or "1"."""
    if hasattr(a, "unwrap"):  # tomlkit items
        a = a.unwrap()
    if isinstance(a, Mapping) and isinstance(b, Mapping):
        return a.keys() == b.keys() and all(_same(a[k], b[k]) for k in a)
    if isinstance(a, list) and isinstance(b, list):
        return len(a) == len(b) and all(_same(x, y) for x, y in zip(a, b))
    kinds = (bool, int, float, str, type(None), Mapping, list)
    kind = lambda v: next((t for t in kinds if isinstance(v, t)), type(v))
    return kind(a) is kind(b) and a == b


def _merge_patch(into: dict, body: dict, nested: bool) -> None:
    """Merge a later profile's patch over an earlier one's.

    The later entry always wins: a key is moved to the end so it applies after whatever
    it refines, and when `nested`, earlier entries below it (`a.b` when the new key is `a`)
    are dropped, since the new value replaces that whole section anyway.
    """
    for key, value in body.items():
        key = str(key)
        for old in [k for k in into if k == key or (nested and k.startswith(key + "."))]:
            del into[old]
        into[key] = value


# Java .properties syntax (what Paper and Minecraft read): `key=value`, `key: value` or
# `key value`; `#`/`!` comments; a trailing odd backslash continues the line; backslash escapes.
_PROP_ESCAPES = {"t": "\t", "n": "\n", "r": "\r", "f": "\f"}


def _prop_logical_lines(lines: list[str]) -> list[tuple[int, int]]:
    """[start, end) physical line ranges of each logical line."""
    out, i = [], 0
    while i < len(lines):
        start = i
        if not lines[i].lstrip().startswith(("#", "!")):
            while i < len(lines) - 1 and (len(lines[i]) - len(lines[i].rstrip("\\"))) % 2 == 1:
                i += 1
        i += 1
        out.append((start, i))
    return out


def _prop_unescape(s: str) -> str:
    out, i = [], 0
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s):
            n = s[i + 1]
            if n == "u" and re.fullmatch(r"[0-9a-fA-F]{4}", s[i + 2:i + 6]):
                out.append(chr(int(s[i + 2:i + 6], 16)))
                i += 6
                continue
            out.append(_PROP_ESCAPES.get(n, n))
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def _prop_parse(physical: list[str]) -> tuple[str, str] | None:
    """(key, value) of one logical line, or None for blanks and comments."""
    joined = physical[0]
    for nxt in physical[1:]:
        joined = joined[:-1] + nxt.lstrip()  # drop the continuation backslash
    s = joined.lstrip(" \t\f")
    if not s or s.startswith(("#", "!")):
        return None
    i = 0
    while i < len(s) and s[i] not in "=: \t\f":
        i += 2 if s[i] == "\\" else 1
    key, rest = s[:i], s[i:].lstrip(" \t\f")
    if rest[:1] in ("=", ":"):
        rest = rest[1:].lstrip(" \t\f")
    return _prop_unescape(key), _prop_unescape(rest)


def _prop_escape(s: str, is_key: bool) -> str:
    out = []
    for i, c in enumerate(s):
        if c == "\\":
            out.append("\\\\")
        elif c in "\t\n\r\f":
            out.append("\\" + {"\t": "t", "\n": "n", "\r": "r", "\f": "f"}[c])
        elif c in "=:#!" or (c == " " and (is_key or i == 0)):
            out.append("\\" + c)
        else:
            out.append(c)
    return "".join(out)


def _prop_line(key: str, value) -> str:
    return f"{_prop_escape(key, True)}={_prop_escape(_prop_str(value), False)}"


def patch_properties(text: str, patch: dict) -> str:
    """Set each patched key. Lines that already hold the patched value are left as they are.

    Duplicate keys are a conflict (Java keeps the last one, so an old duplicate further down
    would silently win): a patched key keeps its first line, with the new value, and loses the
    rest; an unpatched duplicate keeps only its last, effective, line.
    """
    lines = text.splitlines()
    patch = {str(k): v for k, v in patch.items()}
    entries = [(rng, _prop_parse(lines[rng[0]:rng[1]])) for rng in _prop_logical_lines(lines)]
    last = {kv[0]: n for n, (_, kv) in enumerate(entries) if kv}
    out, done = [], set()
    for n, ((start, end), kv) in enumerate(entries):
        if kv is None:
            out.extend(lines[start:end])
            continue
        key, value = kv
        if key in patch:
            if key in done:
                continue
            done.add(key)
            want = _prop_str(patch[key])
            out.extend(lines[start:end] if value == want else [_prop_line(key, patch[key])])
        elif last[key] == n:
            out.extend(lines[start:end])
    out.extend(_prop_line(k, v) for k, v in patch.items() if k not in done)
    return "\n".join(out) + "\n"


def _prop_str(v) -> str:
    if isinstance(v, bool):
        return "true" if v else "false"
    if v is None:
        return ""
    return str(v)


def _detect_yaml_style(text: str) -> tuple[int, bool]:
    """Return (mapping indent, whether list items are indented under their key)."""
    indent, nested_lists = None, None
    prev_key_indent = None
    for line in text.splitlines():
        stripped = line.lstrip(" ")
        if not stripped or stripped.startswith("#"):
            continue
        depth = len(line) - len(stripped)
        if stripped.startswith("- "):
            if nested_lists is None and prev_key_indent is not None:
                nested_lists = depth > prev_key_indent
        elif indent is None and depth:
            indent = depth
        prev_key_indent = depth if stripped.rstrip().endswith(":") else None
    return indent or 2, bool(nested_lists)


class _LastKeyWins(RoundTripConstructor):
    """Resolve duplicate YAML keys the way SnakeYAML (Bukkit, Velocity plugins) does: the
    last one wins. Only that one is written back, so the conflict is gone after a patch."""

    def check_mapping_key(self, node, key_node, mapping, key, value) -> bool:
        if key in mapping:
            del mapping[key]
        return True


def _yaml_for(text: str) -> YAML:
    """A round-trip YAML that writes `text` back in its own indentation style."""
    y = yaml_rt()
    y.Constructor = _LastKeyWins
    indent, nested_lists = _detect_yaml_style(text)
    if nested_lists:
        y.indent(mapping=indent, sequence=indent + 2, offset=indent)
    else:
        y.indent(mapping=indent, sequence=indent, offset=0)
    return y


def patch_yaml(text: str, patch: dict) -> str:
    y = _yaml_for(text)
    doc = y.load(text) if text.strip() else None
    if not isinstance(doc, dict):
        doc = CommentedMap()
    for path, value in patch.items():
        keys = str(path).split(".")
        node, i = doc, 0
        while i < len(keys) - 1 and isinstance(node.get(keys[i]), dict):
            node, i = node[keys[i]], i + 1
        if i < len(keys) - 1:
            # A missing section, or a scalar or list where one goes: set it as a whole.
            for k in reversed(keys[i + 1:]):
                value = {k: value}
            _set_key(node, keys[i], value)
            continue
        # Re-assigning an equal value would drop comments ruamel attached to it.
        if keys[-1] in node and _same(node[keys[-1]], value):
            continue
        _set_key(node, keys[-1], value)
    out = io.StringIO()
    y.dump(doc, out)
    return out.getvalue()


def _round_trip(value):
    """Plain dicts and lists as ruamel's commented types, so comments can be attached to them."""
    if isinstance(value, Mapping):
        return CommentedMap((k, _round_trip(v)) for k, v in value.items())
    if isinstance(value, list):
        return CommentedSeq(_round_trip(v) for v in value)
    return value


def _set_key(node: CommentedMap, key, value) -> None:
    """Sets node[key], keeping the comment lines that follow it (ruamel keeps the next key's
    header there) below the new value. A new key goes last and takes over the lines that
    followed the previous last key, so they stay above the next section."""
    value = _round_trip(value)
    if not len(node):
        node[key] = value
        return
    moved = _take_after(node, key if key in node else next(reversed(node)))
    node[key] = value
    if moved:
        _put_after(node, key, moved)


def patch_toml(text: str, patch: dict) -> str:
    doc = tomlkit.parse(text)
    for path, value in patch.items():
        keys = str(path).split(".")
        node = doc
        for k in keys[:-1]:
            # A scalar, array or array of tables is in the way of the section: replace it.
            if not isinstance(node.get(k), Mapping):
                if k in node:
                    del node[k]
                node[k] = tomlkit.table()
            node = node[k]
        last = keys[-1]
        # Re-assigning an equal value would drop its trailing comment.
        if last in node and _same(node[last], value):
            continue
        if isinstance(value, dict):
            item = tomlkit.table()
            for k, v in value.items():
                item[k] = v
        else:
            item = value
        if last in node and isinstance(node[last], Mapping) != isinstance(value, dict):
            del node[last]  # a table can't be overwritten in place by a value, or vice versa
        node[last] = item
    return tomlkit.dumps(doc)


# --------------------------------------------------------------------------- default config keys
#
# A plugin writes its bundled config.yml only when there's none, so a server keeps the config
# of the version it first ran: settings and sections a later version added are missing from
# the file (rapid-* plugins fill them in memory, ConfigView.withDefaults, but the file is what
# admins read and edit). fill_defaults adds them from the bundled config, with its comments.

def _is_collection(section: Mapping) -> bool:
    """A default section whose values are all sections: entries keyed by id (NPCs, Chapters,
    worlds). Only added when missing entirely, never filled entry by entry, so deleted entries
    stay deleted. Same rule as ConfigView.withDefaults in rapid-common."""
    return all(isinstance(v, Mapping) for v in section.values())


def _block(node) -> bool:
    """A non-empty block-style section or list (its last line is one of its children's)."""
    return isinstance(node, (CommentedMap, CommentedSeq)) and len(node) > 0 and not node.fa.flow_style()


def _end_slot(parent, key) -> tuple[list, int]:
    """(ca entry, index) of the comment emitted after the last line of parent[key]. ruamel
    keeps the comment lines that follow a value there, so they're the next key's header."""
    while _block(parent[key]):
        parent = parent[key]
        key = next(reversed(parent)) if isinstance(parent, CommentedMap) else len(parent) - 1
    entry = parent.ca.items.setdefault(key, [None, None, None, None] if isinstance(parent, CommentedMap)
                                       else [None, None])
    return entry, 2 if isinstance(parent, CommentedMap) else 0


def _take_after(parent, key) -> str | None:
    """Removes and returns the comment lines after parent[key], keeping its end-of-line comment.
    None if they're not a plain comment token (left alone then)."""
    entry, i = _end_slot(parent, key)
    tok = entry[i]
    if tok is None:
        return ""
    if not isinstance(tok, CommentToken):
        return None
    first, _, rest = tok.value.partition("\n")
    if first.strip():
        tok.value = first + "\n"
    else:
        entry[i] = None
        rest = tok.value[1:] if tok.value.startswith("\n") else tok.value
    return rest


def _put_after(parent, key, lines: str) -> None:
    """Emits `lines` (whole comment or blank lines) right after parent[key]."""
    if not lines:
        return
    entry, i = _end_slot(parent, key)
    tok = entry[i]
    if tok is None:
        entry[i] = CommentToken("\n" + lines, CommentMark(0))
    elif isinstance(tok, CommentToken):
        tok.value = (tok.value if tok.value.endswith("\n") else tok.value + "\n") + lines


def _header(default_lines: list[str], line: int, col: int) -> str:
    """The comment block right above a key in the default file (with one blank line before it,
    if there is one), as text."""
    out, i = [], line - 1
    while i >= 0:
        s = default_lines[i]
        stripped = s.lstrip(" ")
        if not stripped.startswith("#") or len(s) - len(stripped) > col:
            break
        out.append(s)
        i -= 1
    if out and i >= 0 and not default_lines[i].strip():
        out.append("")
    return "".join(s + "\n" for s in reversed(out))


def _fill(live: CommentedMap, defaults: CommentedMap, default_lines: list[str], path: str,
          added: list[str]) -> None:
    default_keys = list(defaults)
    for n, key in enumerate(default_keys):
        if key in live:
            if isinstance(live[key], CommentedMap) and isinstance(defaults[key], CommentedMap) \
                    and not _is_collection(defaults[key]):
                _fill(live[key], defaults[key], default_lines, f"{path}{key}.", added)
            continue
        before = next((k for k in reversed(default_keys[:n]) if k in live), None)
        pos = list(live).index(before) + 1 if before is not None else 0
        value = copy.deepcopy(defaults[key])
        live.insert(pos, key, value)
        entry = copy.deepcopy(defaults.ca.items.get(key)) or [None, None, None, None]
        entry[1] = None  # its header comes from the default text below, wherever ruamel put it
        live.ca.items[key] = entry
        # The copy ends with the default file's next header, which belongs to another key.
        _take_after(live, key)
        if before is not None:
            # Whatever followed the previous key (the next key's header) now follows this one.
            moved = _take_after(live, before)
            if moved is not None:
                header = _header(default_lines, *defaults.lc.key(key)) if n > 0 else ""
                _put_after(live, before, header)
                _put_after(live, key, moved)
        added.append(path + str(key))


def fill_defaults(text: str, default_text: str) -> tuple[str, list[str]]:
    """`text` with the keys it lacks added from `default_text` (a plugin's bundled config),
    and the dotted paths that were added. Keys that are present, even empty, are kept."""
    live_yaml = _yaml_for(text)
    live = live_yaml.load(text) if text.strip() else None
    defaults = yaml_rt().load(default_text)
    if not isinstance(defaults, CommentedMap):
        return text, []
    if not isinstance(live, CommentedMap):
        return default_text, list(map(str, defaults))
    added: list[str] = []
    _fill(live, defaults, default_text.splitlines(), "", added)
    if not added:
        return text, []
    out = io.StringIO()
    live_yaml.dump(live, out)
    return out.getvalue(), added


def plugin_config_defaults(srv: dict, inv: dict) -> tuple[dict[str, tuple[str, str]], list[str]]:
    """{"plugins/<folder>/config.yml": (bundled config text, jar name)} for the server's rapid-*
    plugins (the dist/ entries of its plugin sets), and notes about jars that couldn't be read.
    Third-party plugins keep their own configs up to date."""
    out: dict[str, tuple[str, str]] = {}
    notes: list[str] = []
    for g in [g for s in srv["plugins"] for g in (inv["plugin_sets"][s] or [])]:
        if not g.startswith(DIST_PREFIX):
            continue
        jars = sorted(DIST.glob(g[len(DIST_PREFIX):]))
        if len(jars) != 1:
            notes.append(f"{g} isn't built (./gradlew dist), so its config isn't checked for missing keys")
            continue
        with zipfile.ZipFile(jars[0]) as z:
            names = set(z.namelist())
            if "config.yml" not in names:
                continue
            if "plugin.yml" in names:  # Paper: plugins/<name>/
                folder = YAML(typ="safe").load(z.read("plugin.yml").decode("utf-8"))["name"]
            elif "velocity-plugin.json" in names:  # Velocity: plugins/<id>/
                folder = json.loads(z.read("velocity-plugin.json"))["id"]
            else:
                continue
            out[f"plugins/{folder}/config.yml"] = (z.read("config.yml").decode("utf-8"), jars[0].name)
    return out, notes


def apply_patch(target: str, text: str, patch: dict) -> str:
    try:
        if target.endswith(".properties"):
            return patch_properties(text, patch)
        if target.endswith((".yml", ".yaml")):
            return patch_yaml(text, patch)
        if target.endswith(".toml"):
            return patch_toml(text, patch)
    except (YAMLError, TOMLKitError) as e:
        raise DeployError(f"{target} on the server doesn't parse, fix or delete it: {e}") from e
    raise DeployError(f"don't know how to patch {target}")


# --------------------------------------------------------------------------- backends

class PanelBackend:
    """Pterodactyl client API (/api/client, key from Account -> API Credentials).

    The panel and the game node are separate machines. API calls go to the panel, which
    proxies file operations to Wings; jar uploads go directly to the node's Wings through
    a signed URL, so the machine running this needs network access to both.
    """

    def __init__(self, env: dict[str, str]):
        self.base = env.get("PTERO_URL", "").rstrip("/")
        key = env.get("PTERO_API_KEY", "")
        if not self.base or not key or "changeme" in key:
            raise DeployError("set PTERO_URL and PTERO_API_KEY in infra/secrets.env (or use --local)")
        self.headers = {"Authorization": f"Bearer {key}", "Accept": "application/json"}
        if env.get("CF_ACCESS_CLIENT_ID"):
            self.headers["CF-Access-Client-Id"] = env["CF_ACCESS_CLIENT_ID"]
            self.headers["CF-Access-Client-Secret"] = env.get("CF_ACCESS_CLIENT_SECRET", "")
        self._servers: dict[str, dict] | None = None

    def _req(self, method: str, url: str, body: bytes | None = None, ctype: str | None = None,
             raw: bool = False, headers: dict | None = None):
        h = dict(self.headers if headers is None else headers)
        if ctype:
            h["Content-Type"] = ctype
        req = urllib.request.Request(url, data=body, method=method, headers=h)
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                data = resp.read()
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "replace")[:300]
            raise DeployError(f"{method} {url.split('?')[0]} -> HTTP {e.code}: {detail}") from e
        if raw:
            return data
        return json.loads(data) if data else None

    def api(self, method: str, path: str, **kw):
        return self._req(method, f"{self.base}/api/client{path}", **kw)

    def servers(self) -> list[dict]:
        """Every server the API key can see: name, identifier, uuid, default allocation."""
        if self._servers is None:
            self._servers, page = [], 1
            while True:
                res = self.api("GET", f"?page={page}&per_page=100")
                for s in res["data"]:
                    a = s["attributes"]
                    allocs = [x["attributes"] for x in a["relationships"]["allocations"]["data"]]
                    default = next((x for x in allocs if x.get("is_default")), allocs[0] if allocs else None)
                    self._servers.append({"name": a["name"], "id": a["identifier"],
                                          "uuid": a["uuid"], "alloc": default})
                meta = res["meta"]["pagination"]
                if meta["current_page"] >= meta["total_pages"]:
                    break
                page += 1
        return self._servers

    def server(self, name: str, inv: dict) -> dict:
        pid = ptero_id(inv["servers"][name])
        if not pid:
            raise DeployError(f"{name}: ptero_id is empty in network.yml "
                              f"(run `rsdeploy.py servers` to list panel servers and their IDs)")
        match = next((s for s in self.servers() if pid in (s["id"], s["uuid"])), None)
        if not match:
            raise DeployError(f"{name}: no panel server with ptero_id '{pid}' visible to this API key "
                              f"(run `rsdeploy.py servers` to list them)")
        return match

    def address(self, name: str, inv: dict) -> str:
        srv = inv["servers"].get(name)
        if srv is None:
            raise DeployError(f"${{addr:{name}}}: no such server in network.yml")
        if srv.get("address"):
            return srv["address"]
        alloc = self.server(name, inv)["alloc"]
        if not alloc:
            raise DeployError(f"{name} has no allocation")
        ip = alloc["ip"]
        if not ip.startswith(INTERNAL_PREFIXES):
            raise DeployError(
                f"{name}'s allocation is {ip}:{alloc['port']}. Backends must bind to the internal "
                f"interface (172.18.0.1), not a public or wildcard address. Fix the allocation, "
                f"or set `address` for it in network.yml.")
        return f"{ip}:{alloc['port']}"

    def _absent(self, sid: str, path: str) -> bool:
        """True if `path` provably does not exist, checked by listing its parent directory."""
        parent, _, name = ("/" + path.strip("/")).rpartition("/")
        try:
            res = self.api("GET", f"/servers/{sid}/files/list?directory={urllib.parse.quote(parent or '/')}")
        except DeployError as e:
            if parent and ("HTTP 404" in str(e) or "HTTP 500" in str(e)):
                return self._absent(sid, parent)
            raise
        return all(x["attributes"]["name"] != name for x in res["data"])

    def _is_missing(self, sid: str, path: str, e: DeployError) -> bool:
        # Some Wings versions answer a missing file or directory with HTTP 500
        # (DaemonConnectionException) instead of 404, so a 500 is only treated as
        # "missing" once the parent listing confirms the entry isn't there.
        if "HTTP 404" in str(e):
            return True
        return "HTTP 500" in str(e) and self._absent(sid, path)

    def read(self, sid: str, path: str) -> bytes | None:
        try:
            data = self.api("GET", f"/servers/{sid}/files/contents?file={urllib.parse.quote('/' + path)}", raw=True)
        except DeployError as e:
            if self._is_missing(sid, path, e):
                return None
            raise
        return data

    def write(self, sid: str, path: str, data: bytes) -> None:
        self.api("POST", f"/servers/{sid}/files/write?file={urllib.parse.quote('/' + path)}",
                 body=data, ctype="application/octet-stream", raw=True)

    def list_dir(self, sid: str, directory: str) -> dict[str, int]:
        try:
            res = self.api("GET", f"/servers/{sid}/files/list?directory={urllib.parse.quote(directory)}")
        except DeployError as e:
            if self._is_missing(sid, directory, e):
                return {}
            raise
        return {x["attributes"]["name"]: x["attributes"]["size"] for x in res["data"] if x["attributes"]["is_file"]}

    def upload(self, sid: str, directory: str, local: Path, ctype: str = "application/java-archive") -> None:
        url = self.api("GET", f"/servers/{sid}/files/upload")["attributes"]["url"]
        boundary = uuid.uuid4().hex
        body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"files\"; filename=\"{local.name}\"\r\n"
                f"Content-Type: {ctype}\r\n\r\n").encode() + local.read_bytes() + \
            f"\r\n--{boundary}--\r\n".encode()
        sep = "&" if "?" in url else "?"
        self._req("POST", f"{url}{sep}directory={urllib.parse.quote(directory)}", body=body,
                  ctype=f"multipart/form-data; boundary={boundary}", raw=True, headers={})

    def sha256(self, sid: str, path: str) -> str:
        """Hash of a server file, streamed from Wings through a signed download URL."""
        url = self.api("GET", f"/servers/{sid}/files/download?file={urllib.parse.quote('/' + path.strip('/'))}")
        req = urllib.request.Request(url["attributes"]["url"], method="GET")
        digest = hashlib.sha256()
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                while chunk := resp.read(1 << 20):
                    digest.update(chunk)
        except urllib.error.HTTPError as e:
            raise DeployError(f"download of {path} from Wings -> HTTP {e.code}") from e
        return digest.hexdigest()

    def rename(self, sid: str, directory: str, src: str, dst: str) -> None:
        # Wings refuses to rename onto an existing file, so `dst` must not exist.
        self.api("PUT", f"/servers/{sid}/files/rename",
                 body=json.dumps({"root": directory, "files": [{"from": src, "to": dst}]}).encode(),
                 ctype="application/json", raw=True)

    def delete(self, sid: str, directory: str, names: list[str]) -> None:
        self.api("POST", f"/servers/{sid}/files/delete", body=json.dumps({"root": directory, "files": names}).encode(),
                 ctype="application/json", raw=True)

    def restart(self, sid: str) -> None:
        self.api("POST", f"/servers/{sid}/power", body=b'{"signal":"restart"}', ctype="application/json", raw=True)

    def power_state(self, sid: str) -> str:
        """offline, starting, running or stopping."""
        return self.api("GET", f"/servers/{sid}/resources")["attributes"]["current_state"]

    def exists(self, sid: str, path: str) -> bool:
        return not path.strip("/") or not self._absent(sid, path)

    def move(self, sid: str, src: str, dst: str) -> None:
        """Moves a file or folder elsewhere under the server root, creating dst's parent."""
        parent = dst.strip("/").rpartition("/")[0]
        if parent and not self.exists(sid, parent):
            up, _, leaf = parent.rpartition("/")
            self.api("POST", f"/servers/{sid}/files/create-folder",
                     body=json.dumps({"root": "/" + up, "name": leaf}).encode(), ctype="application/json", raw=True)
        self.rename(sid, "/", src.strip("/"), dst.strip("/"))

    def put_files(self, sid: str, files: dict[str, Path], label: str) -> None:
        """Uploads many files at once: one .tar.gz through Wings, unpacked in place."""
        name = f"rsdeploy-{label}.tar.gz"
        with tempfile.TemporaryDirectory() as tmp:
            archive = Path(tmp) / name
            with tarfile.open(archive, "w:gz") as tar:
                for remote, local in files.items():
                    tar.add(local, arcname=remote.strip("/"))
            self.upload(sid, "/", archive, ctype="application/gzip")
        try:
            self.api("POST", f"/servers/{sid}/files/decompress",
                     body=json.dumps({"root": "/", "file": name}).encode(), ctype="application/json", raw=True)
        finally:
            self.delete(sid, "/", [name])

    def ensure_schedule(self, sid: str, name: str, cron: str, action: str, dry_run: bool) -> None:
        minute, hour, dom, month, dow = cron.split()
        body = {"name": name, "minute": minute, "hour": hour, "day_of_month": dom, "month": month,
                "day_of_week": dow, "is_active": True, "only_when_online": False}
        existing = next((s["attributes"] for s in self.api("GET", f"/servers/{sid}/schedules")["data"]
                         if s["attributes"]["name"] == name), None)
        if existing is None:
            print(f"  + schedule {name} ({cron}, {action})")
            if dry_run:
                return
            existing = self.api("POST", f"/servers/{sid}/schedules", body=json.dumps(body).encode(),
                                ctype="application/json")["attributes"]
        else:
            c = existing["cron"]
            if (c["minute"], c["hour"], c["day_of_month"], c["month"], c["day_of_week"]) != (minute, hour, dom, month, dow) \
                    or not existing["is_active"]:
                print(f"  ~ schedule {name} -> {cron}")
                if not dry_run:
                    self.api("POST", f"/servers/{sid}/schedules/{existing['id']}", body=json.dumps(body).encode(),
                             ctype="application/json")
        tasks = existing.get("relationships", {}).get("tasks", {}).get("data", [])
        if not any(t["attributes"]["action"] == action for t in tasks):
            print(f"  + task {action} in {name}")
            if not dry_run:
                task = {"action": action, "payload": "", "time_offset": 0, "continue_on_failure": False}
                self.api("POST", f"/servers/{sid}/schedules/{existing['id']}/tasks",
                         body=json.dumps(task).encode(), ctype="application/json")


class LocalBackend:
    """Treats <root>/<server>/ as each server's root folder (for SFTP mirrors and testing)."""

    def __init__(self, root: Path):
        self.root = root

    def server(self, name: str, inv: dict) -> dict:
        return {"id": name, "alloc": None}

    def address(self, name: str, inv: dict) -> str:
        srv = inv["servers"].get(name)
        if srv is None:
            raise DeployError(f"${{addr:{name}}}: no such server in network.yml")
        if not srv.get("address"):
            raise DeployError(f"${{addr:{name}}}: --local mode needs `address` set for {name} in network.yml")
        return srv["address"]

    def read(self, sid: str, path: str) -> bytes | None:
        p = self.root / sid / path
        return p.read_bytes() if p.exists() else None

    def write(self, sid: str, path: str, data: bytes) -> None:
        p = self.root / sid / path
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(data)

    def list_dir(self, sid: str, directory: str) -> dict[str, int]:
        d = self.root / sid / directory.strip("/")
        return {f.name: f.stat().st_size for f in d.iterdir() if f.is_file()} if d.is_dir() else {}

    def upload(self, sid: str, directory: str, local: Path) -> None:
        d = self.root / sid / directory.strip("/")
        d.mkdir(parents=True, exist_ok=True)
        shutil.copy2(local, d / local.name)

    def sha256(self, sid: str, path: str) -> str:
        return file_sha256(self.root / sid / path.strip("/"))

    def rename(self, sid: str, directory: str, src: str, dst: str) -> None:
        d = self.root / sid / directory.strip("/")
        (d / src).rename(d / dst)

    def delete(self, sid: str, directory: str, names: list[str]) -> None:
        for n in names:
            (self.root / sid / directory.strip("/") / n).unlink(missing_ok=True)

    def restart(self, sid: str) -> None:
        print(f"  (local mode: restart {sid} yourself)")

    def power_state(self, sid: str) -> None:
        return None  # unknown in local mode: make sure the server is stopped yourself

    def exists(self, sid: str, path: str) -> bool:
        return (self.root / sid / path.strip("/")).exists()

    def move(self, sid: str, src: str, dst: str) -> None:
        target = self.root / sid / dst.strip("/")
        target.parent.mkdir(parents=True, exist_ok=True)
        (self.root / sid / src.strip("/")).rename(target)

    def put_files(self, sid: str, files: dict[str, Path], label: str) -> None:
        for remote, local in files.items():
            p = self.root / sid / remote.strip("/")
            p.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(local, p)

    def ensure_schedule(self, sid: str, name: str, cron: str, action: str, dry_run: bool) -> None:
        print(f"  (local mode: would ensure schedule {name} = {cron}, {action})")


# --------------------------------------------------------------------------- commands

def mask(text: str, env: dict[str, str]) -> str:
    for k, v in env.items():
        if v and len(v) >= 6 and any(s in k for s in ("SECRET", "PASSWORD", "KEY")):
            text = text.replace(v, "********")
    return text


def check_secrets(env: dict[str, str]) -> None:
    # Panel credentials are checked by PanelBackend, and aren't needed with --local.
    bad = [k for k, v in env.items() if "changeme" in v.lower() and not k.startswith(("PTERO_", "CF_"))]
    if bad:
        raise DeployError(f"secrets.env still has placeholder values: {', '.join(bad)}")


def _as_text(data: bytes) -> str | None:
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return None


def configure_server(name: str, inv: dict, env: dict, backend, write: bool) -> bool:
    srv = inv["servers"][name]
    sid = backend.server(name, inv)["id"]
    resolver = Resolver(env, {"SERVER_NAME": name, **srv["vars"]}, lambda n: backend.address(n, inv),
                        skip=unset_servers(inv))
    changed = False
    plans = collect_profile_files(srv["profiles"])
    defaults, notes = plugin_config_defaults(srv, inv)
    for note in notes:
        print(f"  ! {note}")
    for target in list(plans) + [t for t in defaults if t not in plans]:
        plan = plans.get(target) or FilePlan()
        current = backend.read(sid, target)
        if plan.verbatim is not None:
            base = plan.verbatim
            text = _as_text(base)
            if text is not None:
                base = resolver.text(text).encode("utf-8")
        elif current is None:
            # A plugin that hasn't run yet writes its whole bundled config on first start.
            if plan.patch:
                print(f"  ! {target}: not on the server yet. Start {name} once so it generates "
                      f"its defaults, then run again.")
            continue
        else:
            base = current
            if target in defaults:
                text = _as_text(base)
                if text is None:
                    raise DeployError(f"{target} is not UTF-8 text, can't add its missing keys")
                default_text, jar = defaults[target]
                try:
                    filled, added = fill_defaults(text, default_text)
                except YAMLError as e:
                    raise DeployError(f"{target} on the server doesn't parse, fix or delete it: {e}") from e
                if added:
                    print(f"  + {target}: missing from the server, added from {jar}'s defaults: "
                          f"{', '.join(added)}")
                    base = filled.encode("utf-8")
        if plan.patch:
            text = _as_text(base)
            if text is None:
                raise DeployError(f"{target} is not UTF-8 text, can't patch it")
            new = apply_patch(target, text, resolver.value(plan.patch)).encode("utf-8")
        else:
            new = base
        if new == current:
            continue
        changed = True
        print(f"  ~ {target}")
        old_text, new_text = _as_text(current or b""), _as_text(new)
        if old_text is None or new_text is None:
            print(f"    (binary, {len(new)} bytes)")
        else:
            diff = difflib.unified_diff(old_text.splitlines(), new_text.splitlines(),
                                        f"a/{target}", f"b/{target}", lineterm="", n=1)
            for line in diff:
                print("    " + mask(line, env))
        if write:
            backend.write(sid, target, new)
    if not changed:
        print("  (no changes)")
    return changed


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        while chunk := f.read(1 << 20):
            digest.update(chunk)
    return digest.hexdigest()


def world_dimension_dir(folder: str) -> str:
    """Where a world's chunk storage goes on the server, for a `worlds:` folder in network.yml.

    Paper 26.1+ keeps every world of a server inside the main save (the `level-name` folder):
    the main world in `<level>/dimensions/minecraft/overworld`, and a world a plugin loads by
    key (`rapidskies:spawn`) in `<level>/dimensions/rapidskies/spawn`, each with its own
    region/, entities/ and poi/. A plain folder name is a whole save, anything with
    /dimensions/ is one world inside one.
    """
    folder = folder.strip("/")
    if not WORLD_FOLDER.fullmatch(folder):
        raise DeployError(f"world folder '{folder}' must be a level folder (`hub`) or "
                          f"`<level>/dimensions/<namespace>/<key>`, lowercase")
    if "/" not in folder:
        return f"{folder}/dimensions/minecraft/overworld"
    return folder


def world_files(inv: dict, world: str) -> dict[str, Path]:
    """A pre-built world's chunk storage from the repo, as {"region/r.0.0.mca": path}.

    Entities have lived in their own entities/ files since 1.17; leaving them out would
    strip a build of its item frames, paintings and armor stands.
    """
    src = REPO / inv["worlds"][world]["source"]
    files = {f"{kind}/{f.name}": f for kind in WORLD_STORAGE for f in sorted((src / kind).glob("r.*.*.mca"))}
    if not any(k.startswith("region/") for k in files):
        raise DeployError(f"world {world}: no region files in {src / 'region'}")
    return files


def level_name(srv: dict) -> str | None:
    plan = collect_profile_files(srv["profiles"]).get("server.properties")
    return str(plan.patch["level-name"]) if plan and "level-name" in plan.patch else None


def sync_worlds(name: str, inv: dict, backend, replace: bool, dry_run: bool) -> bool:
    """Ships the server's pre-built worlds. Returns True if anything changed.

    Only chunk data is shipped, never a level.dat: the server writes fresh world data on its
    next start from server.properties (main world) or rapid-core's `worlds` config (worlds it
    loads by key), so the generator, spawn and game rules are always ours, not the
    download's. An existing world is never touched without --replace, and even then it's
    moved to world-backups/ rather than deleted.
    """
    srv = inv["servers"][name]
    if not srv["worlds"]:
        print("  (no pre-built worlds)")
        return False
    sid = backend.server(name, inv)["id"]
    state = None
    changed = False
    for folder, world in srv["worlds"].items():
        folder = folder.strip("/")
        dim = world_dimension_dir(folder)
        files = world_files(inv, world)
        size = sum(f.stat().st_size for f in files.values())
        if backend.exists(sid, folder):
            if not replace:
                print(f"  = {folder} is already there. Pass --replace to swap in '{world}' "
                      f"(the current one moves to {WORLD_BACKUPS}/).")
                continue
            if state is None:
                state = backend.power_state(sid)
            if state not in (None, "offline"):
                raise DeployError(f"{name} is {state}. Stop it before replacing {folder}: a running "
                                  f"server keeps writing the world it has loaded.")
            backup = f"{WORLD_BACKUPS}/{folder.replace('/', '__')}-{time.strftime('%Y%m%d-%H%M%S')}"
            print(f"  ~ {folder} -> {backup}")
            if not dry_run:
                backend.move(sid, folder, backup)
        kinds = ", ".join(f"{sum(k.startswith(kind + '/') for k in files)} {kind}"
                          for kind in WORLD_STORAGE if any(k.startswith(kind + "/") for k in files))
        print(f"  + {world} -> {dim}/ ({kinds} files, {size / 1e6:.1f} MB)")
        changed = True
        if dry_run:
            continue
        backend.put_files(sid, {f"{dim}/{rel}": f for rel, f in files.items()}, label=f"world-{world}")
        listings = {kind: backend.list_dir(sid, f"/{dim}/{kind}") for kind in WORLD_STORAGE}
        wrong = [rel for rel, f in files.items()
                 if listings[rel.split("/")[0]].get(f.name) != f.stat().st_size]
        if wrong:
            raise DeployError(f"{name}: {len(wrong)} file(s) didn't arrive intact in {dim}: "
                              f"{', '.join(wrong[:5])}")
    if changed and not dry_run:
        print("  Start the server to generate the rest of the world data (apply the profiles first).")
    return changed


def plugin_source(glob: str) -> tuple[Path, str]:
    """(local folder, file-name glob) for a plugin set entry."""
    if glob.startswith(DIST_PREFIX):
        return DIST, glob[len(DIST_PREFIX):]
    return LOCAL_PLUGINS, glob


def replace_jar(backend, sid: str, remote: dict[str, int], jar: Path) -> None:
    """Uploads `jar` to /plugins without writing into a jar a running server has open.

    The JVM keeps plugin jars open and loads classes from them lazily (some only on
    shutdown). Uploading over the live file rewrites it in place, so the next lazy load
    reads garbage offsets and throws NoClassDefFoundError. Instead the old jar is renamed
    aside (same inode, so the JVM keeps reading it), the new one is uploaded as a fresh
    file, and the old one is unlinked; its data stays readable until the server stops.
    """
    if jar.name not in remote:
        backend.upload(sid, "/plugins", jar)
        return
    old = f"{jar.name}.old"
    if old in remote:  # leftover from an interrupted deploy
        backend.delete(sid, "/plugins", [old])
    backend.rename(sid, "/plugins", jar.name, old)
    try:
        backend.upload(sid, "/plugins", jar)
    except Exception:
        backend.rename(sid, "/plugins", old, jar.name)
        raise
    backend.delete(sid, "/plugins", [old])


def sync_plugins(name: str, inv: dict, backend, dry_run: bool) -> bool:
    """Uploads the server's plugin jars. Returns True if anything changed."""
    srv = inv["servers"][name]
    sid = backend.server(name, inv)["id"]
    remote = backend.list_dir(sid, "/plugins")
    globs = [g for s in srv["plugins"] for g in (inv["plugin_sets"][s] or [])]
    changed = False
    for g in globs:
        folder, pattern = plugin_source(g)
        local = sorted(folder.glob(pattern))
        if len(local) != 1:
            hint = " (run ./gradlew dist first)" if folder == DIST else ""
            raise DeployError(f"plugin glob '{g}' matched {len(local)} jars in {folder} (need exactly 1){hint}")
        jar = local[0]
        stale = [r for r in remote if fnmatch.fnmatch(r, pattern) and r != jar.name]
        up_to_date = remote.get(jar.name) == jar.stat().st_size
        if up_to_date and folder == DIST:
            # rapid-* jars keep the same name across builds, and a rebuild can easily keep
            # the same size, so compare contents. Gradle builds them reproducibly, so an
            # unchanged build hashes the same and isn't re-uploaded.
            up_to_date = backend.sha256(sid, f"/plugins/{jar.name}") == file_sha256(jar)
        if up_to_date and not stale:
            continue
        changed = True
        if not up_to_date:
            print(f"  + {jar.name}")
            if not dry_run:
                replace_jar(backend, sid, remote, jar)
        if stale:
            print(f"  - {', '.join(stale)}")
            if not dry_run:
                backend.delete(sid, "/plugins", stale)
    if not changed:
        print("  (plugins up to date)")
    return changed


def check(inv: dict) -> int:
    """Offline validation for CI: profiles parse, placeholders are known, globs are sane."""
    known = set(load_env(INFRA / "secrets.env.example")) | {"SERVER_NAME"}
    problems = 0
    seen_ids: dict[str, str] = {}
    for name, srv in inv["servers"].items():
        pid = ptero_id(srv)
        if not pid:
            print(f"{name}: note: ptero_id is empty, so rsdeploy skips it")
        elif not PTERO_ID.fullmatch(pid):
            print(f"{name}: ptero_id '{pid}' is neither an 8-char identifier nor a UUID")
            problems += 1
        elif pid in seen_ids:
            print(f"{name}: ptero_id '{pid}' is also used by {seen_ids[pid]}")
            problems += 1
        else:
            seen_ids[pid] = name
        plans = collect_profile_files(srv["profiles"])  # raises on unparsable patches
        for target, plan in plans.items():
            blobs = [json.dumps(plan.patch, ensure_ascii=False)]
            if plan.verbatim is not None and _as_text(plan.verbatim) is not None:
                blobs.append(plan.verbatim.decode("utf-8"))
            for ref in PLACEHOLDER.findall("\n".join(blobs)):
                if ref.startswith("addr:"):
                    ok = ref[5:] in inv["servers"]
                else:
                    ok = ref in known or ref in srv["vars"]
                if not ok:
                    print(f"{name}: {target}: unknown placeholder ${{{ref}}}")
                    problems += 1
            if plan.patch and not target.endswith((".properties", ".yml", ".yaml", ".toml")):
                print(f"{name}: {target}: can't patch this file type")
                problems += 1
        level = level_name(srv)
        for folder, world in srv["worlds"].items():
            try:
                world_dimension_dir(folder)
                world_files(inv, world)
            except DeployError as e:
                print(f"{name}: {e}")
                problems += 1
                continue
            # Every world of a 26.x server lives inside its main save.
            if folder.strip("/").split("/")[0] != level:
                print(f"{name}: world folder '{folder}' isn't inside this server's level-name ({level})")
                problems += 1
    modules = {p.name for p in REPO.glob("rapid-*") if (p / "build.gradle.kts").is_file()}
    for set_name, globs in inv.get("plugin_sets", {}).items():
        for g in globs or []:
            if g.startswith(DIST_PREFIX):
                # Built by Gradle, not committed: check the name matches a plugin module.
                pattern = g[len(DIST_PREFIX):]
                if not any(fnmatch.fnmatch(f"{m}.jar", pattern) for m in modules):
                    print(f"plugin set {set_name}: '{g}' matches no rapid-* module's jar")
                    problems += 1
                continue
            n = len(list(LOCAL_PLUGINS.glob(g)))
            if n > 1:
                print(f"plugin set {set_name}: '{g}' matches {n} jars, expected 1")
                problems += 1
            elif n == 0:
                print(f"plugin set {set_name}: warning: '{g}' matches nothing in plugins/ (not committed?)")
    print("check: OK" if not problems else f"check: {problems} problem(s)")
    return 1 if problems else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("command", choices=["check", "servers", "diff", "apply", "plugins", "schedules", "worlds"])
    ap.add_argument("targets", nargs="*", help="server names from network.yml (default: all)")
    ap.add_argument("--local", type=Path, help="operate on LOCAL/<server>/ folders instead of the panel")
    ap.add_argument("--network", type=Path, default=INFRA / "network.yml")
    ap.add_argument("--secrets", type=Path, default=INFRA / "secrets.env")
    ap.add_argument("--restart", action="store_true",
                    help="apply/plugins: restart servers whose configs or plugins changed")
    ap.add_argument("--dry-run", action="store_true", help="plugins/schedules/worlds: only print what would change")
    ap.add_argument("--replace", action="store_true",
                    help="worlds: swap out a world that's already on the server (it's moved to world-backups/)")
    args = ap.parse_args()
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")

    try:
        inv = load_inventory(args.network)
        if args.command == "check":
            return check(inv)
        env = load_env(args.secrets)
        if not env:
            raise DeployError(f"{args.secrets} not found. Copy infra/secrets.env.example and fill it in.")
        backend = LocalBackend(args.local) if args.local else PanelBackend(env)
        targets = args.targets or list(inv["servers"])
        unknown = [t for t in targets if t not in inv["servers"]]
        if unknown:
            raise DeployError(f"unknown server(s): {', '.join(unknown)}")
        skipped = unset_servers(inv)

        if args.command == "servers":
            if isinstance(backend, PanelBackend):
                mapped = {ptero_id(srv): key for key, srv in inv["servers"].items() if ptero_id(srv)}
                print("Panel servers (copy the ID into network.yml → ptero_id):")
                print(f"  {'ID':10} {'allocation':22} {'network.yml':14} panel name")
                for s in backend.servers():
                    a = s["alloc"]
                    alloc = f"{a['ip']}:{a['port']}" if a else "-"
                    key = mapped.get(s["id"]) or mapped.get(s["uuid"]) or "-"
                    print(f"  {s['id']:10} {alloc:22} {key:14} {s['name']}")
                print()
            print("network.yml servers:")
            for t in targets:
                if t in skipped:
                    print(f"  {t:14} skipped (no ptero_id)")
                    continue
                try:
                    info = backend.server(t, inv)
                    a = info["alloc"]
                    alloc = f"{a['ip']}:{a['port']}" if a else "-"
                    print(f"  {t:14} id={info['id']:10} alloc={alloc}")
                except DeployError as e:
                    print(f"  {t:14} !! {e}")
            return 0

        if args.command == "apply":
            check_secrets(env)
        for t in targets:
            if t in skipped:
                print(f"[{t}] skipped: no ptero_id in network.yml")
                continue
            print(f"[{t}]")
            if args.command == "plugins":
                changed = sync_plugins(t, inv, backend, args.dry_run)
                if changed and args.restart and not args.dry_run:
                    print("  restarting")
                    backend.restart(backend.server(t, inv)["id"])
            elif args.command == "worlds":
                sync_worlds(t, inv, backend, args.replace, args.dry_run)
            elif args.command == "schedules":
                sid = backend.server(t, inv)["id"]
                for s in inv["servers"][t]["schedules"]:
                    spec = inv["schedules"][s]
                    backend.ensure_schedule(sid, f"rsdeploy: {s}", spec["cron"], spec["action"], args.dry_run)
            else:
                changed = configure_server(t, inv, env, backend, write=args.command == "apply")
                if changed and args.command == "apply" and args.restart:
                    print("  restarting")
                    backend.restart(backend.server(t, inv)["id"])
        if args.command == "apply" and not args.restart:
            print("\nConfig changes take effect on the next restart (or pass --restart).")
        return 0
    except DeployError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
