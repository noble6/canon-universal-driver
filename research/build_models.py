#!/usr/bin/env python3
"""Build models/ivec_models.json from the reference harness output.

Inputs (all under $W, default ~/.cache/canon-ivec-re):
  cnijfilter2/pkg/.../model/*.ppd  Canon PPDs (page sizes, margins, media list)
  maps/<model>.txt                 mapprobe: PPD name -> IVEC keyword
  caps/<model>.txt                 capdump: IEEE-1284 device ID + capability XML
  refall/<model>.bin               reference job (protocol framing, colour mode)

Only interoperability facts are kept: names, sizes, margins and the
keywords the printer expects.
"""
import glob
import json
import os
import re
import sys

W = os.environ.get('W', os.path.expanduser('~/.cache/canon-ivec-re'))
PPDS = os.path.join(W, 'cnijfilter2/pkg/cnijfilter2/usr/share/cups/model')


def parse_ppd(path):
    sizes, media, ia, area = {}, [], {}, {}
    info = {'duplex': False, 'color': True, 'resolution': 600, 'optimization': False}
    for line in open(path, encoding='utf-8', errors='replace'):
        line = line.rstrip('\n')
        if re.match(r'\*[a-z]{2}(_[A-Z]{2})?\.', line):      # translations
            continue
        m = re.match(r'\*PageSize ([^/:]+)/([^:]+): ".*PageSize\[([\d.]+) ([\d.]+)\]', line)
        if m:
            sizes[m.group(1)] = {'name': m.group(1), 'text': m.group(2).strip(),
                                 'w': float(m.group(3)), 'h': float(m.group(4))}
            continue
        m = re.match(r'\*ImageableArea ([^:]+): "([^"]+)"', line)
        if m:
            ia[m.group(1)] = [float(v) for v in m.group(2).split()]
            continue
        m = re.match(r'\*%CNSizeToPrintArea (\S+) (\d+) (\d+)', line)
        if m:
            area[m.group(1)] = [int(m.group(2)), int(m.group(3))]
            continue
        m = re.match(r'\*MediaType ([^/:]+)/([^:]+):', line)
        if m:
            media.append({'name': m.group(1), 'text': m.group(2).strip()})
            continue
        m = re.match(r'\*(Default\w+): (\S+)', line)
        if m:
            info[m.group(1)] = m.group(2)
        if line.startswith('*Duplex DuplexNoTumble'):
            info['duplex'] = True
        if line.startswith('*ColorDevice:'):
            info['color'] = 'True' in line
        if '--optimization True' in line:
            info['optimization'] = True
        m = re.match(r'\*NickName: "([^"]+)"', line)
        if m:
            info['nickname'] = re.sub(r'\s*Ver\.[\d.]+$', '', m.group(1))
        m = re.match(r'\*ShortNickName: "([^"]+)"', line)
        if m:
            info['short'] = m.group(1)
    res = re.match(r'(\d+)', info.get('DefaultResolution', '600'))
    info['resolution'] = int(res.group(1)) if res else 600
    return sizes, media, ia, area, info


def parse_map(path):
    out = {'media': {}, 'size': {}}
    for line in open(path):
        kind, name, value = line.split()
        out[kind][name.lower()] = value
    return out


def parse_maint(caps):
    """{operation: {type: [inkgroups]}} from the maintenance capability."""
    sec = caps.split('CNIJ-IVEC-CAPABILITY-MAINTENANCE', 1)[-1]
    out = {}
    for op, body in re.findall(r'<ivec:([A-Z]\w+)>(.*?)</ivec:\1>', sec, re.S):
        types = re.findall(r'<ivec:type value="([^"]+)">(.*?)</ivec:type>', body, re.S)
        if not types:
            continue
        out[op] = {}
        for t, tb in types:
            g = re.search(r'<ivec:inkgroup>([^<]*)<', tb)
            out[op][t] = g.group(1).split(',') if g else []
    return out, '<ivec:datetime/>' in sec, 'host_environment' in sec


def parse_ref(path):
    d = open(path, 'rb').read()
    return {
        'protocol': 1 if b'<ivec:jobID>00000001' in d else 2,
        'datetime': b'SetJobConfiguration' in d,
        'monochrome': b'<ivec:printcolormode>monochrome' in d,
        'ok': b'EndJob' in d and b'PWGRaster' in d,
    }


