# SPDX-License-Identifier: GPL-3.0-or-later
"""Right-click print helper: file-type routing, without printing."""
import os
import subprocess
import sys
import tempfile

HELPER = os.path.join(os.path.dirname(__file__), '..', 'scripts', 'canon-ivec-print')
with tempfile.TemporaryDirectory() as d:
    open(os.path.join(d, 'a.txt'), 'w').write('hello\n')
    subprocess.run(['gs', '-q', '-sDEVICE=pdfwrite', '-o', os.path.join(d, 'b.pdf'), '-c', 'showpage'], check=True)
    os.mkdir(os.path.join(d, 'folder'))
    r = subprocess.run([sys.executable, '-B', HELPER, '--dry-run', '--printer', 'Q', '--two-sided',
                        os.path.join(d, 'a.txt'), os.path.join(d, 'b.pdf'), os.path.join(d, 'folder')],
                       capture_output=True, text=True)
    out = r.stdout
    ok = ('lp -d Q -t a.txt -o sides=two-sided-long-edge' in out and '-t b.pdf' in out
          and "folder: folders cannot be printed" in out and r.returncode == 1)
    print('PASS print helper' if ok else 'FAIL print helper\n' + out + r.stderr)
    sys.exit(0 if ok else 1)
