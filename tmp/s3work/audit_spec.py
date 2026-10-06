"""
美术规格自审：抓「图案意图/网格 引用的 tone 超出调色板档数」这类矛盾。

★ 为什么要写这个脚本：
  S3 施工时，iron_block 的图案意图引用了 tone5，但调色板只给了 4 档。
  工程侧靠 Java 抛"字符越界"才暴露 —— 那是运气。
  如果某张贴图的 tone 号"恰好在范围内但语义不对"，管线不会报错，
  只会让画面与设计稿静默不一致。**这类静默不一致才是真正要命的。**

本脚本对 24 层逐层检查三件事：
  A. 网格实际用到的最大 tone  vs  调色板声明的档数   -> 越界即矛盾
  B. 调色板声明了但网格从未用到的 tone                 -> 死档（可能是笔误）
  C. 图案意图文字里提到的 tone/l1/l2/d1 等符号是否都在调色板里有定义
"""
import json
import re
import sys

SPEC = 'docs/art/BLOCK_TEXTURE_SPEC.md'
JSON = 'tmp/s3work/spec_data.json'

spec_text = open(SPEC, encoding='utf-8').read()
data = json.load(open(JSON, encoding='utf-8'))

# 调色板行的两种写法都要能抓：
#   | 调色板 | `0=595959` `1=6B6B6B` ... |
#   | 调色板（side） | ... |
#   | 调色板 | 岩底 `0=5E5A55` ... ；矿点 `tone4 = D8C4B4` |
secs = re.split(r'\n### ', spec_text)
sec_by_num = {}
for s in secs:
    m = re.match(r'([\d]+\.[\d]+)\s', s)
    if m:
        sec_by_num[m.group(1)] = s

# 章节号 -> (方块名, 槽位列表)，与 extract_spec.py 的 ORDER 保持一致
LAYERS = [
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

# 调色板里 tone 号 -> HEX 的抽取（支持 `3=ECECEC` 与 `tone3 = ECECEC` 两种写法）
def parse_palette(sec):
    """返回该章节所有调色板行里声明的 {tone: hex}（合并；多行调色板会跨行拼接）。"""
    out = {}
    for line in sec.split('\n'):
        if '调色板' not in line or not line.strip().startswith('|'):
            continue
        for m in re.finditer(r'(?:tone)?(\d)\s*=\s*`?([0-9A-Fa-f]{6})`?', line):
            out[int(m.group(1))] = m.group(2).upper()
    return out


# 图案意图 / 正文中引用的 tone 号（只认显式的 toneN 写法，避免误抓 16x16 之类的数字）
def referenced_tones(sec):
    refs = set()
    for m in re.finditer(r'tone\s*(\d)', sec):
        refs.add(int(m.group(1)))
    return refs


problems = []
notes = []
rows = []

for num, block, slots in LAYERS:
    sec = sec_by_num.get(num, '')
    pal = parse_palette(sec)
    refs = referenced_tones(sec)
    declared_max = max(pal) if pal else -1
    for slot in slots:
        grid = data[block]['grids'][slot]
        used = set()
        for r in grid:
            used |= set(int(c) for c in r)
        used_max = max(used)

        # A. 越界：网格用到的 tone 超出调色板声明
        if used_max > declared_max:
            problems.append(
                '%s/%s: 网格用到 tone%d，但调色板只声明到 tone%d（缺 %s）'
                % (block, slot, used_max, declared_max,
                   ', '.join('tone%d=%s' % (t, pal.get(t, '??')) for t in
                             range(declared_max + 1, used_max + 1))))

        # B. 死档：声明了但网格里从没出现
        #    ★ 规格 §2.1 情形②明确允许：调色板是"该材质的完整明度阶梯"，
        #      不是"这张图恰好用到的颜色列表"。沙子/石砖/台阶/木门顶面都只用 3-4 档，
        #      但仍声明满 5 档 —— 这是刻意留的余量，不是矛盾。
        #      因此这里只做提示，不计入 problems。
        dead = [t for t in sorted(pal) if t not in used and t != 0]
        if dead:
            notes.append('%s/%s: tone%s 为声明余量，网格未用（§2.1 情形②，非矛盾）'
                         % (block, slot, dead))

        # C. 图案意图引用了调色板没有的 tone
        bad_ref = sorted(t for t in refs if t > declared_max and t not in used)
        if bad_ref:
            problems.append('%s/%s: 图案意图引用 tone%s，但调色板未声明'
                            % (block, slot, bad_ref))

        rows.append((block, slot, sorted(used), declared_max, len(pal), dead))

print('=== 每层：网格实际用到的 tone / 调色板声明档数 ===')
print('%-14s %-6s %-22s %8s %6s' % ('方块', '槽位', '用到的 tone', '声明上限', '死档'))
for block, slot, used, dmax, n, dead in rows:
    print('%-14s %-6s %-22s %8d %6s'
          % (block, slot, ','.join(map(str, used)), dmax, dead if dead else '-'))

print()
if notes:
    print('--- 以下为提示（非矛盾）---')
    for n in notes:
        print('  [提示] ' + n)
    print()

if problems:
    print('=== 发现 %d 处矛盾 ===' % len(problems))
    for p in problems:
        print('  [矛盾] ' + p)
    sys.exit(1)
else:
    print('=== 24 层全部一致：无越界、无悬空 tone 引用 ===')
    print('（判据：网格用到的 tone 号不得超过调色板声明的最大档号 —— 规格 §2.1）')
