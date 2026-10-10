#!/usr/bin/env python3
"""Generate a Colosseum-style arena as a Sponge v3 schematic (.schem).

Usage: python3 generate_colosseum.py [output.schem]

The paste origin is the centre of the arena floor at ground level, so
`//paste` with WorldEdit/FAWE centres the arena on your position.
"""
import gzip
import math
import struct
import sys

# ---------------------------------------------------------------- layout --
R_PLAZA = 66          # outer edge of the tiled plaza
SIZE = 2 * R_PLAZA + 1
HEIGHT = 62
BAYS = 32             # arches around the facade
AISLES = 12           # stairways through the seating

R_ARENA = 24          # sand floor radius
R_PODIUM = (25, 26)   # wall around the arena floor
R_LOWER = (27, 39)    # lower seating ring
R_BALC_WALK = (40, 41)
R_BALC_WALL = 42
R_UPPER = (43, 55)    # upper seating ring
R_TOP_WALK = 56
R_CORRIDOR = (52, 56) # ambulatories behind the facade
R_WALL = (57, 60)     # outer facade
R_PILASTER = 61
R_LIP = 62

PODIUM_TOP = 6
LOWER_BASE = 7        # seat height at R_LOWER[0]
BALC_FLOOR = 20
BALC_TOP = 27
UPPER_BASE = 28
TOP_FLOOR = 40

CORNICES = (15, 29, 39, 49)   # terracotta bands; the row above is the lip
CORR1 = (1, 13)               # ground floor ambulatory (air rows)
CORR2 = (21, 27)              # first floor ambulatory (air rows)
SPIRE = (51, 61)

DATA_VERSION = 3955   # 1.21.1; WorldEdit upgrades it for newer versions


def h(x, y, z):
    """Deterministic per-block hash in [0, 1) for texture variation."""
    n = (x * 73856093) ^ (y * 19349663) ^ (z * 83492791)
    n = (n ^ (n >> 13)) * 1274126177
    return ((n ^ (n >> 16)) & 0xFFFF) / 65536.0


def stone(x, y, z):
    v = h(x, y, z)
    if v < 0.55:
        return "minecraft:smooth_sandstone"
    if v < 0.85:
        return "minecraft:sandstone"
    return "minecraft:cut_sandstone"


def outward(x, z):
    if abs(x) >= abs(z):
        return "east" if x > 0 else "west"
    return "south" if z > 0 else "north"


def inward(x, z):
    return {"east": "west", "west": "east", "north": "south", "south": "north"}[outward(x, z)]


def stairs(name, facing, half="bottom"):
    return f"minecraft:{name}[facing={facing},half={half},shape=straight,waterlogged=false]"


def slab(name, kind="bottom"):
    return f"minecraft:{name}[type={kind},waterlogged=false]"


def seat(x, y, z, upper):
    v = h(x, y, z)
    if v < 0.45:
        name = "polished_andesite_stairs"
    elif v < 0.8:
        name = "stone_stairs"
    else:
        name = "polished_diorite_stairs" if upper else "andesite_stairs"
    return stairs(name, outward(x, z))


def ring_pos(x, z, r, divisions):
    """Return (u_center, u_edge): arc distance (blocks) from the centre of the
    current division and from the nearest division boundary."""
    theta = math.atan2(z, x) % (2 * math.pi)
    f = theta / (2 * math.pi) * divisions
    frac = f - math.floor(f)
    arc = 2 * math.pi * r / divisions
    return abs(frac - 0.5) * arc, (0.5 - abs(frac - 0.5)) * arc


def arch_half_width(y, base, spring, top, width):
    """Pointed arch: constant width up to `spring`, narrowing to `top`."""
    if y < base or y > top:
        return -1
    if y <= spring:
        return width
    t = (y - spring) / (top - spring + 1)
    return width * math.sqrt(1 - t * t) * 0.95 - 0.35 * (y - spring)


def tier1(y):
    return arch_half_width(y, 1, 9, 13, 3.6)


