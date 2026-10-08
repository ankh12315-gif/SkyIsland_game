/*
 * 守门人：play-creative.bat 必须真的能进创造模式。
 *
 * WHY this exists
 * ---------------
 * PRD_BLOCK_CREATIVE §4.3 裁定「模式在存档创建时确定，永不切换」。
 * 这条规则有一个非常难受的失败形态：
 *
 *   -Dskyisland.gameMode=creative 只在**存档还不存在**时生效。
 *   已经存在的存档会读 level.json 里的 survival，并（正确地）忽略你的开关。
 *   而症状是完全正常的画面 + 一个少了「创造」标签的背包 —— 没有任何报错。
 *
 * 也就是说：创造模式"没开"这件事，默认表现是**静默**的。
 * 而"静默"在本项目的历史里已经反复造成过误判（本文件提到的
 * check_play_sync.js 就是同一个家族：两个入口各看各的，全绿）。
 *
 * 所以这里守的不是"参数值对不对"，而是**那三件事一旦错位就一定静默失效**：
 *   1. -Dskyisland.gameMode=creative 必须在命令行里
 *   2. world 必须与生存入口不同（共用 = 生存存档已经占了这个名字 = 永远进不去）
 *   3. saveDir 必须与生存入口不同（同上）
 *
 * 用法：node tmp/check_creative_sync.js
 */

'use strict';

const fs = require('fs');
const path = require('path');

const PROJ = process.env.SKYISLAND_PROJ || 'F:/minecraftspace';
const CREATIVE = path.join(PROJ, 'play-creative.bat');
const SURVIVAL = path.join(PROJ, 'play.bat');

const failures = [];

function read(p, what) {
  if (!fs.existsSync(p)) {
    console.error(`[check_creative_sync] missing ${what}: ${p}`);
    process.exit(2);
  }
  return fs.readFileSync(p, 'utf8');
}

/** 可执行行（去掉 REM 注释与空行）—— 与 extract_play_exec.js 同一口径。 */
function execLines(bat) {
  return bat.split(/\r?\n/)
    .map(l => l.trim())
    .filter(l => l !== '' && !/^REM\b/i.test(l) && !/^@echo/i.test(l));
}

const creative = read(CREATIVE, 'play-creative.bat');
const survival = read(SURVIVAL, 'play.bat');

// ---- 1. 模式开关必须在可执行行里 ----
// ★ 只在整文件里找是不够的：文件头部的 REM 里也提到过这串文字，
//   而 REM 行 cmd 根本不解析。那样这条断言会被**说明文字**满足 ——
//   把真正的开关删掉，它照样全绿。⇒ 判据只读可执行行。
const creativeExec = execLines(creative).join('\n');
if (!/-Dskyisland\.gameMode=creative/.test(creativeExec)) {
  failures.push('play-creative.bat: no -Dskyisland.gameMode=creative on an '
    + 'EXECUTABLE line. It may only appear in a REM comment, which cmd never '
    + 'runs -- then the world launches in survival and the 创造 tab is missing, '
    + 'with no error anywhere.');
}

// ---- 2 & 3. 世界名 / 存档目录必须与生存入口不同 ----
const cWorld = /set "WORLD=([^"]*)"/.exec(creative);
const cSave = /set "SAVE=%PROJ%([^"]*)"/.exec(creative);
const sWorld = /set "WORLD=([^"]*)"/.exec(survival);
const sSave = /set "SAVE=%PROJ%([^"]*)"/.exec(survival);

if (!cWorld) {
  failures.push('play-creative.bat: no set "WORLD=..." line');
}
if (!cSave) {
  failures.push('play-creative.bat: no set "SAVE=%PROJ%..." line');
}
if (cWorld && sWorld && cWorld[1] === sWorld[1]) {
  failures.push(
    `worldName is the SAME as play.bat ("${cWorld[1]}") -- that save already `
    + 'exists, so §4.3 locks it to survival and creative can never be entered.'
  );
}
if (cSave && sSave && cSave[1] === sSave[1]) {
  failures.push(
    `saveDir is the SAME as play.bat ("${cSave[1]}") -- the two entry points `
    + 'would share one level.json and fight over the mode lock.'
  );
}

