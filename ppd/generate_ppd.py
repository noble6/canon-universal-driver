# SPDX-License-Identifier: GPL-3.0-or-later
#!/usr/bin/env python3
import json
import sys
import os
from jinja2 import Environment, FileSystemLoader

def generate_ppds(models_json, template_dir, output_dir):
    with open(models_json, 'r') as f:
        models = json.load(f)

    env = Environment(loader=FileSystemLoader(template_dir))
    template = env.get_template('canon_base.ppd.j2')

    if not os.path.exists(output_dir):
        os.makedirs(output_dir)

    for m in models:
        ppd_content = template.render(
            model=m['model'],
            vendor_id=m['vendor_id'],
            product_id=m['product_id'],
            max_dpi=m['max_dpi'],
            is_color="color" in m['features'],
            has_scan="scan" in m['features'],
            has_wifi="wifi" in m['features']
        )
        safe_name = m['model'].replace(' ', '_')
        output_file = os.path.join(output_dir, f"Canon-{safe_name}.ppd")
        with open(output_file, 'w') as out:
            out.write(ppd_content)
        print(f"Generated {output_file}")

if __name__ == '__main__':
    if len(sys.argv) != 4:
        print("Usage: generate_ppd.py <models.json> <template_dir> <output_dir>")
        sys.exit(1)
    generate_ppds(sys.argv[1], sys.argv[2], sys.argv[3])