def tier2(y):
    return arch_half_width(y, CORR2[0], 24, 27, 3.2)


def tier3_blind(y):
    return arch_half_width(y, 31, 35, 37, 2.6)


def attic_window(y, u):
    if 42 <= y <= 46 and u < 1.5:
        return True
    if 43 <= y <= 44 and u < 2.6:
        return True
    return y == 47 and u < 0.6


# ---------------------------------------------------------------- blocks --
def facade(x, y, z, r, rr):
    u, u_edge = ring_pos(x, z, 60, BAYS)

    if y == 0:
        return "minecraft:smooth_sandstone"

    # Spires on top of each pier.
    if y >= SPIRE[0]:
        if not (58.5 <= r < 61.0) or y > SPIRE[1]:
            return None
        if y >= SPIRE[1] - 1:
            return "minecraft:sandstone_wall" if u_edge < 0.7 else None
        width = 1.5 if y < SPIRE[0] + 4 else 0.9
        return "minecraft:terracotta" if u_edge < width else None

    if y in CORNICES:
        return "minecraft:terracotta"
    if y - 1 in CORNICES:
        if rr == R_LIP or rr == R_PILASTER:
            return slab("smooth_red_sandstone_slab")
        return "minecraft:cut_sandstone" if rr >= R_WALL[1] else stone(x, y, z)

    # Pilasters between the arches.
    if rr == R_PILASTER:
        if u_edge < 1.6:
            if y in (12, 13, 26, 27, 36, 37, 47, 48):  # capitals
                return "minecraft:chiseled_sandstone" if u_edge < 0.6 else "minecraft:cut_sandstone"
            return "minecraft:cut_sandstone" if u_edge < 0.6 else "minecraft:smooth_sandstone"
        return None

    # Arch openings straight through the wall.
    hw1, hw2 = tier1(y), tier2(y)
    if u < hw1 or u < hw2:
        return None
    if y >= 41 and attic_window(y, u):
        if rr == 58 and u < 0.6 and y in (42, 43):  # statues in the windows
            return "minecraft:andesite_wall" if y == 42 else "minecraft:polished_andesite"
        return None

    # Lanterns on the first floor arch sills.
    if y == CORR2[0] - 1 and rr == 59 and u < 0.6:
        return "minecraft:chiseled_sandstone"

    hw3 = tier3_blind(y)
    if rr == R_WALL[1]:
        if u < hw3:
            return None  # recess of the blind arch
        # arch frames around every opening
        for hw in (hw1, hw2, hw3):
            if hw > 0 and u < hw + 1.2:
                return "minecraft:cut_sandstone"
        if y in (14, 28, 38) and u < 1.0:
            return "minecraft:chiseled_sandstone"  # keystones
    if rr == R_WALL[1] - 1 and u < hw3:
        return "minecraft:chiseled_sandstone" if u < 0.6 and y == 33 else "minecraft:cut_sandstone"
    if y >= 41 and rr == R_WALL[0] and attic_window(y, u - 1.2):
        return "minecraft:cut_sandstone"
    return stone(x, y, z)


def facade_extra(x, y, z, rr):
    """Small blocks that sit inside openings (lanterns)."""
    u, _ = ring_pos(x, z, 60, BAYS)
    if y == CORR2[0] and rr == 59 and u < 0.6:
        return "minecraft:lantern[hanging=false,waterlogged=false]"
    return None


