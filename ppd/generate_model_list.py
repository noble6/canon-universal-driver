#!/usr/bin/env python3
"""Write docs/SUPPORTED_MODELS.md from models/ivec_models.json."""
import json
import re
import sys
from collections import defaultdict

SERIES = [
    ('G', 'PIXMA G (MegaTank / ink tank)', r'^G\d'),
    ('GM', 'PIXMA GM (mono ink tank)', r'^GM'),
    ('GX', 'MAXIFY GX (ink tank)', r'^GX'),
    ('E', 'PIXMA E (Ink Efficient)', r'^E\d'),
    ('TS', 'PIXMA TS', r'^TS'),
    ('TR', 'PIXMA TR', r'^TR'),
    ('MG', 'PIXMA MG', r'^MG'),
    ('MX', 'PIXMA MX', r'^MX'),
    ('MB', 'MAXIFY MB', r'^MB'),
    ('iB', 'MAXIFY iB', r'^iB'),
    ('XK', 'PIXMA XK', r'^XK'),
    ('iP', 'PIXMA iP', r'^iP'),
    ('other', 'Other', r''),
]


def main(db, out):
    models = [m for m in json.load(open(db))['models'] if m['id'] != 'generic']
    groups = defaultdict(list)
    for m in models:
        name = m['model'].replace(' series', '')
        for key, _, pat in SERIES:
            if re.match(pat, name):
                groups[key].append((name, m))
                break
    lines = [
        '# Supported printers',
        '',
        f'{len(models)} Canon models have a tested profile. Every listed model was checked '
        'byte for byte against Canon\'s own Linux driver (see `research/RESULTS.md`).',
        '',
        'Names are the model families as the printer reports them; a "G3010 series" profile also covers '
        'the regional variants Canon groups under it (for the G3010 series: G3410, G3411, G3415, G3510, G3515).',
        '',
        'Not listed? If `canonctl detect` shows `IVEC` in the device ID, the generic profile will work. '
        'Please open a "new model" issue so it can get a full profile.',
        '',
        '| Columns | Meaning |',
        '|---|---|',
        '| Duplex | automatic 2-sided printing |',
        '| Borderless | at least one borderless paper size |',
        '| Maintenance | operations offered by `canonctl` |',
        '',
    ]
    for key, title, _ in SERIES:
        if not groups[key]:
            continue
        lines += [f'## {title}', '', '| Model | Duplex | Borderless | Paper sizes | Maintenance |', '|---|---|---|---|---|']
        for name, m in sorted(groups[key], key=lambda x: [int(t) if t.isdigit() else t for t in re.split(r'(\d+)', x[0])]):
            bl = 'yes' if any(s['name'].endswith('.bl') for s in m['sizes']) else ''
            nsz = len({s['pwg'] for s in m['sizes']})
            ops = []
            mt = m['maint']
            if m['maint_style'] == 'bjl':
                ops = ['clean', 'nozzle']
            else:
                c = mt.get('Cleaning', {})
                ops += [x for x, k in (('clean', 'regular'), ('deep-clean', 'deep'), ('flush', 'choke')) if k in c]
                ops += [t for t in ('roller', 'platen') if t in mt.get('RollerCleaning', {})]
                tp = mt.get('TestPrint', {})
                if 'nozzle_check' in tp:
                    ops.append('nozzle')
                if any(t.endswith('registration') or t == 'regi_check' for t in tp):
                    ops.append('align')
            lines.append(f'| {name} | {"yes" if m["duplex"] else ""} | {bl} | {nsz} | {", ".join(ops)} |')
        lines.append('')
    open(out, 'w').write('\n'.join(lines))
    print(len(models), 'models ->', out)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