def generic(models):
    """Conservative profile for IVEC printers not in the list: the common
    office sizes with the smallest printable area any known model uses."""
    sizes = {}
    for m in models:
        for s in m['sizes']:
            if s['name'] in ('A4', 'Letter', 'A5', 'legal', '4x6', '5x7in'):
                cur = sizes.get(s['name'])
                if not cur or s['print'][0] * s['print'][1] < cur['print'][0] * cur['print'][1]:
                    sizes[s['name']] = s
    return {
        'id': 'generic', 'model': 'IVEC Generic', 'nickname': 'Canon IVEC Generic',
        'device_id': 'MFG:Canon;CMD:IVEC;MDL:IVEC Generic;CLS:PRINTER;',
        'protocol': 2, 'datetime': True, 'mono_only': False, 'default_gray': False, 'color': True, 'duplex': False, 'optimization': False,
        'resolution': 600, 'default_size': 'A4', 'default_media': 'plain',
        'maint_style': 'ivec', 'maint_datetime': True, 'maint_hostenv': True,
        'maint': {'Cleaning': {'regular': ['all']}, 'TestPrint': {'nozzle_check': []}},
        'sizes': [sizes[k] for k in ('A4', 'Letter', 'A5', 'legal', '4x6', '5x7in') if k in sizes],
        'media': [{'name': 'plain', 'text': 'Plain Paper', 'ivec': 'stationery'},
                  {'name': 'photopaper', 'text': 'Photo Paper', 'ivec': 'photographic'}],
    }


def main(out_path):
    models = []
    for ppd in sorted(glob.glob(os.path.join(PPDS, '*.ppd'))):
        key = os.path.basename(ppd)[:-4]
        sizes, media, ia, area, info = parse_ppd(ppd)
        mpath = os.path.join(W, 'maps', key + '.txt')
        if not os.path.exists(mpath) or not os.path.exists(os.path.join(W, 'caps', key + '.txt')):
            print('skip', key, '(not probed yet)', file=sys.stderr)
            continue
        mp = parse_map(mpath)
        caps = open(os.path.join(W, 'caps', key + '.txt'), errors='replace').read()
        ref = parse_ref(os.path.join(W, 'refall', key + '.bin'))
        devid = re.search(r'(MFG:[^\n]*?;)\s*\n', caps)
        devid = devid.group(1) if devid else ''
        mdl = re.search(r'MDL:([^;]+);', devid)

        out_sizes = []
        for name, s in sizes.items():
            pwg = mp['size'].get(name.lower())
            if not pwg or name not in area or name not in ia:
                continue
            out_sizes.append(dict(s, ia=ia[name], print=area[name], pwg=pwg))
        maint, maint_dt, maint_host = parse_maint(caps)
        ppd_text = open(ppd, errors='replace').read()
        out_media = [dict(m, ivec=mp['media'][m['name'].lower()])
                     for m in media if m['name'].lower() in mp['media']]

        short = info.get('short', '')
        if mdl and short and short != mdl.group(1):
            # Canon's data has a few copy-paste device IDs (E200 carries TS200's)
            devid = devid.replace('MDL:%s;' % mdl.group(1), 'MDL:%s;' % short) \
                         .replace('DES:Canon %s;' % mdl.group(1), 'DES:Canon %s;' % short)
            mdl = re.search(r'MDL:([^;]+);', devid)
        if not ref['ok']:
            print('skip', key, '(reference job failed)', file=sys.stderr)
            continue
        models.append({
            'id': key,
            'model': mdl.group(1) if mdl else info.get('short', key),
            'nickname': info.get('nickname', key),
            'device_id': devid,
            'protocol': ref['protocol'],
            'datetime': ref['datetime'],
            'mono_only': ref['monochrome'] and info.get('DefaultCNGrayscale') != 'True',
            'default_gray': info.get('DefaultCNGrayscale') == 'True',
            'color': info['color'],
            'duplex': info['duplex'],
            'optimization': info['optimization'],
            'resolution': info['resolution'],
            'default_size': info.get('DefaultPageSize', 'A4'),
            'default_media': info.get('DefaultMediaType', 'plain'),
            'maint_style': 'bjl' if 'cmdtocanonij2' in ppd_text else 'ivec',
            'maint_datetime': maint_dt,
            'maint_hostenv': maint_host,
            'maint': maint,
            'sizes': out_sizes,
            'media': out_media,
        })
    models.append(generic(models))
    json.dump({'source': 'cnijfilter2 6.81 capability data', 'models': models},
              open(out_path, 'w'), indent=0, separators=(',', ':'))
    print(len(models), 'models ->', out_path)


if __name__ == '__main__':
    main(sys.argv[1])