/** 路径折叠：`\` → `/`。与 check_play_sync.js 同一口径，避免假红。 */
function norm(p) {
  return String(p).replace(/\\+/g, '/').replace(/^\.\//, '');
}

// ---- ★ 6. 原生启动器的 SKYISLAND_CREATIVE 分支必须与 .bat 一致 ----
// WHY: 桌面上现在有两个 exe（生存 / 创造）。它们与两个 .bat 是同一件事的四种实现。
//   本守卫第一版只比 .bat 与 launcher.c 的**生存**常量 —— 那时确实只有一个 exe。
//   加上创造 exe 之后，如果只守生存分支，那么"创造 exe 指向了生存世界"
//   这件事没有任何检查会红，而症状与 §4.3 叠加起来正是本文件要治的病：
//   **创造按钮打不开创造，且画面完全正常。**
//
// ★ 解析方式：C 里是 `#ifdef SKYISLAND_CREATIVE` 包着的 `#define SKY_WORLD L"..."`。
//   刻意**不**去找 `kWorldName = L"..."` —— 那里已经改成单一赋值点
//   （`kWorldName = SKY_WORLD;`），两分支各写一个 `kWorldName` 会让任何按
//   "取第一个匹配"写的守卫只校验生存分支，而创造分支悄悄漂移。
const C_SRC = path.join(PROJ, 'launcher', 'skyisland_launcher.c');
if (!fs.existsSync(C_SRC)) {
  console.error('[check_creative_sync] missing launcher source: ' + C_SRC);
  process.exit(2);
}
const cSrc = fs.readFileSync(C_SRC, 'utf8');

const branchMatch = /#ifdef SKYISLAND_CREATIVE([\s\S]*?)#else([\s\S]*?)#endif/.exec(cSrc);
if (!branchMatch) {
  failures.push('skyisland_launcher.c: no `#ifdef SKYISLAND_CREATIVE / #else / '
    + '#endif` block -- the creative exe cannot have its own world, so it would '
    + 'launch the survival world and never enter creative.');
} else {
  const cBranch = branchMatch[1];
  const sBranch = branchMatch[2];

  // ★ 去掉注释后再判"有没有某个开关"。
  //   这一条第一版直接对原文 grep `loadout|infiniteReserve`，结果**红在
  //   自己的解释性注释上** —— 那段注释恰恰是在说"这里不该有 loadout"。
  //   症状是"守卫说创造分支带了生存开关，可我明明没写"，看起来像守卫坏了。
  //   真因是判据读了注释。和 Java 侧那条"去掉注释后的源码"是同一个纪律：
  //   **注释里会解释这些行为，全文 grep 会被说明文字满足或否证。**
  const stripComments = (s) => s.replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/\/\/[^\n]*/g, ' ');
  const cBranchCode = stripComments(cBranch);

  const macro = (body, name) => {
    const m = new RegExp('#\\s*define\\s+' + name + '\\s+L"([^"]*)"').exec(body);
    return m ? m[1] : null;
  };

  const cWorldC = macro(cBranch, 'SKY_WORLD');
  const cSaveC = macro(cBranch, 'SKY_SAVE');
  const sWorldC = macro(sBranch, 'SKY_WORLD');
  const sSaveC = macro(sBranch, 'SKY_SAVE');

  if (cWorldC === null) {
    failures.push('skyisland_launcher.c: creative branch has no #define SKY_WORLD');
  } else if (cWorld && norm(cWorldC) !== norm(cWorld[1])) {
    failures.push(`creative worldName differs: play-creative.bat="${cWorld[1]}" vs `
      + `skyisland_launcher.c(SKYISLAND_CREATIVE)="${cWorldC}"`);
  }
  if (cSaveC === null) {
    failures.push('skyisland_launcher.c: creative branch has no #define SKY_SAVE');
  } else if (cSave && norm(cSaveC) !== norm(cSave[1])) {
    failures.push(`creative saveDir differs: play-creative.bat="${cSave[1]}" vs `
      + `skyisland_launcher.c(SKYISLAND_CREATIVE)="${cSaveC}"`);
  }
  if (sWorldC === null) {
    failures.push('skyisland_launcher.c: survival branch has no #define SKY_WORLD');
  } else if (sWorld && norm(sWorldC) !== norm(sWorld[1])) {
    failures.push(`survival worldName differs: play.bat="${sWorld[1]}" vs `
      + `skyisland_launcher.c(survival)="${sWorldC}"`);
  }
  if (sSaveC === null) {
    failures.push('skyisland_launcher.c: survival branch has no #define SKY_SAVE');
  } else if (sSave && norm(sSaveC) !== norm(sSave[1])) {
    failures.push(`survival saveDir differs: play.bat="${sSave[1]}" vs `
      + `skyisland_launcher.c(survival)="${sSaveC}"`);
  }

  if (!/-Dskyisland\.gameMode=creative/.test(cBranchCode)) {
    failures.push('skyisland_launcher.c: creative branch does not pass '
      + '-Dskyisland.gameMode=creative -- the creative exe would launch survival.');
  }
  // ★ 创造分支不得带生存专属开关。创造模式**不发枪**（PRD §5.6），
  //   而 loadout=dev 会去发放步枪与材料包 —— 一个"要了但永远拿不到"的请求，
  //   它错起来不会有任何报错。
  if (/loadout|infiniteReserve/.test(cBranchCode)) {
    failures.push('skyisland_launcher.c: creative branch carries a survival-only '
      + 'switch (loadout / infiniteReserve). Creative grants no guns (PRD 5.6).');
  }
  if (cWorldC !== null && sWorldC !== null && cWorldC === sWorldC) {
    failures.push(`both launcher branches point at world "${cWorldC}" -- the survival `
      + 'save already owns that name, so §4.3 locks it and the creative exe can '
      + 'never enter creative.');
  }
}

