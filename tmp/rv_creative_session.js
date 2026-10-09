/*
 * 反向验证：CreativeSessionWiringTest 的关键断线，逐条注入 → 确认精确变红 → 逐字节还原。
 *
 * 纪律（project handoff §四）：每条新断言都要做反向验证。
 * 失败的标准长相是"断言在失败场景下仍能通过"。
 *
 * 这份守卫守的是**安全性**（创造会话不得写进存档、不得给创造存档退出路径），
 * 而它的判据全是"源码里没有某串字"—— 那是最容易在失败场景下仍能通过的一类：
 * 只要有人把接线写在另一个文件、或者换个字段名，全部 6 条都照样绿。
 * ⇒ 注入必须真的落到"守卫声称在守的那个东西上"。
 *
 * 用法：node tmp/rv_creative_session.js
 */
'use strict';

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { spawnSync } = require('child_process');

const PROJ = process.env.SKYISLAND_PROJ || 'F:/minecraftspace';
const TEST = 'CreativeSessionWiringTest';

const FILES = {
  player: path.join(PROJ, 'src', 'main', 'java', 'com', 'skyisland', 'player', 'Player.java'),
  game: path.join(PROJ, 'src', 'main', 'java', 'com', 'skyisland', 'game', 'SkyIslandGame.java'),
  save: path.join(PROJ, 'src', 'main', 'java', 'com', 'skyisland', 'save', 'SaveManager.java'),
  loc: path.join(PROJ, 'src', 'main', 'java', 'com', 'skyisland', 'ui', 'Localization.java'),
  renderer: path.join(PROJ, 'src', 'main', 'java', 'com', 'skyisland', 'render', 'ui', 'HudRenderer.java'),
};

/** 每条注入：改哪个文件 / 找不到就跳过要算失败 / 期望变红的断言。 */
const INJECTIONS = [
  {
    name: 'A · 存档的 gameMode 改读会话状态（把生存存档永久变成创造存档）',
    file: 'save',
    find: 'meta.gameMode = effectiveGameMode().persisted()',
    // ★ 注入必须**能编译**。第一版写的是 `sessionActive ? ... : ...`，
    //   而 SaveManager 里根本没有这个变量 —— 于是注入后 mvn 停在编译错误，
    //   exit≠0 让"变红"成立，可失败行是编译错误而不是那条断言。
    //   症状是 RV 报"未归因命中"，看起来像守卫漏判，真因是**注入产出了不能编译的代码**。
    //   与前两轮（注入到注释上 / 拼回时丢 #else）同一个家族：注入自身的缺陷被报成守卫的错。
    repl: 'meta.gameMode = GameMode.CREATIVE.persisted();',
    expect: /theSessionNeverReachesTheSaveFile/,
  },
  {
    name: 'B · 用 config.gameMode() 门控开会话（创造存档因此拿到退出路径）',
    file: 'game',
    find: 'player.setCreativeSessionAllowed(!creative);',
    repl: 'player.setCreativeSessionAllowed(config.gameMode() == GameMode.SURVIVAL);',
    expect: /sessionsAreAllowedOnlyInSurvivalSaves|theCreativeEntryHasItsOwnWorld/,
  },
  {
    name: 'C · 退出会话时不清 creativeView（能力没了但创造标签还在）',
    file: 'game',
    // ★ 锚点必须**唯一且真的唯一**。`creativeView = null;` 在文件里出现两次
    //   （启动装配的 else、会话退出的 else），而且两处的 `} else { … }`
    //   **缩进与文本完全相同** —— 第一版的锚点 `} else {\n            creativeView = null;\n        }`
    //   照样先命中启动那一处，于是注入完全没有破坏被守的行为，守卫保持全绿。
    //   症状是 RV 报 FAIL，看起来像"守卫没守住"，真因是**注入打错了地方**。
    //   ⇒ 锚点必须带上 `buildCreativeView();` 这一行才唯一（只有会话边会建面板）。
    find: 'if (active) {\n            buildCreativeView();\n        } else {\n            creativeView = null;\n        }',
    repl: 'if (active) {\n            buildCreativeView();\n        } else {\n            /* RV-INJECT C: 不清面板 */\n        }',
    expect: /theCreativePaletteFollowsTheSession/,
  },
  {
    name: 'D · 触发回调的判据多一个条件（退出回调被吞，面板清不掉）',
    file: 'player',
    find: 'if (creativeSessionListener != null) {',
    repl: 'if (creativeSessionListener != null && creativeSession) {',
    expect: /theSessionListenerIsActuallyWired/,
  },
  {
    name: 'D2 · 删掉退出那条边的回调触发',
    file: 'player',
    find: 'notifyCreativeSession(false);',
    repl: '/* RV-INJECT D2: 退出不触发 */',
    expect: /theSessionListenerIsActuallyWired/,
  },
  {
    name: 'E · 文案删掉「不写入存档」（玩家会以为存档被改了，不敢退出）',
    file: 'loc',
    find: '本次运行有效，不写入存档',
    repl: '本次运行有效',
    expect: /bothSessionNoticesExist/,
  },
  {
    name: 'F · 文案删掉「建筑仍然留在世界里」（隐瞒 §4.3 理由①的残留代价）',
    file: 'loc',
    find: '用无限方块盖的建筑仍然留在世界里',
    repl: '已回到生存能力',
    expect: /bothSessionNoticesExist/,
  },
  {
    // ★ 2026-10-09：把「退出创造」塞回双击空格里 —— 正是主理人踩到的那个设计。
    //   它必须是破坏性的：一次手感键的连按静默拿走全部五项能力。
    name: 'G · 双击空格里重新塞回「退出会话」（静默拿走全部能力）',
    file: 'player',
    // ★ 锚点必须是**单行**：本仓库的 .java 是 CRLF，
    //   跨行锚点写成 '\n' 永远匹配不上，而症状是
    //   「RV INVALID — 锚点找不到」，看起来像源码变了，其实只是行尾。
    //   这一条此前因此 INVALID 了两次。
    find: '        setFlying(!flying);',
    repl: '        if (creativeSession && !flying) { creativeSession = false; setCreativeMode(false); return; }   // RV-INJECT G\n'
      + '        setFlying(!flying);',
    expect: /doubleTapNeverExitsTheSession/,
  },
  {
    // ★ 第一版的注入用的是 `!model.flying || model.onGround`，
    //   而断言只查 `model.onGround && model.flying` 这一种形状 ⇒ 注入没被抓到。
    //   真因是**断言写得太窄**：它匹配一种写法，而不是"依赖 onGround 这件事"。
    //   ⇒ 断言改成"drawFlightBadge 的方法体里不得出现 model.onGround"。
    name: 'H · HUD 的飞行指示与 onGround 挂钩（站着时永远不显示）',
    file: 'renderer',
    find: '        if (!model.flying && !model.creativeSession) {\n            return;\n        }',
    repl: '        if (!model.flying || model.onGround) {\n            return;   // RV-INJECT H\n        }',
    expect: /flightAndSessionAreShownOnTheHud/,
  },
  {
    name: 'I · 结束会话后不重建暂停菜单（那一行会留在屏幕上，点了没反应）',
    file: 'game',
    find: '                        pauseMenuScreen = Menus.pauseMenu(false);\n',
    repl: '                        // RV-INJECT I: 不重建\n',
    expect: /theSessionExitIsReachableFromThePauseMenu/,
  },
];

