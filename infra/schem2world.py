#!/usr/bin/env python3
"""schem2world: turn a Sponge schematic (.schem) into a world's chunk storage (region/*.mca).

The output is what `rsdeploy.py worlds` ships: a folder holding region/ (network.yml →
`worlds.<name>.source`). No level.dat is written; the server creates its own world data on
the next start, with the void generator from its profile, and fills in the rest:

  - Light is left out (isLightOn 0), so Paper lights every chunk when it first loads it.
  - Heightmaps are left out; the server computes them on load.
  - Chunks are tagged with DataVersion 4440 (1.21.8), the newest format this script writes,
    and Paper upgrades them on load as it does the other shipped worlds. Block states pass
    through untouched, so blocks newer than 1.21.8 (iron_chain, ...) keep their names.
    An older schematic keeps its own DataVersion, so Paper upgrades its states too.
  - Blocks that only render or work with a block entity (chests, beds, banners, signs,
    campfires, ...) get one: the schematic's own if it has one there, otherwise an empty one.
    Without it a chest or bed is invisible.
  - Every chunk gets one biome (--biome); it sets the grass, leaf and water colours.

Entities (item frames, armor stands) aren't converted: the script warns if the schematic
has any. Only chunks with at least one block are written; the void generator makes the rest.

The build is placed with --anchor: one block of the build (in the build's own coordinates,
as genMC and WorldEdit's offset give them: schematic position + Offset) and the world block
it lands on. Put the spawn marker at 0, y, 0 so the spawn is at the world's origin.

Usage:
  python infra/schem2world.py worlds/revamped/rapid-lobby-spawn.schem \\
      worlds/revamped/rapid-lobby-spawn --anchor 100,53,159=0,79,0 [--biome minecraft:plains] [--force]
"""
from __future__ import annotations

import argparse
import gzip
import io
import struct
import sys
import time
import zlib
from pathlib import Path

# ---------------------------------------------------------------- NBT (big-endian, Java)

END, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTE_ARRAY, STRING, LIST, COMPOUND, INT_ARRAY, LONG_ARRAY = range(13)
_SCALARS = {BYTE: ">b", SHORT: ">h", INT: ">i", LONG: ">q", FLOAT: ">f", DOUBLE: ">d"}


class Tag:
    """A typed NBT value. LIST tags keep their element type in `elem`; compounds are dicts
    of Tags; list elements are bare payloads (str, int, dict, Tag for nested lists...)."""
    __slots__ = ("type", "value", "elem")

    def __init__(self, type_: int, value, elem: int = END):
        self.type, self.value, self.elem = type_, value, elem


def _read_str(f) -> str:
    n = struct.unpack(">H", f.read(2))[0]
    return f.read(n).decode("utf-8", "surrogatepass")


def _read_payload(f, t: int):
    if t in _SCALARS:
        fmt = _SCALARS[t]
        return struct.unpack(fmt, f.read(struct.calcsize(fmt)))[0]
    if t == BYTE_ARRAY:
        return f.read(struct.unpack(">i", f.read(4))[0])
    if t == STRING:
        return _read_str(f)
    if t == LIST:
        et = f.read(1)[0]
        n = struct.unpack(">i", f.read(4))[0]
        return Tag(LIST, [_read_payload(f, et) for _ in range(n)], et)
    if t == COMPOUND:
        d = {}
        while (ct := f.read(1)[0]) != END:
            name = _read_str(f)
            v = _read_payload(f, ct)
            d[name] = v if ct == LIST else Tag(ct, v)
        return d
    if t == INT_ARRAY:
        n = struct.unpack(">i", f.read(4))[0]
        return list(struct.unpack(f">{n}i", f.read(4 * n)))
    if t == LONG_ARRAY:
        n = struct.unpack(">i", f.read(4))[0]
        return list(struct.unpack(f">{n}q", f.read(8 * n)))
    raise ValueError(f"bad NBT tag type {t}")


def read_nbt(path: Path) -> dict:
    data = path.read_bytes()
    if data[:2] == b"\x1f\x8b":
        data = gzip.decompress(data)
    f = io.BytesIO(data)
    t = f.read(1)[0]
    _read_str(f)
    if t != COMPOUND:
        raise ValueError(f"{path}: root tag isn't a compound")
    return _read_payload(f, t)


