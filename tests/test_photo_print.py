# SPDX-License-Identifier: GPL-3.0-or-later
"""canon-photo-print: sheet layout and PDF page size (skipped without PySide6)."""
import os
import subprocess
import sys
import tempfile

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
try:
    import PySide6.QtGui  # noqa: F401
except ImportError:
    print('SKIP photo print (PySide6 not installed)')
    sys.exit(0)

ppd = os.path.join(ROOT, 'out', 'ppd', 'Canon-G3010_series-ivec.ppd')
with tempfile.TemporaryDirectory() as d:
    photos = []
    for name, size in (('land', '600x400'), ('port', '400x600')):
        path = os.path.join(d, name + '.png')
        subprocess.run(['gs', '-q', '-sDEVICE=png16m', f'-g{size}', '-o', path, '-c', '0.2 0.5 0.9 setrgbcolor clippath fill showpage'],
                       check=True)
        photos.append(path)
    ok = True
    for page, count, want_box, want_pages in (('4x6.bl', '1', [0, 0, 288, 432], 2), ('A4', '4', [0, 0, 595, 842], 1)):
        out = os.path.join(d, page + '.pdf')
        r = subprocess.run([sys.executable, '-B', os.path.join(ROOT, 'scripts', 'canon-photo-print'), '--pdf', out, ppd,
                            page, count, *photos], capture_output=True, text=True)
        info = subprocess.run(['gs', '-q', '-dNODISPLAY', '-dNOSAFER', '-c',
                               f'({out}) (r) file runpdfbegin 1 pdfgetpage /MediaBox pget pop == pdfpagecount == quit'],
                              capture_output=True, text=True).stdout.split('\n')
        box = [round(float(v)) for v in info[0].strip('[]').split()] if r.returncode == 0 else None
        pages = int(info[1]) if r.returncode == 0 else 0
        if box != want_box or pages != want_pages:
            print(f'FAIL photo print {page} x{count}: box {box} pages {pages} {r.stderr[-300:]}')
            ok = False
    out = os.path.join(d, 'align.pdf')
    r = subprocess.run([sys.executable, '-B', os.path.join(ROOT, 'scripts', 'canon-photo-print'), '--pdf', out, ppd,
                        '4x6.bl', 'align'], capture_output=True, text=True)
    if r.returncode != 0 or not os.path.getsize(out):
        print('FAIL photo print alignment sheet', r.stderr[-300:])
        ok = False
    if ok:
        print('PASS photo print (borderless 4x6, 4 per A4, alignment sheet)')
    sys.exit(0 if ok else 1)
