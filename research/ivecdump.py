#!/usr/bin/env python3
"""Pretty-print a Canon IVEC print stream: XML commands, binary chunks and PWG page headers."""
import re
import struct
import sys

PWG = [("MediaClass", 0, 64), ("MediaType", 128, 64), ("Duplex", 272, 'u'), ("HWRes", 276, '2u'),
       ("ImagingBBox", 284, '4u'), ("NumCopies", 340, 'u'), ("PageSize", 352, '2u'), ("Tumble", 368, 'u'),
       ("Width", 372, 'u'), ("Height", 376, 'u'), ("BitsPerColor", 384, 'u'), ("BitsPerPixel", 388, 'u'),
       ("BytesPerLine", 392, 'u'), ("ColorSpace", 400, 'u'), ("NumColors", 420, 'u'),
       ("Integer", 452, '8u'), ("RenderingIntent", 1668, 64), ("PageSizeName", 1732, 64)]


def pwg_header(h):
    out = {}
    for name, off, kind in PWG:
        if isinstance(kind, int):
            v = h[off:off + kind].split(b'\0')[0].decode(errors='replace')
        else:
            n = int(kind[:-1]) if len(kind) > 1 else 1
            v = struct.unpack('>%dI' % n, h[off:off + 4 * n])
            v = v[0] if n == 1 else v
        if v not in ('', 0):
            out[name] = v
    return out


def main(path, full=False):
    d = open(path, 'rb').read()
    pos = 0
    for m in re.finditer(rb'<\?xml.*?</cmd>', d, re.S):
        if m.start() > pos:
            chunk = d[pos:m.start()]
            if chunk.startswith(b'RaS2'):
                print('  PWG %d bytes, pages=%d' % (len(chunk), chunk.count(b'PwgRaster')))
                for p in re.finditer(rb'PwgRaster', chunk):
                    print('     ', pwg_header(chunk[p.start():p.start() + 1796]))
            else:
                print('  BIN', len(chunk), chunk[:120])
        x = m.group().decode()
        if full:
            print(x)
        else:
            ops = re.findall(r'<(?:ivec:operation|vcn:ijoperation)>(\w+)', x)
            params = re.findall(r'<(?:ivec|vcn):(\w+)>([^<]*)</', x)
            params = [f'{k}={v}' for k, v in params if k not in ('operation', 'ijoperation', 'jobID')]
            print('/'.join(ops), ' '.join(params))
        pos = m.end()
    if pos < len(d):
        print('  TRAILING', len(d) - pos)


if __name__ == '__main__':
    main(sys.argv[1], '-f' in sys.argv)