def val(tag):
    """The payload of a compound entry (unwraps Tag)."""
    return tag.value if isinstance(tag, Tag) else tag


def _w_str(out, s: str):
    b = s.encode("utf-8", "surrogatepass")
    out.write(struct.pack(">H", len(b)))
    out.write(b)


def _w_payload(out, t: int, v):
    if isinstance(v, Tag):
        if v.type == LIST:
            _w_list(out, v.elem, v.value)
            return
        v = v.value
    if t in _SCALARS:
        out.write(struct.pack(_SCALARS[t], v))
    elif t == BYTE_ARRAY:
        out.write(struct.pack(">i", len(v)))
        out.write(v)
    elif t == STRING:
        _w_str(out, v)
    elif t == LIST:
        raise TypeError("lists must be Tag(LIST, ...)")
    elif t == COMPOUND:
        for k, x in v.items():
            if not isinstance(x, Tag):
                raise TypeError(f"compound entry {k!r} isn't a Tag")
            out.write(bytes([x.type]))
            _w_str(out, k)
            _w_payload(out, x.type, x)
        out.write(b"\x00")
    elif t == INT_ARRAY:
        out.write(struct.pack(f">i{len(v)}i", len(v), *v))
    elif t == LONG_ARRAY:
        out.write(struct.pack(f">i{len(v)}q", len(v), *v))
    else:
        raise ValueError(f"bad NBT tag type {t}")


def _w_list(out, et: int, items: list):
    out.write(bytes([et if items else (et or END)]))
    out.write(struct.pack(">i", len(items)))
    for x in items:
        _w_payload(out, et, x)


def nbt_bytes(root: dict) -> bytes:
    out = io.BytesIO()
    out.write(bytes([COMPOUND]))
    _w_str(out, "")
    _w_payload(out, COMPOUND, root)
    return out.getvalue()


def S(v): return Tag(STRING, v)
def I(v): return Tag(INT, v)
def B(v): return Tag(BYTE, v)
def Lg(v): return Tag(LONG, v)
def C(d): return Tag(COMPOUND, d)


# ---------------------------------------------------------------- schematic

AIR = "minecraft:air"
# structure_void marks "leave whatever is there", which in a fresh void world is air.
AS_AIR = {"minecraft:air", "minecraft:structure_void"}


class Schematic:
    """A Sponge schematic, version 2 or 3: blocks as palette indices, x fastest, then z, then y."""

    def __init__(self, path: Path):
        root = read_nbt(path)
        if "Schematic" in root:  # v3 nests everything one level down
            root = val(root["Schematic"])
        version = val(root.get("Version", Tag(INT, 0)))
        if version == 3:
            blocks = val(root["Blocks"])
            palette, data = val(blocks["Palette"]), val(blocks["Data"])
            block_entities = blocks.get("BlockEntities")
        elif version in (1, 2):
            palette, data = val(root["Palette"]), val(root["BlockData"])
            block_entities = root.get("BlockEntities", root.get("TileEntities"))
        else:
            raise ValueError(f"{path}: unsupported Sponge schematic version {version}")
        self.data_version = val(root.get("DataVersion", Tag(INT, 0)))
        self.W, self.H, self.L = (val(root[k]) for k in ("Width", "Height", "Length"))
        self.offset = val(root["Offset"]) if "Offset" in root else [0, 0, 0]
        self.palette = [""] * len(palette)
        for state, i in palette.items():
            state = state if ":" in state.split("[")[0] else "minecraft:" + state
            self.palette[val(i)] = AIR if state.split("[")[0] in AS_AIR else state
        self.idx = _varints(data, self.W * self.H * self.L)
        # Block entities by schematic-local position: {(x, y, z): (id, data compound)}
        self.block_entities = {}
        for be in (block_entities.value if block_entities else []):
            x, y, z = val(be["Pos"])
            data = dict(val(be["Data"])) if "Data" in be else {k: v for k, v in be.items() if k not in ("Pos", "Id")}
            self.block_entities[(x, y, z)] = (val(be["Id"]), data)
        entities = root.get("Entities")
        self.entity_count = len(entities.value) if entities else 0


def _varints(data: bytes, n: int) -> list[int]:
    out = [0] * n
    i = pos = 0
    while pos < n:
        v = shift = 0
        while True:
            b = data[i]
            i += 1
            v |= (b & 0x7F) << shift
            if not b & 0x80:
                break
            shift += 7
        out[pos] = v
        pos += 1
    return out


