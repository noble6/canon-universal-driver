# SPDX-License-Identifier: GPL-3.0-or-later
"""Checks which CUPS printer states make canon-ivec-notify pop up."""
import importlib.machinery
import importlib.util
import os
import sys

path = os.path.join(os.path.dirname(__file__), '..', 'scripts', 'canon-ivec-notify')
loader = importlib.machinery.SourceFileLoader('notify', path)
spec = importlib.util.spec_from_loader('notify', loader)
n = importlib.util.module_from_spec(spec)
loader.exec_module(n)

cases = [
    ({'printer-state': 'processing', 'printer-state-reasons': 'media-empty-error'}, ['media-empty']),
    ({'printer-state': 'processing', 'printer-state-reasons': 'media-jam-error,other-warning'}, ['media-jam', 'other']),
    ({'printer-state': 'idle', 'printer-state-reasons': 'none'}, []),
    ({'printer-state': 'processing', 'printer-state-reasons': 'marker-supply-low-warning'}, []),
    ({'printer-state': 'stopped', 'printer-state-reasons': 'paused'}, ['paused']),
    ({'printer-state': 'stopped', 'printer-state-reasons': 'none'}, ['paused']),
    ({'printer-state': 'processing', 'printer-state-reasons': 'door-open-report'}, []),
]
bad = 0
for st, want in cases:
    got = n.problems(st)
    if got != want:
        print(f'FAIL notify {st} -> {got}, want {want}')
        bad += 1
if not bad:
    print('PASS notifier state mapping')
sys.exit(1 if bad else 0)
