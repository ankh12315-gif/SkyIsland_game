"""S3 辅助脚本：把美术规格里的 16x16 字符网格抽成 JSON，避免手工转录 20 张贴图时出错。"""
import re, json, sys, os

SPEC = 'docs/art/BLOCK_TEXTURE_SPEC.md'
OUT = 'tmp/s3work/spec_data.json'

s = open(SPEC, encoding='utf-8').read()
secs = re.split(r'\n### ', s)

ORDER = [
    ('3.1', 'stone', ['main']),
    ('3.2', 'dirt', ['main']),
    ('3.3', 'grass_block', ['top', 'side']),
    ('3.4', 'sand', ['main']),
    ('3.5', 'cobblestone', ['main']),
    ('3.6', 'log', ['side', 'top']),
    ('3.7', 'oak_planks', ['main']),
    ('3.8', 'leaves', ['main']),
    ('3.9', 'glass', ['main']),
    ('3.10', 'torch', ['main']),
    ('3.11', 'wooden_door', ['side', 'top']),
    ('3.12', 'iron_ore', ['main']),
    ('3.13', 'coal_ore', ['main']),
    ('3.14', 'copper_ore', ['main']),
    ('3.15', 'crystal_ore', ['main']),
    ('3.16', 'gold_ore', ['main']),
    ('3.17', 'stone_brick', ['main']),
    ('3.18', 'iron_block', ['main']),
    ('3.19', 'slab', ['top', 'side']),
    ('3.20', 'wheat', ['main']),
]

byname = {}
for sec in secs:
    m = re.match(r'([\d]+\.[\d]+)\s', sec)
    if not m:
        continue
    num = m.group(1)
    grids = re.findall(r'```\n([0-9\n]+)```', sec)
    if not grids:
        continue
    pal = re.search(r'调色板(?:（[^）]*）)? \| (.+?) \|', sec)
    tone0 = re.search(r'tone0 含义 \| (.+?) \|', sec)
    byname[num] = {
        'grids': [[r for r in g.strip().split('\n') if r] for g in grids],
        'palette': pal.group(1).strip() if pal else None,
        'tone0': tone0.group(1).strip() if tone0 else None,
    }

res = {}
problems = []
for num, name, slots in ORDER:
    if num not in byname:
        problems.append('MISSING section ' + num + ' for ' + name)
        continue
    d = byname[num]
    g = d['grids']
    if len(g) != len(slots):
        problems.append('%s: expected %d grids, got %d' % (name, len(slots), len(g)))
        continue
    for grid in g:
        if len(grid) != 16 or any(len(r) != 16 for r in grid):
            problems.append('%s: grid is not 16x16 -> %s' % (
                name, [len(r) for r in grid]))
    res[name] = {'palette': d['palette'], 'tone0': d['tone0'],
                 'grids': dict(zip(slots, g))}

os.makedirs('tmp/s3work', exist_ok=True)
with open(OUT, 'w', encoding='utf-8') as f:
    json.dump(res, f, ensure_ascii=False, indent=1)

for p in problems:
    print('PROBLEM:', p, file=sys.stderr)
print('extracted %d/%d sections -> %s' % (len(res), len(ORDER), OUT))
for k, v in res.items():
    print('  %-14s %s' % (k, {kk: len(vv) for kk, vv in v['grids'].items()}))
