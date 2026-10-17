#!/usr/bin/env python3
"""Decode the PWG pages inside an IVEC stream to PNG.

    pwg2png.py job.ivec out-prefix [--paper W_PX H_PX LEFT_PX TOP_PX]

--paper crops each page to where the paper sits inside the printer's
print area (borderless overhang), i.e. what actually lands on the sheet.
"""
import re
import struct
import sys
import zlib


def png(path, w, h, rows):
    raw = b''.join(b'\0' + r for r in rows)
    def chunk(t, d):
        c = struct.pack('>I', len(d)) + t + d
        return c + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    open(path, 'wb').write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0))
                           + chunk(b'IDAT', zlib.compress(raw, 6)) + chunk(b'IEND', b''))


def decode(blob):
    h = blob[4:4 + 1796]
    u = lambda o: struct.unpack('>I', h[o:o + 4])[0]
    w, ht, bpp = u(372), u(376), u(388) // 8
    pos, rows = 4 + 1796, []
    while len(rows) < ht:
        rep = blob[pos] + 1
        pos += 1
        line = bytearray()
        while len(line) < w * bpp:
            n = blob[pos]
            pos += 1
            if n == 128:
                line += b'\xff' * (w * bpp - len(line))
            elif n < 128:
                line += blob[pos:pos + bpp] * (n + 1)
                pos += bpp
            else:
                cnt = 257 - n
                line += blob[pos:pos + cnt * bpp]
                pos += cnt * bpp
        if bpp == 1:
            line = bytes(b for v in line for b in (v, v, v))
        rows += [bytes(line)] * rep
    return w, ht, rows[:ht]


def main():
    data = open(sys.argv[1], 'rb').read()
    crop = [int(v) for v in sys.argv[sys.argv.index('--paper') + 1:][:4]] if '--paper' in sys.argv else None
    for i, m in enumerate(re.finditer(rb'<ivec:datasize>(\d+)</ivec:datasize>.*?</cmd>', data, re.S)):
        blob = data[m.end():m.end() + int(m.group(1))]
        w, h, rows = decode(blob)
        if crop:
            cw, ch, cx, cy = crop
            rows = [r[cx * 3:(cx + cw) * 3] for r in rows[cy:cy + ch]]
            w, h = cw, len(rows)
        out = f'{sys.argv[2]}-{i + 1}.png'
        png(out, w, h, rows)
        print(out, w, h)


if __name__ == '__main__':
    main()