function sha(p) {
  return crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
}

function runTest() {
  const r = spawnSync('node', [path.join(PROJ, 'tmp', 'mvn_test_one.js'), TEST], {
    cwd: PROJ, encoding: 'utf8', shell: true, timeout: 900000,
  });
  return { code: r.status, out: (r.stdout || '') + (r.stderr || '') };
}

const original = {};
for (const [k, p] of Object.entries(FILES)) {
  original[k] = fs.readFileSync(p);
}

const base = runTest();
console.log('[rv] baseline exit=' + base.code + ' (expect 0)');
if (base.code !== 0) {
  console.error('[rv] baseline is red — fix that first, never inject into a red tree.');
  process.exit(2);
}

const results = [];

for (const inj of INJECTIONS) {
  const target = FILES[inj.file];
  const before = original[inj.file];
  const text = before.toString('utf8');

  if (!text.includes(inj.find)) {
    // ★ 找不到锚点必须计入失败。
    //   一个"注入没生效却仍报绿"的脚本比没有脚本更危险：它给出虚假的确信。
    console.error('[rv] INVALID  ' + inj.name + '  —— 锚点找不到，注入不会生效');
    results.push({ name: inj.name, ok: false, why: 'anchor not found' });
    continue;
  }

  // 全部替换。★ 注意：若某条的锚点在文件里出现多次，替换会打到**第一处**，
  //   而守卫守的可能是另一处 —— 那会让注入"看似生效、实则没碰到被守的行为"。
  //   本轮 C 那条就是踩了这个坑（两处 else 分支文本完全相同），
  //   修法是把锚点加长到**真正唯一**，而不是加 onceOnly 开关。
  const patched = text.split(inj.find).join(inj.repl);
  if (patched === text) {
    console.error('[rv] INVALID  ' + inj.name + '  —— 替换后文件没变');
    results.push({ name: inj.name, ok: false, why: 'replacement was a no-op' });
    continue;
  }

  fs.writeFileSync(target, patched, 'utf8');
  let r;
  try {
    r = runTest();
  } finally {
    fs.writeFileSync(target, before);   // 逐字节还原
  }

  const restored = sha(target) === crypto.createHash('sha256').update(before).digest('hex');
  const turnedRed = r.code !== 0;
  const blamed = inj.expect.test(r.out);
  const markerGone = !/RV-INJECT/.test(fs.readFileSync(target, 'utf8'));

  const ok = turnedRed && blamed && restored && markerGone;
  console.log('[rv] ' + (ok ? 'OK   ' : 'FAIL ') + inj.name);
  console.log('       exit=' + r.code + ' 变红=' + turnedRed
    + ' 归因命中=' + blamed + ' 还原=' + restored + ' 残渣清零=' + markerGone);
  if (turnedRed && !blamed) {
    console.log('       实际变红的断言（与预期不符）：');
    for (const l of r.out.split(/\r?\n/).filter(x => /ERROR\]   /.test(x)).slice(0, 4)) {
      console.log('         ' + l.trim());
    }
  }
  results.push({ name: inj.name, ok, why: '' });
}

// 全局残渣扫描：任何一个源文件里都不该留下标记
let residue = 0;
for (const [k, p] of Object.entries(FILES)) {
  if (/RV-INJECT/.test(fs.readFileSync(p, 'utf8'))) {
    console.error('[rv] RESIDUE in ' + k);
    residue++;
  }
}

const bad = results.filter(x => !x.ok);
console.log('');
console.log('[rv] ' + (bad.length === 0 && residue === 0
  ? `OK — ${results.length} 条断线全部被精确捕获并逐字节还原。`
  : `FAIL — ${bad.length}/${results.length} 条不合格，残渣 ${residue} 处：`
    + bad.map(b => b.name + (b.why ? ' (' + b.why + ')' : '')).join('; ')));
process.exit(bad.length === 0 && residue === 0 ? 0 : 1);