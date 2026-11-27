#!/usr/bin/env python3
"""Generate ProtoForm multi-resolution PNG and Windows ICO assets with zero external dependencies."""

import math
import struct
import zlib
from pathlib import Path


def clamp(v: float, lo: float = 0.0, hi: float = 1.0) -> float:
    return lo if v < lo else (hi if v > hi else v)


def smoothstep(edge0: float, edge1: float, x: float) -> float:
    if abs(edge1 - edge0) < 1e-9:
        return 1.0 if x >= edge1 else 0.0
    t = clamp((x - edge0) / (edge1 - edge0))
    return t * t * (3.0 - 2.0 * t)


def lerp(a: float, b: float, t: float) -> float:
    return a + (b - a) * t


def blend(dst, src):
    sr, sg, sb, sa = src
    if sa <= 0.0:
        return dst
    dr, dg, db, da = dst
    out_a = sa + da * (1.0 - sa)
    if out_a <= 1e-6:
        return (0.0, 0.0, 0.0, 0.0)
    return (
        (sr * sa + dr * da * (1.0 - sa)) / out_a,
        (sg * sa + dg * da * (1.0 - sa)) / out_a,
        (sb * sa + db * da * (1.0 - sa)) / out_a,
        out_a,
    )


def sd_rounded_box(px: float, py: float, cx: float, cy: float, hw: float, hh: float, r: float) -> float:
    r = min(r, hw, hh)
    dx = abs(px - cx) - hw + r
    dy = abs(py - cy) - hh + r
    return math.hypot(max(dx, 0.0), max(dy, 0.0)) + min(max(dx, dy), 0.0) - r


def sd_segment(px: float, py: float, ax: float, ay: float, bx: float, by: float) -> float:
    pax, pay = px - ax, py - ay
    bax, bay = bx - ax, by - ay
    denom = bax * bax + bay * bay
    h = clamp((pax * bax + pay * bay) / denom) if denom > 1e-9 else 0.0
    return math.hypot(pax - bax * h, pay - bay * h)


def sd_symmetric_poly(px: float, py: float, pts_right: list[tuple[float, float]], round_r: float = 0.0) -> float:
    """Seamless symmetric polygon SDF around x=0.50 without center-seam artifacts."""
    full = list(pts_right) + [(1.0 - x, y) for (x, y) in reversed(pts_right) if abs(x - 0.50) > 1e-6]
    n = len(full)
    d2 = 1e9
    inside = False
    j = n - 1
    for i in range(n):
        xi, yi = full[i]
        xj, yj = full[j]
        ex, ey = xj - xi, yj - yi
        wx, wy = px - xi, py - yi
        denom = ex * ex + ey * ey
        t = clamp((wx * ex + wy * ey) / denom) if denom > 1e-12 else 0.0
        cx, cy = wx - ex * t, wy - ey * t
        d2 = min(d2, cx * cx + cy * cy)
        if ((yi > py) != (yj > py)) and (px < (xj - xi) * (py - yi) / (yj - yi + 1e-12) + xi):
            inside = not inside
        j = i
    return (-math.sqrt(d2) if inside else math.sqrt(d2)) - round_r


def shade_sculpted(px_col, box_alpha, nx, ny, aa, size, fn, c_top, c_bot, bevel=(1.0, 1.0, 1.0), shadow_a=0.60):
    d = fn(nx, ny)
    if size >= 32 and shadow_a > 0.0:
        d_sh = fn(nx, ny - 0.028)
        px_col = blend(px_col, (0.01, 0.02, 0.04, (1.0 - smoothstep(-0.02, 0.048, d_sh)) * box_alpha * shadow_a))
    a = (1.0 - smoothstep(-aa, aa, d)) * box_alpha
    t = clamp((ny - 0.18) / 0.64)
    r = lerp(c_top[0] / 255.0, c_bot[0] / 255.0, t)
    g = lerp(c_top[1] / 255.0, c_bot[1] / 255.0, t)
    b = lerp(c_top[2] / 255.0, c_bot[2] / 255.0, t)
    if size >= 48:
        d_off = fn(nx - 0.012, ny - 0.014)
        bev = clamp((d_off - d) / 0.02) * 0.28
        r, g, b = lerp(r, bevel[0], bev), lerp(g, bevel[1], bev), lerp(b, bevel[2], bev)
    return blend(px_col, (r, g, b, a))