def interior(x, y, z, r, rr):
    """Arena floor, seating and the vaulted core under the seats."""
    u_aisle, _ = ring_pos(x, z, r, AISLES)
    _, u_card = ring_pos(x, z, r, 4)  # distance to the N/E/S/W axes
    u_bay, _ = ring_pos(x, z, 60, BAYS)
    u_door, _ = ring_pos(x, z, r, 8)

    tunnel = u_card < 2.5 and R_PODIUM[0] <= rr <= R_CORRIDOR[0] and 1 <= y <= 5
    if tunnel:
        if y == 5:
            return "minecraft:cut_sandstone" if rr in R_PODIUM else None
        return None

    if rr <= R_ARENA:
        if y == 0:
            return "minecraft:sand"
        return dais(x, y, z)

    if y == 0:
        return "minecraft:sandstone"

    if rr in R_PODIUM:
        if y > PODIUM_TOP:
            return None
        if rr == R_PODIUM[0] and u_card < 3.6:
            return "minecraft:chiseled_sandstone"  # gate frame
        if y == PODIUM_TOP:
            return "minecraft:cut_sandstone"
        if rr == R_PODIUM[0] and u_bay < 0.6 and y <= 2 and u_card > 6:
            half = "lower" if y == 1 else "upper"
            return (f"minecraft:spruce_door[facing={outward(x, z)},half={half},"
                    "hinge=left,open=false,powered=false]")
        if rr == R_PODIUM[0] and ring_pos(x, z, 60, BAYS)[1] < 1.0:
            return "minecraft:cut_sandstone"
        return stone(x, y, z)

    if R_CORRIDOR[0] <= rr <= R_CORRIDOR[1]:
        if CORR1[0] <= y <= CORR1[1]:
            if y == CORR1[1] and rr == 54 and u_bay < 0.6:
                return "minecraft:lantern[hanging=true,waterlogged=false]"
            return None
        if CORR2[0] <= y <= CORR2[1]:
            if y == CORR2[1] and rr == 54 and u_bay < 0.6:
                return "minecraft:lantern[hanging=true,waterlogged=false]"
            return None

    # Passages from the balcony into the first floor ambulatory.
    if R_BALC_WALL <= rr < R_CORRIDOR[0] and u_door < 1.1 and CORR2[0] <= y <= CORR2[0] + 2:
        return None

    if R_LOWER[0] <= rr <= R_LOWER[1]:
        top = LOWER_BASE + rr - R_LOWER[0]
        upper = False
    elif rr in R_BALC_WALK:
        top = BALC_FLOOR
        if y == BALC_FLOOR + 4 and rr == R_BALC_WALK[1] and u_bay < 0.6:
            return stairs("sandstone_stairs", inward(x, z), "top")  # bracket
        if y == BALC_FLOOR + 5 and rr == R_BALC_WALK[1] and u_bay < 0.6:
            return "minecraft:lantern[hanging=false,waterlogged=false]"
        upper = None
    elif rr == R_BALC_WALL:
        if y > BALC_TOP:
            return None
        if y == BALC_TOP:
            return "minecraft:cut_sandstone"
        if y > BALC_FLOOR and u_door < 1.1 and y <= BALC_FLOOR + 3:
            return "minecraft:chiseled_sandstone" if y == BALC_FLOOR + 3 else None
        return "minecraft:cut_sandstone" if ring_pos(x, z, 60, BAYS)[1] < 1.0 else stone(x, y, z)
    elif R_UPPER[0] <= rr <= R_UPPER[1]:
        top = UPPER_BASE + rr - R_UPPER[0]
        upper = True
    elif rr == R_TOP_WALK:
        top = TOP_FLOOR
        upper = None
    else:
        return None

    if y > top:
        return None
    if y < top:
        return stone(x, y, z)
    if upper is None:
        return "minecraft:cut_sandstone"
    if u_aisle < 1.0:
        return stairs("smooth_sandstone_stairs" if upper else "sandstone_stairs", outward(x, z))
    return seat(x, y, z, upper)


def dais(x, y, z):
    ax, az = abs(x), abs(z)
    if y == 1 and ax <= 8 and az <= 5:
        if ax == 8 or az == 5:
            face = "east" if ax == 8 and x < 0 else "west" if ax == 8 else "south" if z < 0 else "north"
            if ax == 8 and az == 5:
                return "minecraft:stone_bricks"
            return stairs("stone_brick_stairs", face)
        return "minecraft:stone_bricks"
    if y == 2 and ax <= 7 and az <= 4:
        if az == 4 and ax % 3 == 1:
            return "minecraft:dark_oak_planks"
        if ax == 7 or az == 4:
            return "minecraft:chiseled_stone_bricks" if (ax + az) % 2 == 0 else "minecraft:polished_andesite"
        return "minecraft:stone_bricks"
    if y == 3 and ax <= 5 and az <= 2:
        if ax <= 1 and az <= 1:
            return "minecraft:chiseled_stone_bricks" if ax + az != 0 else "minecraft:lodestone"
        return "minecraft:polished_andesite"
    return None


