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
  <file>         Any other file is written verbatim.
Both may contain ${NAME} placeholders, resolved from infra/secrets.env, the
server's `vars` in network.yml, SERVER_NAME, and ${addr:<server>}.

Usage:
  python infra/rsdeploy.py check                   # offline validation (CI)
  python infra/rsdeploy.py servers                # show servers found in the panel
  python infra/rsdeploy.py diff  [server ...]      # show what `apply` would change
  python infra/rsdeploy.py apply [server ...] [--restart]
  python infra/rsdeploy.py plugins [server ...] [--dry-run]
  python infra/rsdeploy.py schedules [server ...] [--dry-run]   # backup schedules
  add --local DIR to work on DIR/<server>/ folders instead of the panel API.
"""
from __future__ import annotations

import argparse
import difflib
import fnmatch
import io
import json
import os
import re
import shutil
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass, field
from pathlib import Path

try:
    import tomlkit
    from ruamel.yaml import YAML
    from ruamel.yaml.comments import CommentedMap
except ImportError:  # pragma: no cover
    sys.exit("Missing dependencies: pip install -r infra/requirements.txt")

INFRA = Path(__file__).resolve().parent
REPO = INFRA.parent
PROFILES = INFRA / "profiles"
LOCAL_PLUGINS = REPO / "plugins"
PLACEHOLDER = re.compile(r"\$\{([A-Za-z0-9_:.\-]+)\}")
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


def load_inventory(path: Path) -> dict:
    data = YAML(typ="safe").load(path.read_text(encoding="utf-8"))
    for name, srv in data["servers"].items():
        srv.setdefault("profiles", [])
        srv.setdefault("plugins", [])
        srv.setdefault("vars", {})
        srv.setdefault("schedules", [])
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
                plans.setdefault(target, FilePlan()).patch.update(body)
            else:
                plans.setdefault(rel, FilePlan()).verbatim = f.read_bytes()
    return plans


# --------------------------------------------------------------------------- substitution

class Resolver:
    def __init__(self, env: dict[str, str], server_vars: dict, addr_lookup):
        self.env = env
        self.vars = {k: str(v) for k, v in server_vars.items()}
        self.addr_lookup = addr_lookup

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
            return {k: self.value(x) for k, x in v.items()}
        return v


# --------------------------------------------------------------------------- patchers

def patch_properties(text: str, patch: dict) -> str:
    lines = text.splitlines()
    remaining = dict(patch)
    for i, line in enumerate(lines):
        if line.lstrip().startswith(("#", "!")) or "=" not in line:
            continue
        key = line.split("=", 1)[0].strip()
        if key in remaining:
            lines[i] = f"{key}={_prop_str(remaining.pop(key))}"
    for key, value in remaining.items():
        lines.append(f"{key}={_prop_str(value)}")
    return "\n".join(lines) + "\n"


def _prop_str(v) -> str:
    if isinstance(v, bool):
        return "true" if v else "false"
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


def patch_yaml(text: str, patch: dict) -> str:
    y = yaml_rt()
    indent, nested_lists = _detect_yaml_style(text)
    if nested_lists:
        y.indent(mapping=indent, sequence=indent + 2, offset=indent)
    else:
        y.indent(mapping=indent, sequence=indent, offset=0)
    doc = y.load(text) if text.strip() else None
    if doc is None:
        doc = CommentedMap()
    for path, value in patch.items():
        keys = str(path).split(".")
        node = doc
        for k in keys[:-1]:
            if not isinstance(node.get(k), dict):
                node[k] = CommentedMap()
            node = node[k]
        # Re-assigning an equal list would drop comments ruamel attached to it.
        if keys[-1] in node and node[keys[-1]] == value:
            continue
        node[keys[-1]] = value
    out = io.StringIO()
    y.dump(doc, out)
    return out.getvalue()


def patch_toml(text: str, patch: dict) -> str:
    doc = tomlkit.parse(text)
    for path, value in patch.items():
        keys = str(path).split(".")
        node = doc
        for k in keys[:-1]:
            if k not in node:
                node[k] = tomlkit.table()
            node = node[k]
        if isinstance(value, dict):
            table = tomlkit.table()
            for k, v in value.items():
                table[k] = v
            node[keys[-1]] = table
        else:
            node[keys[-1]] = value
    return tomlkit.dumps(doc)


def apply_patch(target: str, text: str, patch: dict) -> str:
    if target.endswith(".properties"):
        return patch_properties(text, patch)
    if target.endswith((".yml", ".yaml")):
        return patch_yaml(text, patch)
    if target.endswith(".toml"):
        return patch_toml(text, patch)
    raise DeployError(f"don't know how to patch {target}")


# --------------------------------------------------------------------------- backends

class PanelBackend:
    """Pterodactyl client API (/api/client, key from Account -> API Credentials)."""

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

    def servers(self) -> dict[str, dict]:
        if self._servers is None:
            self._servers, page = {}, 1
            while True:
                res = self.api("GET", f"?page={page}&per_page=100")
                for s in res["data"]:
                    a = s["attributes"]
                    allocs = [x["attributes"] for x in a["relationships"]["allocations"]["data"]]
                    default = next((x for x in allocs if x.get("is_default")), allocs[0] if allocs else None)
                    self._servers[a["name"]] = {"id": a["identifier"], "alloc": default}
                meta = res["meta"]["pagination"]
                if meta["current_page"] >= meta["total_pages"]:
                    break
                page += 1
        return self._servers

    def server(self, name: str, inv: dict) -> dict:
        srv = inv["servers"][name]
        if "ptero_id" in srv:
            match = next((s for s in self.servers().values() if s["id"] == srv["ptero_id"]), None)
        else:
            match = self.servers().get(srv.get("name", name))
        if not match:
            raise DeployError(f"server '{name}' not found in the panel (set `name` or `ptero_id` in network.yml)")
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

    def read(self, sid: str, path: str) -> bytes | None:
        try:
            data = self.api("GET", f"/servers/{sid}/files/contents?file={urllib.parse.quote('/' + path)}", raw=True)
        except DeployError as e:
            if "HTTP 404" in str(e):
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
            if "HTTP 404" in str(e):
                return {}
            raise
        return {x["attributes"]["name"]: x["attributes"]["size"] for x in res["data"] if x["attributes"]["is_file"]}

    def upload(self, sid: str, directory: str, local: Path) -> None:
        url = self.api("GET", f"/servers/{sid}/files/upload")["attributes"]["url"]
        boundary = uuid.uuid4().hex
        body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"files\"; filename=\"{local.name}\"\r\n"
                f"Content-Type: application/java-archive\r\n\r\n").encode() + local.read_bytes() + \
            f"\r\n--{boundary}--\r\n".encode()
        sep = "&" if "?" in url else "?"
        self._req("POST", f"{url}{sep}directory={urllib.parse.quote(directory)}", body=body,
                  ctype=f"multipart/form-data; boundary={boundary}", raw=True, headers={})

    def delete(self, sid: str, directory: str, names: list[str]) -> None:
        self.api("POST", f"/servers/{sid}/files/delete", body=json.dumps({"root": directory, "files": names}).encode(),
                 ctype="application/json", raw=True)

    def restart(self, sid: str) -> None:
        self.api("POST", f"/servers/{sid}/power", body=b'{"signal":"restart"}', ctype="application/json", raw=True)

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

    def delete(self, sid: str, directory: str, names: list[str]) -> None:
        for n in names:
            (self.root / sid / directory.strip("/") / n).unlink(missing_ok=True)

    def restart(self, sid: str) -> None:
        print(f"  (local mode: restart {sid} yourself)")

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
    resolver = Resolver(env, {"SERVER_NAME": name, **srv["vars"]}, lambda n: backend.address(n, inv))
    changed = False
    for target, plan in collect_profile_files(srv["profiles"]).items():
        current = backend.read(sid, target)
        if plan.verbatim is not None:
            base = plan.verbatim
            text = _as_text(base)
            if text is not None:
                base = resolver.text(text).encode("utf-8")
        elif current is None:
            print(f"  ! {target}: not on the server yet. Start {name} once so it generates "
                  f"its defaults, then run again.")
            continue
        else:
            base = current
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


def sync_plugins(name: str, inv: dict, backend, dry_run: bool) -> None:
    srv = inv["servers"][name]
    sid = backend.server(name, inv)["id"]
    remote = backend.list_dir(sid, "/plugins")
    globs = [g for s in srv["plugins"] for g in (inv["plugin_sets"][s] or [])]
    for g in globs:
        local = sorted(LOCAL_PLUGINS.glob(g))
        if len(local) != 1:
            raise DeployError(f"plugin glob '{g}' matched {len(local)} jars in {LOCAL_PLUGINS} (need exactly 1)")
        jar = local[0]
        stale = [r for r in remote if fnmatch.fnmatch(r, g) and r != jar.name]
        up_to_date = remote.get(jar.name) == jar.stat().st_size
        if up_to_date and not stale:
            continue
        if not up_to_date:
            print(f"  + {jar.name}")
            if not dry_run:
                backend.upload(sid, "/plugins", jar)
        if stale:
            print(f"  - {', '.join(stale)}")
            if not dry_run:
                backend.delete(sid, "/plugins", stale)


def check(inv: dict) -> int:
    """Offline validation for CI: profiles parse, placeholders are known, globs are sane."""
    known = set(load_env(INFRA / "secrets.env.example")) | {"SERVER_NAME"}
    problems = 0
    for name, srv in inv["servers"].items():
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
    for set_name, globs in inv.get("plugin_sets", {}).items():
        for g in globs or []:
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
    ap.add_argument("command", choices=["check", "servers", "diff", "apply", "plugins", "schedules"])
    ap.add_argument("targets", nargs="*", help="server names from network.yml (default: all)")
    ap.add_argument("--local", type=Path, help="operate on LOCAL/<server>/ folders instead of the panel")
    ap.add_argument("--network", type=Path, default=INFRA / "network.yml")
    ap.add_argument("--secrets", type=Path, default=INFRA / "secrets.env")
    ap.add_argument("--restart", action="store_true", help="apply: restart servers whose config changed")
    ap.add_argument("--dry-run", action="store_true", help="plugins/schedules: only print what would change")
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

        if args.command == "servers":
            for t in targets:
                try:
                    info = backend.server(t, inv)
                    a = info["alloc"]
                    alloc = f"{a['ip']}:{a['port']}" if a else "-"
                    print(f"{t:14} id={info['id']:10} alloc={alloc}")
                except DeployError as e:
                    print(f"{t:14} !! {e}")
            return 0

        if args.command == "apply":
            check_secrets(env)
        for t in targets:
            print(f"[{t}]")
            if args.command == "plugins":
                sync_plugins(t, inv, backend, args.dry_run)
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