def draw_node(px_col, box_alpha, nx, ny, aa, cx, cy, r_outer, r_core):
    d_n = math.hypot(nx - cx, ny - cy) - r_outer
    px_col = blend(px_col, (0x2d / 255.0, 0xd4 / 255.0, 0xbf / 255.0, (1.0 - smoothstep(-aa, aa, d_n)) * box_alpha))
    d_c = math.hypot(nx - cx, ny - cy) - r_core
    px_col = blend(px_col, (0xf0 / 255.0, 0xfd / 255.0, 0xfa / 255.0, (1.0 - smoothstep(-aa, aa, d_c)) * box_alpha))
    return px_col


def sample_sentinel_owl(nx: float, ny: float, aa: float, size: int):
    """Render Carbon tile + ProtoForm Sentinel Owl emblem."""
    hw = 0.455 if size <= 32 else 0.445
    d_box = sd_rounded_box(nx, ny, 0.5, 0.5, hw, hw, 0.16)
    box_alpha = 1.0 - smoothstep(-aa, aa, d_box)

    grad_t = clamp(nx * 0.30 + ny * 0.70)
    bg_r = lerp(0x21 / 255.0, 0x0d / 255.0, grad_t)
    bg_g = lerp(0x28 / 255.0, 0x10 / 255.0, grad_t)
    bg_b = lerp(0x36 / 255.0, 0x15 / 255.0, grad_t)

    glow_d = math.hypot(nx - 0.50, ny - 0.46)
    glow = (1.0 - smoothstep(0.04, 0.56, glow_d)) * 0.25
    bg_r = lerp(bg_r, 0x14 / 255.0, glow)
    bg_g = lerp(bg_g, 0xb8 / 255.0, glow)
    bg_b = lerp(bg_b, 0xa6 / 255.0, glow)

    px_col = (bg_r, bg_g, bg_b, box_alpha)

    if size >= 32:
        top_hl = (1.0 - smoothstep(0.0, 0.045, abs(d_box + 0.018))) * clamp(1.0 - (ny - 0.05) * 2.2) * box_alpha * 0.22
        px_col = blend(px_col, (0xe2 / 255.0, 0xe8 / 255.0, 0xf0 / 255.0, top_hl))

    border_d = abs(d_box + 0.013) - 0.012
    border_alpha = (1.0 - smoothstep(-aa, aa, border_d)) * box_alpha * 0.90
    px_col = blend(px_col, (0x38 / 255.0, 0x48 / 255.0, 0x5e / 255.0, border_alpha))

    # Outer faceted owl shield
    head_pts = [(0.50, 0.30), (0.78, 0.20), (0.76, 0.56), (0.50, 0.80)]
    head_fn = lambda x, y: sd_symmetric_poly(x, y, head_pts, round_r=0.018)
    px_col = shade_sculpted(px_col, box_alpha, nx, ny, aa, size, head_fn, (0x2d, 0x3b, 0x4e), (0x16, 0x20, 0x2d))

    d_hd = head_fn(nx, ny)
    d_rim = abs(d_hd + 0.010) - (0.015 if size <= 32 else 0.010)
    px_col = blend(px_col, (0x2d / 255.0, 0xd4 / 255.0, 0xbf / 255.0, (1.0 - smoothstep(-aa, aa, d_rim)) * box_alpha * 0.90))

    # Dark inner eye-mask visor
    mask_pts = [(0.50, 0.37), (0.71, 0.31), (0.69, 0.54), (0.50, 0.62)]
    d_mask = sd_symmetric_poly(nx, ny, mask_pts, round_r=0.025)
    px_col = blend(px_col, (0x0c / 255.0, 0x11 / 255.0, 0x17 / 255.0, (1.0 - smoothstep(-aa, aa, d_mask)) * box_alpha * 0.85))

    # Sculpted platinum V-brow / ear-horns
    st_brow = 0.036 if size <= 32 else 0.030
    brow_fn = lambda x, y: min(
        sd_segment(x, y, 0.22, 0.21, 0.50, 0.38),
        sd_segment(x, y, 0.78, 0.21, 0.50, 0.38),
    ) - st_brow
    px_col = shade_sculpted(px_col, box_alpha, nx, ny, aa, size, brow_fn, (0xf8, 0xfa, 0xfc), (0xcb, 0xd5, 0xe1), shadow_a=0.45)

    # Signal-teal diamond beak
    beak_fn = lambda x, y: sd_symmetric_poly(x, y, [(0.50, 0.53), (0.565, 0.62), (0.50, 0.74)], round_r=0.008)
    px_col = shade_sculpted(px_col, box_alpha, nx, ny, aa, size, beak_fn, (0x5e, 0xea, 0xd4), (0x0f, 0x76, 0x6e), shadow_a=0.35)

    # Luminous signal-node eyes
    r_out = 0.072 if size <= 32 else 0.060
    r_cor = 0.035 if size <= 32 else 0.028
    px_col = draw_node(px_col, box_alpha, nx, ny, aa, 0.355, 0.475, r_out, r_cor)
    px_col = draw_node(px_col, box_alpha, nx, ny, aa, 0.645, 0.475, r_out, r_cor)
    return px_col