def plaza(x, z):
    mx, mz = x % 4, z % 4
    if mx == 0 or mz == 0:
        return "minecraft:cut_sandstone"
    if mx == 2 and mz == 2:
        return "minecraft:chiseled_sandstone"
    return "minecraft:smooth_sandstone"


def block_at(x, y, z):
    r = math.hypot(x, z)
    rr = int(round(r))
    if rr > R_PLAZA:
        return None
    if rr > R_LIP:
        return plaza(x, z) if y == 0 else None
    if rr >= R_WALL[0]:
        if rr < R_PILASTER and y > 0:
            extra = facade_extra(x, y, z, rr)
            if extra:
                return extra
        if rr == R_LIP and y not in [c + 1 for c in CORNICES]:
            return plaza(x, z) if y == 0 else None
        return facade(x, y, z, r, rr)
    return interior(x, y, z, r, rr)


# ------------------------------------------------------------------- NBT --
def _str(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def tag_compound(name, items):
    out = b"\x0a" + _str(name)
    for item in items:
        out += item
    return out + b"\x00"


def tag_int(name, v):
    return b"\x03" + _str(name) + struct.pack(">i", v)


def tag_short(name, v):
    return b"\x02" + _str(name) + struct.pack(">h", v)


def tag_int_array(name, vs):
    return b"\x0b" + _str(name) + struct.pack(">i", len(vs)) + struct.pack(f">{len(vs)}i", *vs)


def tag_byte_array(name, data):
    return b"\x07" + _str(name) + struct.pack(">i", len(data)) + bytes(data)


def tag_empty_compound_list(name):
    return b"\x09" + _str(name) + b"\x0a" + struct.pack(">i", 0)


def varint(v):
    out = bytearray()
    while True:
        if v & ~0x7F == 0:
            out.append(v)
            return out
        out.append((v & 0x7F) | 0x80)
        v >>= 7


def build():
    palette = {"minecraft:air": 0}
    data = bytearray()
    grid = []
    for y in range(HEIGHT):
        for z in range(SIZE):
            for x in range(SIZE):
                b = block_at(x - R_PLAZA, y, z - R_PLAZA) or "minecraft:air"
                idx = palette.setdefault(b, len(palette))
                data += varint(idx)
                grid.append(idx)
    return palette, data, grid


def write(path, palette, data):
    blocks = tag_compound("Blocks", [
        tag_compound("Palette", [tag_int(k, v) for k, v in palette.items()]),
        tag_byte_array("Data", data),
        tag_empty_compound_list("BlockEntities"),
    ])
    meta = tag_compound("Metadata", [
        tag_int("WEOffsetX", -R_PLAZA),
        tag_int("WEOffsetY", 0),
        tag_int("WEOffsetZ", -R_PLAZA),
    ])
    schem = tag_compound("Schematic", [
        tag_int("Version", 3),
        tag_int("DataVersion", DATA_VERSION),
        tag_short("Width", SIZE),
        tag_short("Height", HEIGHT),
        tag_short("Length", SIZE),
        tag_int_array("Offset", [0, 0, 0]),
        meta,
        blocks,
    ])
    with gzip.open(path, "wb") as f:
        f.write(tag_compound("", [schem]))


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "colosseum_arena.schem"
    palette, data, _ = build()
    write(out, palette, data)
    solid = sum(1 for _ in palette) - 1
    print(f"wrote {out}: {SIZE}x{HEIGHT}x{SIZE}, {solid} block types")