def parse_state(state: str) -> dict:
    """'minecraft:oak_stairs[facing=north,half=top]' -> a block_states palette entry."""
    name, _, props = state.partition("[")
    entry = {"Name": S(name)}
    if props:
        entry["Properties"] = C({k: S(v) for k, v in (p.split("=", 1) for p in props.rstrip("]").split(","))})
    return entry


# Block entity ids (as of 1.21.8) for blocks that need one. Matched on the block name.
BE_EXACT = {
    "chest": "chest", "trapped_chest": "trapped_chest", "ender_chest": "ender_chest",
    "barrel": "barrel", "furnace": "furnace", "blast_furnace": "blast_furnace", "smoker": "smoker",
    "brewing_stand": "brewing_stand", "hopper": "hopper", "dropper": "dropper", "dispenser": "dispenser",
    "lectern": "lectern", "beacon": "beacon", "bell": "bell", "jukebox": "jukebox",
    "enchanting_table": "enchanting_table", "conduit": "conduit", "decorated_pot": "decorated_pot",
    "chiseled_bookshelf": "chiseled_bookshelf", "crafter": "crafter", "spawner": "mob_spawner",
    "trial_spawner": "trial_spawner", "vault": "vault", "sculk_sensor": "sculk_sensor",
    "calibrated_sculk_sensor": "calibrated_sculk_sensor", "sculk_catalyst": "sculk_catalyst",
    "sculk_shrieker": "sculk_shrieker", "comparator": "comparator", "daylight_detector": "daylight_detector",
    "end_gateway": "end_gateway", "end_portal": "end_portal", "campfire": "campfire",
    "soul_campfire": "campfire", "bee_nest": "beehive", "beehive": "beehive",
    "creaking_heart": "creaking_heart", "suspicious_sand": "brushable_block",
    "suspicious_gravel": "brushable_block", "command_block": "command_block",
    "chain_command_block": "command_block", "repeating_command_block": "command_block",
    "structure_block": "structure_block", "jigsaw": "jigsaw", "shulker_box": "shulker_box",
}
BE_SUFFIX = [("_hanging_sign", "hanging_sign"), ("_sign", "sign"), ("_banner", "banner"), ("_bed", "bed"),
             ("_head", "skull"), ("_skull", "skull"), ("_shulker_box", "shulker_box")]
# Blocks from after 1.21.8 that need a block entity this script can't name for that version.
BE_UNKNOWN_SUFFIX = ("copper_chest", "_shelf", "copper_golem_statue")


def block_entity_id(state: str) -> str | None:
    name = state.split("[")[0].removeprefix("minecraft:")
    if name in BE_EXACT:
        return "minecraft:" + BE_EXACT[name]
    for suffix, be in BE_SUFFIX:
        if name.endswith(suffix) and name != "piston_head":
            return "minecraft:" + be
    return None


# ---------------------------------------------------------------- chunks and regions