def render_icon(size: int) -> bytes:
    """Render an RGBA byte buffer (size x size x 4) using 4x4 supersampling at <=64px and 2x2 at >=128px."""
    aa = (0.68 / size) if size <= 32 else (1.0 / (size * 1.18))
    if size <= 64:
        grid = (-0.375, -0.125, 0.125, 0.375)
    else:
        grid = (-0.25, 0.25)
    samples = [(sx, sy) for sy in grid for sx in grid]
    n_samples = float(len(samples))
    buf = bytearray(size * size * 4)

    for y in range(size):
        for x in range(size):
            ar = ag = ab = aa_acc = 0.0
            for sx, sy in samples:
                nx = (x + 0.5 + sx) / size
                ny = (y + 0.5 + sy) / size
                r, g, b, a = sample_sentinel_owl(nx, ny, aa, size)
                ar += r
                ag += g
                ab += b
                aa_acc += a
            idx = (y * size + x) * 4
            buf[idx] = int(clamp(ar / n_samples) * 255 + 0.5)
            buf[idx + 1] = int(clamp(ag / n_samples) * 255 + 0.5)
            buf[idx + 2] = int(clamp(ab / n_samples) * 255 + 0.5)
            buf[idx + 3] = int(clamp(aa_acc / n_samples) * 255 + 0.5)

    return bytes(buf)


def encode_png(size: int, rgba: bytes) -> bytes:
    def chunk(tag: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    stride = size * 4
    raw = b"".join(b"\x00" + rgba[y * stride : (y + 1) * stride] for y in range(size))
    idat = zlib.compress(raw, level=9)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", idat) + chunk(b"IEND", b"")


def encode_bmp_dib_for_ico(size: int, rgba: bytes) -> bytes:
    """Encode 32-bit BGRA DIB + 1-bit AND mask for maximum Windows .ico compatibility."""
    header = struct.pack("<IIIHHIIIIII", 40, size, size * 2, 1, 32, 0, 0, 0, 0, 0, 0)
    xor_rows = []
    and_rows = []
    and_row_bytes = ((size + 31) // 32) * 4

    for y in range(size - 1, -1, -1):
        row = bytearray()
        mask = bytearray(and_row_bytes)
        for x in range(size):
            idx = (y * size + x) * 4
            r, g, b, a = rgba[idx], rgba[idx + 1], rgba[idx + 2], rgba[idx + 3]
            row.extend((b, g, r, a))
            if a == 0:
                mask[x // 8] |= 1 << (7 - (x % 8))
        xor_rows.append(bytes(row))
        and_rows.append(bytes(mask))

    return header + b"".join(xor_rows) + b"".join(and_rows)


def write_ico(path: Path, frames: list[tuple[int, bytes]]) -> None:
    entries = []
    payloads = []
    offset = 6 + 16 * len(frames)

    for size, rgba in frames:
        data = encode_png(size, rgba) if size >= 128 else encode_bmp_dib_for_ico(size, rgba)
        w_byte = 0 if size >= 256 else size
        h_byte = 0 if size >= 256 else size
        entries.append(struct.pack("<BBBBHHII", w_byte, h_byte, 0, 0, 1, 32, len(data), offset))
        payloads.append(data)
        offset += len(data)

    header = struct.pack("<HHH", 0, 1, len(frames))
    path.write_bytes(header + b"".join(entries) + b"".join(payloads))


def main() -> None:
    root = Path(__file__).resolve().parent.parent
    res_icons = root / "src" / "main" / "resources" / "icons"
    pkg_icons = root / "packaging" / "icons"
    res_icons.mkdir(parents=True, exist_ok=True)
    pkg_icons.mkdir(parents=True, exist_ok=True)

    rendered = {s: render_icon(s) for s in (16, 32, 48, 64, 128, 256, 512)}

    for s in (16, 32, 48, 128, 256):
        (res_icons / f"protoform-{s}.png").write_bytes(encode_png(s, rendered[s]))

    (pkg_icons / "protoform.png").write_bytes(encode_png(512, rendered[512]))
    write_ico(pkg_icons / "protoform.ico", [(s, rendered[s]) for s in (16, 32, 48, 64, 128, 256)])
    print("Generated ProtoForm Sentinel Owl PNG and ICO icon assets.")


if __name__ == "__main__":
    main()
