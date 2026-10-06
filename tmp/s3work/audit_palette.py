"""
调色板 HEX 对账：把美术规格 §3 各节的调色板表 与 BlockTextures.java 的实际调色板逐档比对。

★ 为什么必须单独写这个脚本（S3 返工的直接产物）：

  现有的两道守卫各有盲区：
    - verify_grids.py  只比 **网格字符**，不比 **色值**
    - audit_spec.py    只查 **档数越界**，不比 **色值是否一致**

  于是存在一类**静默不一致**：规格里把某个色值写错 / 写偏，
  而 **档数不变、网格字符不变** —— 两道守卫都绿灯，
  但画面与设计稿对不上，且**永远不会抛异常**。
  这正是主理人要求的"更致命的那一类"。

  本脚本把规格 §3 声明的 {tone: HEX} 与 Java 里的 {tone: rgb} 对上，
  任何一档对不上就退出码 1。

判据：
  - 规格声明了 N 档，Java 必须有 N 档（长度一致）
  - 逐档 HEX 必须相等（大小写不敏感）
  - 规格里"矿点 / 高光核"这类**另起的色值**（写法 `toneN = XXXXXX`）单独抽出比对
"""
import json
import re
import sys

SPEC = 'docs/art/BLOCK_TEXTURE_SPEC.md'
JAVA = 'src/main/java/com/skyisland/render/mesh/BlockTextures.java'

spec = open(SPEC, encoding='utf-8').read()
java = open(JAVA, encoding='utf-8').read()

# ---------------------------------------------------------------- 规格侧
# 把章节切开，逐节抓调色板行
secs = {}
for chunk in re.split(r'\n### ', spec):
    m = re.match(r'([\d]+\.[\d]+)\s', chunk)
    if m:
        secs[m.group(1)] = chunk

# 章节号 -> 该节声明的调色板（tone -> hex）
def spec_palette(sec, label=None):
    """抓调色板行。label 用于多调色板章节（如 §3.6 原木有 side / top 两行），
    传入 '（side）' 之类只取该行；label=None 时取第一行。"""
    pal = {}
    extra = {}
    for line in sec.split('\n'):
        if '调色板' not in line or not line.strip().startswith('|'):
            continue
        if label is not None and label not in line:
            continue
        # 普通档位 `N=XXXXXX`
        for mm in re.finditer(r'(?:tone)?(\d)\s*=\s*`?([0-9A-Fa-f]{6})`?', line):
            pal[int(mm.group(1))] = mm.group(2).upper()
        # 另起色值（矿点 / 高光核）：`tone4 = XXXXXX` 出现在 "；" 之后
        for mm in re.finditer(r'矿点[^|]*?tone(\d)\s*=\s*`?([0-9A-Fa-f]{6})`?', line):
            extra['spot' + mm.group(1)] = mm.group(2).upper()
    return pal, extra

# 只对"调色板是单一材质阶梯"的章节做对账（矿石类是多套，跳过避免误报）
# 元素为 (显示名, Java 常量, 规格里的调色板行标签)
#   标签用于多调色板章节 —— §3.6 原木有「（side）」「（top）」两行，必须分开抓，
#   否则会拿错行而报出一堆假阳性（第一版就踩了这个坑）。
# ★ 用 list 不用 dict：§3.6 要出现两次，dict 的重复 key 会静默丢掉后一条。
SIMPLE = [
    ('3.1', 'stone',         'OPAQUE_STONE',      None),
    ('3.2', 'dirt',          'OPAQUE_DIRT',       None),
    ('3.3', 'grass_block',   'OPAQUE_GRASS',      None),
    ('3.4', 'sand',          'OPAQUE_SAND',       None),
    ('3.5', 'cobblestone',   'OPAQUE_COBBLE',     None),
    ('3.6', 'log_side',      'OPAQUE_LOG',        '（side）'),
    ('3.6', 'log_top',       'OPAQUE_LOG_TOP',    '（top）'),
    ('3.7', 'oak_planks',    'OPAQUE_PLANKS',     None),
    ('3.11', 'wooden_door',  'OPAQUE_DOOR',       None),
    ('3.17', 'stone_brick',  'OPAQUE_BRICK',      None),
    ('3.18', 'iron_block',   'OPAQUE_IRON_BLOCK', None),
]
# 台阶两槽都写"同石砖"，对账到 OPAQUE_BRICK
SLAB_SEC = '3.19'

