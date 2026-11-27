#!/usr/bin/env python3
"""Generate ProtoForm multi-resolution PNG and Windows ICO assets with zero external dependencies."""

import math
import os
import struct
import zlib
from pathlib import Path


def clamp(v: float, lo: float = 0.0, hi: float = 1.0) -> float:
    return lo if v < lo else (hi if v > hi else v)


def smoothstep(edge0: float, edge1: float, x: float) -> float:
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
    out_r = (sr * sa + dr * da * (1.0 - sa)) / out_a
    out_g = (sg * sa + dg * da * (1.0 - sa)) / out_a
    out_b = (sb * sa + db * da * (1.0 - sa)) / out_a
    return (out_r, out_g, out_b, out_a)


def sd_rounded_box(px: float, py: float, cx: float, cy: float, hw: float, hh: float, r: float) -> float:
    dx = abs(px - cx) - hw + r
    dy = abs(py - cy) - hh + r
    ox = max(dx, 0.0)
    oy = max(dy, 0.0)
    return math.hypot(ox, oy) + min(max(dx, dy), 0.0) - r


def sd_segment(px: float, py: float, ax: float, ay: float, bx: float, by: float) -> float:
    pax, pay = px - ax, py - ay
    bax, bay = bx - ax, by - ay
    denom = bax * bax + bay * bay
    h = clamp((pax * bax + pay * bay) / denom) if denom > 1e-9 else 0.0
    return math.hypot(pax - bax * h, pay - bay * h)


def render_icon(size: int) -> bytes:
    """Render an RGBA byte buffer (size x size x 4) using 2x2 supersampling."""
    aa = 1.0 / (size * 1.15)
    buf = bytearray(size * size * 4)
    samples = ((-0.25, -0.25), (0.25, -0.25), (-0.25, 0.25), (0.25, 0.25))

    for y in range(size):
        for x in range(size):
            ar = ag = ab = aa_acc = 0.0
            for sx, sy in samples:
                nx = (x + 0.5 + sx) / size
                ny = (y + 0.5 + sy) / size

                # Base rounded graphite square
                d_box = sd_rounded_box(nx, ny, 0.5, 0.5, 0.44, 0.44, 0.14)
                box_alpha = 1.0 - smoothstep(-aa, aa, d_box)

                grad_t = clamp((nx * 0.35 + ny * 0.65))
                bg_r = lerp(0x1f / 255.0, 0x10 / 255.0, grad_t)
                bg_g = lerp(0x25 / 255.0, 0x12 / 255.0, grad_t)
                bg_b = lerp(0x30 / 255.0, 0x16 / 255.0, grad_t)

                # Subtle inner teal radial glow behind glyph
                glow_d = math.hypot(nx - 0.50, ny - 0.46)
                glow = (1.0 - smoothstep(0.05, 0.48, glow_d)) * 0.18
                bg_r = lerp(bg_r, 0x2d / 255.0, glow)
                bg_g = lerp(bg_g, 0xd4 / 255.0, glow)
                bg_b = lerp(bg_b, 0xbf / 255.0, glow)

                px_col = (bg_r, bg_g, bg_b, box_alpha)

                # Crisp 1px-feel rim border
                border_d = abs(d_box + 0.016) - 0.014
                border_alpha = (1.0 - smoothstep(-aa, aa, border_d)) * box_alpha * 0.85
                px_col = blend(px_col, (0x33 / 255.0, 0x41 / 255.0, 0x55 / 255.0, border_alpha))

                # Signal-teal '>' chevron + '_' terminal prompt / RPC stream bar
                stroke = 0.048
                d_chev_top = sd_segment(nx, ny, 0.25, 0.34, 0.44, 0.50) - stroke
                d_chev_bot = sd_segment(nx, ny, 0.44, 0.50, 0.25, 0.66) - stroke
                d_chev = min(d_chev_top, d_chev_bot)
                chev_alpha = 1.0 - smoothstep(-aa, aa, d_chev)

                # Underscore / RPC pulse bar + node dot
                d_bar = sd_segment(nx, ny, 0.51, 0.64, 0.74, 0.64) - 0.042
                bar_alpha = 1.0 - smoothstep(-aa, aa, d_bar)

                # Top-right signal node + connector stem (evoking gRPC bidirectional node)
                d_stem = sd_segment(nx, ny, 0.56, 0.37, 0.72, 0.37) - 0.026
                d_node = math.hypot(nx - 0.73, ny - 0.37) - 0.068
                node_alpha = 1.0 - smoothstep(-aa, aa, min(d_stem, d_node))

                # Gradient across teal glyph
                gt = clamp((ny - 0.28) / 0.42)
                tr = lerp(0x5e / 255.0, 0x0f / 255.0, gt * 0.65)
                tg = lerp(0xea / 255.0, 0x76 / 255.0, gt * 0.65)
                tb = lerp(0xd4 / 255.0, 0x6e / 255.0, gt * 0.65)

                glyph_alpha = max(chev_alpha, bar_alpha) * box_alpha
                px_col = blend(px_col, (tr, tg, tb, glyph_alpha))
                px_col = blend(px_col, (0x2d / 255.0, 0xd4 / 255.0, 0xbf / 255.0, node_alpha * box_alpha))

                # Bright core inside node dot
                core_alpha = (1.0 - smoothstep(-aa, aa, math.hypot(nx - 0.73, ny - 0.37) - 0.028)) * box_alpha
                px_col = blend(px_col, (0xf0 / 255.0, 0xfd / 255.0, 0xfa / 255.0, core_alpha))

                ar += px_col[0]
                ag += px_col[1]
                ab += px_col[2]
                aa_acc += px_col[3]

            idx = (y * size + x) * 4
            buf[idx] = int(clamp(ar / 4.0) * 255 + 0.5)
            buf[idx + 1] = int(clamp(ag / 4.0) * 255 + 0.5)
            buf[idx + 2] = int(clamp(ab / 4.0) * 255 + 0.5)
            buf[idx + 3] = int(clamp(aa_acc / 4.0) * 255 + 0.5)

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
    print("Generated ProtoForm PNG and ICO icon assets.")


if __name__ == "__main__":
    main()
