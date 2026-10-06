"""校验 BlockTextures.java 里的 24 张网格是否与美术规格逐字节一致。

这是 S3 的"转录防错"护栏：手工把 20 张 16x16 网格抄进 Java 极易出错，
而出错的表现是"画面略有不同"，几乎不可能靠肉眼归因。这里做逐字符比对。

★S4 修正：本脚本原先<b>直接读 spec_data.json</b>，而那是 extract_spec.py 的
派生产物（不在版本库）。单独跑本脚本会 FileNotFound ——
而"要记得先跑另一步"正是这类守卫失效的最常见方式：
两次都跑的人觉得麻烦就不跑了，一次都没跑的人根本不知道它存在。
因此现在<b>缺文件时自动先跑 extract</b>，让"跑一条命令就能校验"成立。
"""
import json, os, re, subprocess, sys

SPEC_JSON = 'tmp/s3work/spec_data.json'
EXTRACTOR = 'tmp/s3work/extract_spec.py'


def ensure_spec():
    """确保规格 JSON 就绪；缺失（或比规格文档旧）时自动重新提取。"""
    # 规格文档比 JSON 新，说明 JSON 是过期派生产物 —— 重新提取
    if os.path.exists(SPEC_JSON) and os.path.exists('docs/art/BLOCK_TEXTURE_SPEC.md'):
        if os.path.getmtime(SPEC_JSON) >= os.path.getmtime('docs/art/BLOCK_TEXTURE_SPEC.md'):
            return
    print('规格数据缺失或已过期 -> 自动运行 extract_spec.py ...', file=sys.stderr)
    subprocess.run([sys.executable, EXTRACTOR], check=True)
    if not os.path.exists(SPEC_JSON):
        raise SystemExit('extract_spec.py 未产出 %s' % SPEC_JSON)


ensure_spec()
spec = json.load(open(SPEC_JSON, encoding='utf-8'))
java = open('src/main/java/com/skyisland/render/mesh/BlockTextures.java', encoding='utf-8').read()

# 从 Java 源里抽出每个常量的 16 行网格
java_grids = {}
for m in re.finditer(r'private static final String\[\]\s+(\w+)\s*=\s*\{(.*?)\};', java, re.S):
    name = m.group(1)
    rows = re.findall(r'"([0-9]+)"', m.group(2))
    java_grids[name] = rows

# 常量名 -> (spec 方块, 槽位)
MAP = {
    'STONE': ('stone', 'main'),
    'DIRT': ('dirt', 'main'),
    'GRASS_TOP': ('grass_block', 'top'),
    'GRASS_SIDE': ('grass_block', 'side'),
    'SAND': ('sand', 'main'),
    'COBBLESTONE': ('cobblestone', 'main'),
    'LOG_SIDE': ('log', 'side'),
    'LOG_TOP': ('log', 'top'),
    'OAK_PLANKS': ('oak_planks', 'main'),
    'LEAVES': ('leaves', 'main'),
    'GLASS': ('glass', 'main'),
    'TORCH_FULL': ('torch', 'main'),
    'DOOR_SIDE': ('wooden_door', 'side'),
    'DOOR_TOP': ('wooden_door', 'top'),
    'IRON_ORE': ('iron_ore', 'main'),
    'COAL_ORE': ('coal_ore', 'main'),
    'COPPER_ORE': ('copper_ore', 'main'),
    'CRYSTAL_ORE': ('crystal_ore', 'main'),
    'GOLD_ORE': ('gold_ore', 'main'),
    'STONE_BRICK': ('stone_brick', 'main'),
    'IRON_BLOCK': ('iron_block', 'main'),
    'WHEAT': ('wheat', 'main'),
    'SLAB_TOP': ('slab', 'top'),
    'SLAB_SIDE': ('slab', 'side'),
}

bad = 0
for const, (block, slot) in MAP.items():
    if const not in java_grids:
        print('MISSING in java:', const); bad += 1; continue
    jg = java_grids[const]
    sg = spec[block]['grids'][slot]
    if len(jg) != 16:
        print('BAD ROWCOUNT %s: %d' % (const, len(jg))); bad += 1; continue
    for i, (a, b) in enumerate(zip(jg, sg)):
        if a != b:
            print('MISMATCH %s row %d:\n  java: %s\n  spec: %s' % (const, i, a, b))
            bad += 1
            break

extra = set(java_grids) - set(MAP)
if extra:
    print('EXTRA constants in java (ok if DIRT_GRASS_SIDE alias):', sorted(extra))

print('checked %d grids, %d problems' % (len(MAP), bad))
sys.exit(1 if bad else 0)
