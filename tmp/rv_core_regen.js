// 反向验证：ResourceCoreRegen 的 5 条关键行为，逐条注入 → 确认精确变红 → 逐字节还原。
//
// 纪律（project handoff §四）：每条新断言都要做反向验证。
// 失败的标准长相是"断言在失败场景下仍能通过"。
//
// 用法：node tmp/rv_core_regen.js
'use strict';

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { spawnSync } = require('child_process');

const PROJ = process.env.SKYISLAND_PROJ || 'F:/minecraftspace';
const TARGET = path.join(PROJ, 'src', 'main', 'java', 'com', 'skyisland', 'world',
  'ResourceCoreRegen.java');

const TEST = 'ResourceCoreRegenTest';

/** 注入列表：每一项都必须让 *某条* 断言精确变红。 */
const INJECTIONS = [
  {
    name: 'A · 周期减半（180 -> 90 秒，违反 PRD 4.6 的速率）',
    find: 'new Profile(IslandKind.STONE, 180, 1, 20),',
    repl: 'new Profile(IslandKind.STONE, 90, 1, 20),',
    expect: /onePeriodProducesExactlyOneOre|nothingGrowsBeforeThePeriodElapses/,
  },
  {
    name: 'B · 不检查区块是否已加载（未加载也照放）',
    find: 'if (world.chunkAt(Coords.toChunk(x), Coords.toChunk(z)) == null) {\n                        skippedChunkNotLoaded++;\n                        continue;\n                    }',
    repl: '// RV-INJECT B: 区块检查被摘掉',
    expect: /unloadedChunksAreSkipped/,
  },
  {
    name: 'C · 放宽刷新范围（y 偏移上限 1 -> 3，违反 PRD 4.6）',
    find: 'public static final int CORE_MAX_DY = 1;',
    repl: 'public static final int CORE_MAX_DY = 3;',
    // ★ 归因要覆盖两条范围判据里的**任意一条**。
    //   上一版只写了 everyGrown…|regenNeverGoes…，实测这条注入先撞红了
    //   `occupiedCellsAreSkippedAndNeverOverwritten` —— 因为放宽后核心正上方
    //   那格不再是范围最高层，夹具的"木板占据最高层"前提就不成立了。
    //   症状看着像"注入改错了东西"，真因是：一条注入可能有多条断言变红，
    //   而**先红的那条**取决于测试的执行顺序。⇒ 判据必须列全部相关断言。
    expect: /everyGrownOreIsInsideThePrdSpawnRange|regenNeverGoesOutsideTheDeclaredRange|occupiedCellsAreSkippedAndNeverOverwritten/,
  },
  {
    name: 'D · 覆盖玩家方块（只判 isAir，不再要求原格是石头）',
    find: 'if (current != BlockRegistry.stone().runtimeId()) {',
    repl: 'if (false) {',
    expect: /occupiedCellsAreSkippedAndNeverOverwritten/,
  },
  {
    name: 'E · 做离线补算（每次 tick 直接按 dt 推算而不是等周期）',
    find: 'while (acc >= profile.periodSeconds() && guard++ < 64) {',
    repl: 'acc += dt * 1000.0; while (acc >= profile.periodSeconds() && guard++ < 64) {',
    expect: /noOfflineCatchUp|onePeriodProducesExactlyOneOre|aHugeDtDoesNotBurstManyOres/,
  },
  {
    // ★ 这条守的是"perRun 这个规格参数真的驱动循环"。
    //   它曾经是 Profile 的一个字段而 runOnce 里写死 return —— 字段从不被读，
    //   于是"把它调成 2"会静默无效，而 PRD 4.6 恰恰把它列成参数表的一列。
    name: 'F · perRun 不驱动循环（写死单次只放 1 格）',
    find: 'final int wanted = profileOf(core.islandKind()).perRun();',
    repl: 'final int wanted = 1;   // RV-INJECT F: 忽略 perRun',
    expect: /thePerRunParameterActuallyDrivesTheLoop/,
  },
];

function sha(p) {
  return crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
}

function runTest() {
  const r = spawnSync('node', [path.join(PROJ, 'tmp', 'mvn_test_one.js'), TEST], {
    cwd: PROJ, encoding: 'utf8',
  });
  const out = (r.stdout || '') + (r.stderr || '');
  return { code: r.status, out };
}

const original = fs.readFileSync(TARGET);
const originalSha = sha(TARGET);

const base = runTest();
console.log('[rv] baseline exit=' + base.code + ' (expect 0)');
if (base.code !== 0) {
  console.error('[rv] baseline is red — fix that first, never inject into a red tree.');
  console.error(base.out.split(/\r?\n/).filter(l => /FAIL|expected/.test(l)).slice(0, 8).join('\n'));
  process.exit(2);
}

const results = [];

for (const inj of INJECTIONS) {
  const text = original.toString('utf8');
  if (!text.includes(inj.find)) {
    console.error('[rv] SKIP  ' + inj.name + '  —— 找不到待注入的源码片段');
    results.push({ name: inj.name, ok: false, why: 'pattern not found' });
    continue;
  }
  fs.writeFileSync(TARGET, text.split(inj.find).join(inj.repl), 'utf8');

  let r;
  try {
    r = runTest();
  } finally {
    fs.writeFileSync(TARGET, original);
  }

  const restored = sha(TARGET) === originalSha;
  const turnedRed = r.code !== 0;
  const blamed = inj.expect.test(r.out);
  const markerGone = !/RV-INJECT/.test(fs.readFileSync(TARGET, 'utf8'));

  const ok = turnedRed && blamed && restored && markerGone;
  console.log('[rv] ' + (ok ? 'OK   ' : 'FAIL ') + inj.name);
  console.log('       exit=' + r.code + ' 变红=' + turnedRed
    + ' 归因命中=' + blamed + ' 还原=' + restored + ' 残渣清零=' + markerGone);
  if (!blamed && turnedRed) {
    const lines = r.out.split(/\r?\n/).filter(l => /FAIL|expected:/.test(l)).slice(0, 4);
    console.log('       实际变红的断言（与预期不符）：');
    for (const l of lines) console.log('         ' + l.trim());
  }
  results.push({ name: inj.name, ok, why: '' });
}

const bad = results.filter(x => !x.ok);
console.log('');
console.log('[rv] ' + (bad.length === 0
  ? `OK — ${results.length} 条断线全部被精确捕获并逐字节还原。`
  : `FAIL — ${bad.length}/${results.length} 条不合格：`
    + bad.map(b => b.name + (b.why ? ' (' + b.why + ')' : '')).join('; ')));
process.exit(bad.length === 0 ? 0 : 1);