// ---- 附带：设置文件也不能分叉（灵敏度等设置落在哪）----
const cSettings = /-Dskyisland\.settingsFile=(\S+)/.exec(creative);
const sSettings = /-Dskyisland\.settingsFile=(\S+)/.exec(survival);
if (cSettings && sSettings && cSettings[1] === sSettings[1]) {
  failures.push(
    `settingsFile is the SAME as play.bat ("${cSettings[1]}") -- two worlds `
    + 'sharing one settings file means window size / sensitivity bleed across them.'
  );
}

// ---- 可执行行必须是纯 ASCII ----
// ★ 与 play.bat 同一纪律（REM 行允许中文）。这不是风格问题：
//   cmd 以 OEM/ANSI 代码页读 .bat，GBK 控制台下的非 ASCII 可执行行会被拆坏，
//   而症状是"某一行参数神秘地丢了" —— 而那恰好是上面第 1 条要守的东西。
const badAscii = execLines(creative).filter(l => /[^\x20-\x7E]/.test(l));
if (badAscii.length > 0) {
  failures.push('non-ASCII on EXECUTABLE line(s): ' + badAscii.length
    + ' (REM comments may contain Chinese; code may not)');
}

console.log('[check_creative_sync] play-creative.bat world=' + (cWorld && cWorld[1])
  + '  save=' + (cSave && cSave[1]) + '  mode=creative');
console.log('[check_creative_sync] play.bat          world=' + (sWorld && sWorld[1])
  + '  save=' + (sSave && sSave[1]) + '  mode=survival');

if (failures.length > 0) {
  console.error('');
  console.error('[check_creative_sync] FAIL:');
  for (const f of failures) {
    console.error('  - ' + f);
  }
  console.error('');
  console.error('  Every failure above fails SILENTLY at runtime: the game starts,');
  console.error('  looks normal, and the 创造 tab is simply absent.');
  process.exit(1);
}

console.log('[check_creative_sync] OK — the creative entry cannot silently fall back to survival.');

// ---- 反向验证（RV）：三条注入都必须被抓住 ----
// ★ 为什么必须做：这条守卫的全部价值在于"它会红"。一个从不失败的检查与
//   没有检查是同一件东西。以下三条都在**内存副本**上注入，不碰磁盘。
//
// ★★ 为什么注入必须**逐行**做，而不是对整份文件做一次 replace：
//   本文件头部 REM 里就解释着 `-Dskyisland.gameMode=creative` 这串文字
//   （本来就该解释），所以"整文件第一个匹配"落在 REM 行上。
//   第一版用 `t.replace(...)` 就改掉了那行 REM，java 命令行纹丝不动 ——
//   注入没造成任何变化，守卫当然"没抓到"。症状是 RV 报 FAIL，
//   看起来像守卫坏了，真因是**注入本身无效**。
//   而一个无效的注入比没有 RV 更危险：它给出虚假的确信。
//   ⇒ 所有注入只作用于非 REM 行。
function onExecLines(bat, fn) {
  return bat.split(/\r?\n/).map(l => {
    const t = l.trim();
    if (t === '' || /^REM\b/i.test(t) || /^@echo/i.test(t)) {
      return l;   // 注释行：注入必须跳过
    }
    return fn(l);
  }).join('\n');
}