CHUNK_DATA_VERSION = 4440  # 1.21.8: the chunk layout written below
MIN_DATA_VERSION = 2860    # 1.18: block_states/biomes sections; older schematics aren't supported
MIN_Y, MAX_Y = -64, 320
SECTIONS = range(MIN_Y // 16, MAX_Y // 16)


def pack(indices: list[int], palette_size: int) -> list[int]:
    """Palette indices into the long array a section's block_states.data holds (no spanning)."""
    bits = max(4, (palette_size - 1).bit_length())
    per = 64 // bits
    longs = []
    for start in range(0, len(indices), per):
        v = 0
        for j, x in enumerate(indices[start:start + per]):
            v |= x << (j * bits)
        longs.append(v - (1 << 64) if v >= 1 << 63 else v)
    return longs


def convert(schem: Schematic, anchor_build, anchor_world, biome: str, data_version: int):
    """Returns ({(cx, cz): chunk NBT dict}, stats)."""
    W, H, L = schem.W, schem.H, schem.L
    # world = schematic-local + offset + shift
    shift = [w - b for w, b in zip(anchor_world, anchor_build)]
    base = [o + s for o, s in zip(schem.offset, shift)]
    x0, y0, z0 = base
    if y0 < MIN_Y or y0 + H > MAX_Y:
        raise SystemExit(f"the build would span y {y0}..{y0 + H - 1}, outside the world's {MIN_Y}..{MAX_Y - 1}")

    pal = schem.palette
    air_ids = {i for i, p in enumerate(pal) if p == AIR}
    be_ids = {i: block_entity_id(p) for i, p in enumerate(pal)}
    unknown_be = sorted({p.split("[")[0] for p in pal if p.split("[")[0].endswith(BE_UNKNOWN_SUFFIX)})
    idx = schem.idx
    biome_entry = {"palette": Tag(LIST, [biome], STRING)}

    chunks = {}
    stats = {"blocks": 0, "block_entities": 0, "copied_block_entities": 0, "unknown_be": unknown_be,
             "min": [x0, y0, z0], "max": [x0 + W - 1, y0 + H - 1, z0 + L - 1]}
    for cx in range(x0 >> 4, ((x0 + W - 1) >> 4) + 1):
        for cz in range(z0 >> 4, ((z0 + L - 1) >> 4) + 1):
            # The part of the schematic inside this chunk, in schematic-local x/z.
            sx0, sx1 = max(cx * 16 - x0, 0), min(cx * 16 + 16 - x0, W)
            sz0, sz1 = max(cz * 16 - z0, 0), min(cz * 16 + 16 - z0, L)
            pad_l, pad_r = sx0 + x0 - cx * 16, cx * 16 + 16 - (sx1 + x0)
            sections, block_entities, any_block = [], [], False
            for sy in SECTIONS:
                cells = []
                for wy in range(sy * 16, sy * 16 + 16):
                    ly = wy - y0
                    for wz in range(cz * 16, cz * 16 + 16):
                        lz = wz - z0
                        if not (0 <= ly < H and sz0 <= lz < sz1):
                            cells.extend([-1] * 16)
                            continue
                        row = ly * W * L + lz * W
                        cells.extend([-1] * pad_l)
                        cells.extend(idx[row + sx0:row + sx1])
                        cells.extend([-1] * pad_r)
                used = sorted(set(cells) - {-1} - air_ids)
                if not used:
                    sections.append({"Y": B(sy), "block_states": C({"palette": Tag(LIST, [parse_state_dict(AIR)], COMPOUND)}),
                                     "biomes": C(dict(biome_entry))})
                    continue
                any_block = True
                local = {g: i + 1 for i, g in enumerate(used)}  # 0 is air
                packed_idx = [local.get(g, 0) for g in cells]
                stats["blocks"] += sum(1 for v in packed_idx if v)
                palette = [parse_state_dict(AIR)] + [parse_state_dict(pal[g]) for g in used]
                states = {"palette": Tag(LIST, palette, COMPOUND), "data": Tag(LONG_ARRAY, pack(packed_idx, len(palette)))}
                sections.append({"Y": B(sy), "block_states": C(states), "biomes": C(dict(biome_entry))})
                # Block entities for this section.
                for i, g in enumerate(cells):
                    if g < 0 or not be_ids.get(g):
                        continue
                    wx, wy, wz = cx * 16 + (i & 15), sy * 16 + (i >> 8), cz * 16 + ((i >> 4) & 15)
                    lpos = (wx - x0, wy - y0, wz - z0)
                    be = {"id": S(be_ids[g]), "x": I(wx), "y": I(wy), "z": I(wz), "keepPacked": B(0)}
                    if lpos in schem.block_entities:
                        sid, sdata = schem.block_entities[lpos]
                        be.update({k: v for k, v in sdata.items() if k not in ("id", "x", "y", "z", "Pos", "Id")})
                        be["id"] = S(sid if ":" in sid else "minecraft:" + sid)
                        stats["copied_block_entities"] += 1
                    block_entities.append(be)
            if not any_block:
                continue
            stats["block_entities"] += len(block_entities)
            chunks[(cx, cz)] = {
                "DataVersion": I(data_version),
                "xPos": I(cx), "yPos": I(SECTIONS.start), "zPos": I(cz),
                "Status": S("minecraft:full"),
                "LastUpdate": Lg(0), "InhabitedTime": Lg(0),
                "isLightOn": B(0),
                "sections": Tag(LIST, sections, COMPOUND),
                "block_entities": Tag(LIST, block_entities, COMPOUND),
                "block_ticks": Tag(LIST, [], END), "fluid_ticks": Tag(LIST, [], END),
                "PostProcessing": Tag(LIST, [Tag(LIST, [], END) for _ in SECTIONS], LIST),
                "structures": C({"References": C({}), "starts": C({})}),
            }
    return chunks, stats


_state_cache: dict[str, dict] = {}


def parse_state_dict(state: str) -> dict:
    if state not in _state_cache:
        _state_cache[state] = parse_state(state)
    return _state_cache[state]


def write_regions(chunks: dict, region_dir: Path) -> list[Path]:
    regions: dict[tuple[int, int], dict] = {}
    for (cx, cz), chunk in chunks.items():
        regions.setdefault((cx >> 5, cz >> 5), {})[(cx & 31, cz & 31)] = chunk
    now = int(time.time())
    written = []
    for (rx, rz), rchunks in sorted(regions.items()):
        locations, timestamps, body = bytearray(4096), bytearray(4096), bytearray()
        for (lx, lz), chunk in sorted(rchunks.items()):
            payload = zlib.compress(nbt_bytes(chunk))
            blob = struct.pack(">iB", len(payload) + 1, 2) + payload
            blob += b"\x00" * (-len(blob) % 4096)
            sectors = len(blob) // 4096
            if sectors > 255:
                raise SystemExit(f"chunk {rx * 32 + lx},{rz * 32 + lz} is too large for a region file")
            offset = 2 + len(body) // 4096
            i = 4 * (lx + lz * 32)
            locations[i:i + 4] = offset.to_bytes(3, "big") + bytes([sectors])
            timestamps[i:i + 4] = now.to_bytes(4, "big")
            body += blob
        path = region_dir / f"r.{rx}.{rz}.mca"
        path.write_bytes(bytes(locations) + bytes(timestamps) + bytes(body))
        written.append(path)
    return written


def triple(s: str) -> list[int]:
    parts = [int(p) for p in s.split(",")]
    if len(parts) != 3:
        raise argparse.ArgumentTypeError(f"expected x,y,z, got {s!r}")
    return parts


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("schematic", type=Path)
    ap.add_argument("out", type=Path, help="world folder; region/ is written inside it")
    ap.add_argument("--anchor", required=True,
                    help="BX,BY,BZ=WX,WY,WZ: the build block (build coordinates) and the world block it lands on")
    ap.add_argument("--biome", default="minecraft:plains", help="biome for every chunk (default minecraft:plains)")
    ap.add_argument("--force", action="store_true", help="replace the region files already in OUT/region")
    args = ap.parse_args()

    build_pos, _, world_pos = args.anchor.partition("=")
    anchor_build, anchor_world = triple(build_pos), triple(world_pos)

    schem = Schematic(args.schematic)
    if schem.data_version and schem.data_version < MIN_DATA_VERSION:
        raise SystemExit(f"schematic DataVersion {schem.data_version} is from before 1.18; not supported")
    data_version = min(schem.data_version or CHUNK_DATA_VERSION, CHUNK_DATA_VERSION)

    region_dir = args.out / "region"
    existing = sorted(region_dir.glob("r.*.*.mca")) if region_dir.exists() else []
    if existing and not args.force:
        raise SystemExit(f"{region_dir} already has {len(existing)} region file(s); pass --force to replace them")

    chunks, stats = convert(schem, anchor_build, anchor_world, args.biome, data_version)
    region_dir.mkdir(parents=True, exist_ok=True)
    for f in existing:
        f.unlink()
    written = write_regions(chunks, region_dir)

    print(f"{args.schematic.name}: {schem.W}x{schem.H}x{schem.L}, DataVersion {schem.data_version} "
          f"-> chunks tagged {data_version}")
    print(f"  {stats['blocks']} blocks in {len(chunks)} chunks, {len(written)} region file(s) in {region_dir}")
    print(f"  world bounds x {stats['min'][0]}..{stats['max'][0]}, y {stats['min'][1]}..{stats['max'][1]}, "
          f"z {stats['min'][2]}..{stats['max'][2]}")
    print(f"  {stats['block_entities']} block entities ({stats['copied_block_entities']} from the schematic, "
          f"the rest empty)")
    if stats["unknown_be"]:
        print(f"  WARNING: no block entity written for {', '.join(stats['unknown_be'])} (newer than 1.21.8); "
              f"they may be invisible until replaced in-game", file=sys.stderr)
    if schem.entity_count:
        print(f"  WARNING: {schem.entity_count} entities in the schematic were not converted", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