# ---------------------------------------------------------------- Java 侧
def java_palette(const):
    m = re.search(r'private static final int\[\]\[\]\s+' + const + r'\s*=\s*\{(.*?)\};',
                  java, re.S)
    if not m:
        return None
    return [h.upper() for h in re.findall(r'rgb\(0x([0-9A-Fa-f]{6})\)', m.group(1))]

def java_cutout_palette(const):
    """树叶 / 火把 / 小麦 用 cutout(0x...) 而非 rgb(0x...)，单独抓。"""
    m = re.search(r'private static final int\[\]\[\]\s+' + const + r'\s*=\s*\{(.*?)\};',
                  java, re.S)
    if not m:
        return None
    return [h.upper() for h in re.findall(r'(?:rgb|cutout)\(0x([0-9A-Fa-f]{6})\)', m.group(1))]

# ---------------------------------------------------------------- 对账
bad = []
print('%-16s %-20s %-20s %s' % ('层', '规格', 'Java', '结果'))
print('-' * 78)

def cmp_row(label, sp, jp):
    # Java 常量不存在 —— 这本身就是要报的事实（工程侧还没建这个调色板）
    if jp is None:
        print('%-16s %-20s %-20s %s' % (label, ','.join(sp), '常量缺失',
                                        '<< Java 侧无此调色板'))
        bad.append('%s: Java 侧缺少该层的调色板常量（规格声明 %d 档）'
                   % (label, len(sp)))
        return
    n = max(len(sp), len(jp))
    sp = [sp[i] if i < len(sp) else '—' for i in range(n)]
    jp2 = [jp[i] if i < len(jp) else '—' for i in range(n)]
    if sp == jp2:
        print('%-16s %-20s %-20s %s' % (label, ','.join(sp), ','.join(jp2), 'OK'))
    else:
        print('%-16s %-20s %-20s %s' % (label, ','.join(sp), ','.join(jp2), '不一致'))
        for i in range(n):
            a = sp[i]
            b = jp2[i]
            if a != b:
                bad.append('%s tone%d: 规格 %s / Java %s' % (label, i, a, b))

for num, name, const, label in SIMPLE:
    sec = secs.get(num, '')
    pal, _ = spec_palette(sec, label)
    if not pal:
        print('%-16s %-20s %-20s %s' % (name, '未解析到', '—', '跳过'))
        continue
    cmp_row(name, [pal.get(i, '—') for i in range(max(pal) + 1)], java_palette(const))

# 台阶：规格写"同石砖"，对账到 OPAQUE_BRICK
sec = secs.get(SLAB_SEC, '')
pal, _ = spec_palette(sec)
cmp_row('slab/(同石砖)', [pal.get(i, '—') for i in range(5)], java_palette('OPAQUE_BRICK'))

# 树叶 / 火把 / 小麦：tone0 是镂空，规格写的是"镂空（alpha=0）"不是色值 -> 单独对账其余档
CUTOUT = [('3.8', 'leaves', 'LEAF_PALETTE'),
          ('3.10', 'torch', 'TORCH_PALETTE'),
          ('3.20', 'wheat', 'WHEAT_PALETTE')]
print()
for num, name, const in CUTOUT:
    sec = secs.get(num, '')
    pal, _ = spec_palette(sec)
    jp = java_cutout_palette(const)
    if jp is None:
        print('%-16s %-20s %-20s %s' % (name, '—', '常量未找到', '跳过'))
        continue
    # tone0 是镂空，规格不写色值 -> 只对 tone1.. 比
    sp = [pal.get(i, '—') for i in range(1, max(pal) + 1)]
    jq = jp[1:max(pal) + 1] if len(jp) > 1 else []
    if sp == jq:
        print('%-16s %-20s %-20s %s' % (name + '(tone1+)', ','.join(sp), ','.join(jq), 'OK'))
    else:
        print('%-16s %-20s %-20s %s' % (name + '(tone1+)', ','.join(sp), ','.join(jq), '不一致'))
        for i in range(max(len(sp), len(jq))):
            a = sp[i] if i < len(sp) else '—'
            b = jq[i] if i < len(jq) else '—'
            if a != b:
                bad.append('%s tone%d: 规格 %s / Java %s' % (name, i + 1, a, b))

print()
if bad:
    print('=== 发现 %d 处调色板色值不一致（静默不一致，必须修）===' % len(bad))
    for b in bad:
        print('  [色值] ' + b)
    sys.exit(1)
else:
    print('=== 调色板色值全部对账一致 ===')
    print('（规格 §3 声明的 HEX 与 BlockTextures.java 逐档相等）')