const injects = [
  {
    name: 'a drifted world name (same as play.bat)',
    apply: (t) => onExecLines(t,
      l => l.replace(/set "WORLD=[^"]*"/, `set "WORLD=${sWorld && sWorld[1]}"`)),
    // 守卫对该注入的反应：世界名与 play.bat 撞车
    guardSays: (t) => {
      const m = /set "WORLD=([^"]*)"/.exec(t);
      return !!(m && sWorld && m[1] === sWorld[1]);
    },
  },
  {
    name: 'the gameMode switch deleted from the executable line',
    apply: (t) => onExecLines(t,
      l => l.replace(/ ?-Dskyisland\.gameMode=creative/, '')),
    guardSays: (t) => !/-Dskyisland\.gameMode=creative/
      .test(execLines(t).join('\n')),
  },
  {
    name: 'a shared saveDir (same as play.bat)',
    apply: (t) => onExecLines(t,
      l => l.replace(/set "SAVE=%PROJ%[^"]*"/, `set "SAVE=%PROJ%${sSave && sSave[1]}"`)),
    guardSays: (t) => {
      const m = /set "SAVE=%PROJ%([^"]*)"/.exec(t);
      return !!(m && sSave && m[1] === sSave[1]);
    },
  },
  {
    // ★ 桌面创造 exe 指向了生存世界。
    //   这一条比前三条更危险：它不会让任何 .bat 检查变红，而症状是
    //   「点了创造按钮，进去的却是生存世界」——玩家会以为创造模式坏了。
    name: 'the creative launcher branch pointing at the survival world',
    applyTo: 'c',
    apply: (t) => {
      const surv = sWorld && sWorld[1];
      if (!surv) {
        return t;
      }
      // 只改创造分支里的那一处，别把生存分支也改了（那样两条仍相等，
      // 注入就变成了"两个都指向生存" —— 那不是这条注入要模拟的场景）。
      //
      // ★ head 结束在 `#else` **之前**，rest 必须从 `#else` **开始**。
      //   第一版把 rest 起点写成 `m.index + m[0].length`（即 `#else` 之后），
      //   于是拼回去时 `#else` 整段丢了，重新解析直接失败 ——
      //   症状是 RV 报"未检测到"，看起来像守卫漏判，真因是**注入产出了
      //   一段语法坏掉的文本**，守卫对它无话可说。
      //   这与前两次是同一个家族：注入自身有缺陷，却报成守卫的错。
      const m = /#ifdef SKYISLAND_CREATIVE([\s\S]*?)#else/.exec(t);
      if (!m) {
        return t;
      }
      const cut = m.index + m[0].length - '#else'.length;
      const head = t.slice(0, cut);
      const rest = t.slice(cut);
      const patched = head.replace(/(#\s*define\s+SKY_WORLD\s+L")[^"]*(")/,
        `$1${surv}$2`);
      return patched + rest;
    },
    guardSays: (t) => {
      const m = /#ifdef SKYISLAND_CREATIVE([\s\S]*?)#else([\s\S]*?)#endif/.exec(t);
      if (!m) {
        return false;
      }
      const g = (b, n) => {
        const r = new RegExp('#\\s*define\\s+' + n + '\\s+L"([^"]*)"').exec(b);
        return r ? r[1] : null;
      };
      const cw = g(m[1], 'SKY_WORLD');
      const sw = g(m[2], 'SKY_WORLD');
      return cw !== null && sw !== null && cw === sw;
    },
  },
  {
    name: 'the creative launcher branch losing its gameMode switch',
    applyTo: 'c',
    apply: (t) => {
      const m = /#ifdef SKYISLAND_CREATIVE([\s\S]*?)#else/.exec(t);
      if (!m) {
        return t;
      }
      // 切片口径与上一条注入一致（rest 必须含 `#else`，否则文本语法就坏了）
      const cut = m.index + m[0].length - '#else'.length;
      const head = t.slice(0, cut);
      const rest = t.slice(cut);
      const patched = head.replace(/(-Dskyisland\.gameMode=creative\s)/, '');
      return patched + rest;
    },
    guardSays: (t) => {
      const m = /#ifdef SKYISLAND_CREATIVE([\s\S]*?)#else([\s\S]*?)#endif/.exec(t);
      return m ? !/-Dskyisland\.gameMode=creative/.test(m[1]) : false;
    },
  },
];

console.log('');
let rvBad = 0;
for (const inj of injects) {
  const targetIsC = inj.applyTo === 'c';
  const before = targetIsC ? cSrc : creative;
  const broken = targetIsC ? inj.apply(cSrc) : inj.apply(creative);

  // 前置自检：注入必须真的改变了文件。
  // ★ 没有这一条，一个"改了等于没改"的注入会被当成"守卫漏了"，
  //   而报告方向完全相反 —— 上两版就死在这里。
  if (broken === before) {
    console.error('[check_creative_sync] RV INVALID — injection changed nothing: '
      + inj.name);
    console.error('  The injection is broken, not the guard. Reporting this as a');
    console.error('  guard miss would send you hunting the wrong bug.');
    rvBad++;
    continue;
  }

  // 两条读 launcher 的注入需要用"注入后的 .c"重新判定，
  // 所以把判据做成接受文本的形式。
  let said;
  if (targetIsC) {
    said = inj.guardSays(broken);
  } else {
    said = inj.guardSays(broken);
  }

  if (said) {
    console.log('[check_creative_sync] RV ok — detected: ' + inj.name);
  } else {
    console.error('[check_creative_sync] RV FAIL — NOT detected: ' + inj.name);
    console.error('  The guard would have stayed green while creative mode silently');
    console.error('  failed to activate — the exact failure it exists to catch.');
    rvBad++;
  }
}

process.exit(rvBad === 0 ? 0 : 4);