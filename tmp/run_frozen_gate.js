// SkyIsland - 冻结 jar 门禁串行跑（M1 功能 / M1.5 界面 / M2 战斗）。
//
// 两条纪律，都是踩过才写下来的：
//
// 1) **冻结 jar**：Maven 会就地重写 target/，而 JVM 惰性读类 —— 并发构建会把正在跑的游戏抽掉
//    （实测：一次 20000 帧死等，一次 17 秒变 2 分 37 秒）。所以先 build，再 copy 一份副本，
//    三个门禁全都从副本启动。
//
// 2) **每次运行必须从空存档目录开始**（本次新增，代价是三个门禁满屏假红）：
//    复用同一个 saveDir 会让第二次运行**读进上一轮的存档** —— 上一轮已经挖掉的地形、
//    已经放过的方块、玩家上一轮的位置，全部被载入。于是断言以"产品缺陷"的样子失败：
//      · m1「破坏计数增量=0」（其实那格上一轮就已经是空气）
//      · m1「放置失败：与实体碰撞箱重叠」「放置位置实际=air」
//      · ui「行走距离 0.000 格」（玩家被载入到上一轮的位置）
//      · m2 51 条连锁失败（装备/地形/粒子）
//    最危险的地方在于**假红长得像真红** —— 它会让人去改根本没坏的代码。
//    M2 自测自带一条前置条件断言挡住了自己（"存档已存在 → 请先清空该目录再跑"），
//    这里是把这个保证提到**运行器**这一层：每次开一个带时间戳的新目录，连删除动作都不需要。
//
// Usage:
//   node tmp/run_frozen_gate.js            # 构建 + 跑三门禁
//   node tmp/run_frozen_gate.js --no-build # 只跑三门禁（jar 已构建时用）
const { spawnSync } = require('child_process');
const fs = require('fs');

const PROJ = 'F:/minecraftspace';
const JAVA = 'D:/software/jdk-25/bin/java.exe';
const FROZEN = PROJ + '/tmp/selftest-jar/skyisland-frozen.jar';
const NO_BUILD = process.argv.indexOf('--no-build') >= 0;

// 目标 jar 用**通配解析**而不是写死文件名（与 play-*.bat 同样的两道校验）。
// 写死版本号会让每次升版本都留下一个"跑的是上一版 jar"的静默陷阱：
// 报告里引用的证据会指向一个不存在的构建，而运行器照样打印"通过"。
//
// ★ 2026-10-03 修正：判据是"恰好 1 个**真** jar"，不是"恰好 1 个文件"。
//   maven-shade-plugin 会在主 jar 旁边留一个**字节完全相同**的 `-shaded` 别名
//   （实测 sha256 一致：主 5794458 B / 别名 5794458 B / original- 678889 B），
//   而"不 clean 就重新构建"是开发常态 —— 原来那条 `hits.length !== 1`
//   于是会在最常见的迭代路径上把运行器自己卡死（play-m3.bat 先撞上了同一个坑）。
//   排除项与启动器保持一致：`-shaded.jar` 是别名，`original-*` 是 shade 前的 thin 备份。
//   仍保留唯一性校验，因为"版本升了却跑着旧 jar"才是这条守卫真正要防的东西。
function resolveTargetJar() {
  const dir = PROJ + '/target';
  const all = fs.readdirSync(dir).filter((n) => /^skyisland-.*\.jar$/.test(n));
  const hits = all.filter((n) => !n.endsWith('-shaded.jar'));
  if (hits.length !== 1) {
    console.log('[FATAL] target/ 下应恰好有 1 个可运行的 skyisland-*.jar，实际 ' + hits.length
      + ' 个（已忽略 *-shaded.jar 别名与 original-* thin 备份）：' + hits.join(', '));
    console.log('        target/ 实际内容：' + (all.join(', ') || '(空)'));
    console.log('        先构建：  node tmp/build.js clean package');
    process.exit(2);
  }
  return dir + '/' + hits[0];
}

function decode(buf) {
  try { return new TextDecoder('gbk').decode(buf); } catch (e) { return String(buf); }
}
function stamp() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return d.getFullYear() + p(d.getMonth() + 1) + p(d.getDate()) + '-' + p(d.getHours()) + p(d.getMinutes()) + p(d.getSeconds());
}
function newestSourceTs() {
  // 源码最近修改时间：用来证明"冻结 jar 不比源码旧"
  let newest = 0;
  const walk = (dir) => {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = dir + '/' + e.name;
      if (e.isDirectory()) { walk(full); continue; }
      if (/\.(java|xml)$/.test(e.name)) newest = Math.max(newest, fs.statSync(full).mtimeMs);
    }
  };
  walk(PROJ + '/src');
  walk(PROJ + '/tools');
  try { newest = Math.max(newest, fs.statSync(PROJ + '/pom.xml').mtimeMs); } catch (e) { /* ignore */ }
  return newest;
}

// ---------- 1. 构建（含全部单元用例）----------
if (!NO_BUILD) {
  console.log('### 构建中（clean package，含 surefire 全量用例）...');
  const b = spawnSync('node', [PROJ + '/tmp/build.js', 'clean', 'package'],
    { cwd: PROJ, shell: true, windowsHide: true, timeout: 900000 });
  const buildTxt = decode(b.stdout || Buffer.alloc(0));
  const keep = buildTxt.split(/\r?\n/).filter(l => /BUILD|Tests run:.*Failures|ERROR|Total time/.test(l));
  console.log(keep.slice(-10).join('\n'));
  if (b.status !== 0) { console.log('BUILD FAILED status=' + b.status + ' —— 中止，不跑门禁'); process.exit(1); }
  const TARGET_JAR = resolveTargetJar();
  fs.mkdirSync(PROJ + '/tmp/selftest-jar', { recursive: true });
  fs.copyFileSync(TARGET_JAR, FROZEN);
  console.log('FROZEN OK -> ' + FROZEN + '  (' + fs.statSync(FROZEN).size + ' bytes)');
  console.log('  源 jar = ' + TARGET_JAR);
} else {
  if (!fs.existsSync(FROZEN)) { console.log('冻结 jar 不存在: ' + FROZEN); process.exit(1); }
  const jarTs = fs.statSync(FROZEN).mtimeMs;
  const srcTs = newestSourceTs();
  console.log('沿用冻结 jar: ' + FROZEN + '  (' + fs.statSync(FROZEN).size + ' bytes)');
  console.log('  源码最新 mtime = ' + new Date(srcTs).toISOString());
  console.log('  冻结 jar mtime = ' + new Date(jarTs).toISOString());
  if (jarTs < srcTs) {
    console.log('  [ERROR] 冻结 jar 比源码旧 —— 它不能代表当前代码。去掉 --no-build 重跑。');
    process.exit(2);
  }
  console.log('  冻结 jar 不比源码旧 ✓');
}
console.log('');

// ---------- 2. 三个门禁串行，各自一个全新的空存档目录 ----------
const RUN_ROOT = PROJ + '/tmp/gate-runs/' + stamp();
fs.mkdirSync(RUN_ROOT, { recursive: true });
console.log('本轮运行根目录: ' + RUN_ROOT);
console.log('');

const GATES = [
  ['gate-m1', '-Dskyisland.selfTest=true', 240],
  ['gate-ui', '-Dskyisland.uiSelfTest=true', 180],
  ['gate-m2', '-Dskyisland.combatSelfTest=true', 300],
];

const summary = [];
for (const [tag, switchArg, timeoutS] of GATES) {
  const saveDir = RUN_ROOT + '/' + tag + '-saves';
  fs.mkdirSync(saveDir, { recursive: true });
  const before = fs.readdirSync(saveDir).length;
  if (before !== 0) {
    console.log('[ABORT] ' + tag + ' 的存档目录不是空的（' + before + ' 项）—— 会读进旧存档造成假红');
    process.exit(3);
  }

  const args = ['--enable-native-access=ALL-UNNAMED', switchArg,
    // DEV 口径：步枪与过渡材料包只在 DEV 下发（正式 Survival 默认没有），
    // 而三个门禁都依赖它们（m2 的 GEAR_CHECK、ui 的合成阶段）。
    '-Dskyisland.saveDir=' + saveDir, '-Dskyisland.loadout=dev', '-jar', FROZEN];
  const t0 = Date.now();
  const r = spawnSync(JAVA, args, {
    cwd: PROJ, windowsHide: true, timeout: timeoutS * 1000, maxBuffer: 64 * 1024 * 1024,
    env: Object.assign({}, process.env, { JAVA_HOME: 'D:\\software\\jdk-25' }),
  });
  const elapsed = (Date.now() - t0) / 1000;
  const outBuf = r.stdout || Buffer.alloc(0);
  fs.writeFileSync(PROJ + '/tmp/m2_' + tag + '.stdout.txt', outBuf);
  fs.writeFileSync(PROJ + '/tmp/m2_' + tag + '.stderr.txt', r.stderr || Buffer.alloc(0));
  const killed = r.error != null && /ETIMEDOUT|timed out/i.test(String(r.error));
  fs.writeFileSync(PROJ + '/tmp/m2_' + tag + '.meta.json', JSON.stringify({
    tag, jar: 'skyisland-frozen.jar', args, saveDir,
    exitCode: r.status, signal: r.signal, killedByTimeout: !!killed,
    elapsedSeconds: Number(elapsed.toFixed(2)),
    stdoutFile: PROJ + '/tmp/m2_' + tag + '.stdout.txt',
    stderrFile: PROJ + '/tmp/m2_' + tag + '.stderr.txt',
    finishedAt: new Date().toISOString(),
  }, null, 2), 'utf8');

  const L = decode(outBuf).split(/\r?\n/);
  let pass = 0, fail = 0, warn = 0;
  L.forEach(l => {
    if (/\[自测\] PASS|\[UI自测\] PASS/.test(l)) pass++;
    if (/\[自测\] FAIL|\[UI自测\] FAIL|FAIL ·/.test(l)) fail++;
    if (/\[WARN \]/.test(l)) warn++;
  });
  console.log('=== ' + tag + ' exit=' + r.status + ' signal=' + r.signal + ' killed=' + killed
    + ' elapsed=' + elapsed.toFixed(1) + 's | PASS=' + pass + ' FAIL=' + fail + ' WARN=' + warn + ' ===');
  L.filter(l => /closure|selftest_(passed|assertions|failures|scope)|警告数|退出码|存档\]|音频/.test(l))
    .forEach(k => console.log('   ' + k.replace(/^\[[\d:.]+\]\[(INFO|ERROR)\s*\]\s*/, '').trim()));
  const bad = L.filter(l => /FAIL ·|\[ERROR\]/.test(l));
  if (bad.length) { console.log('   -- 失败/错误行 --'); bad.slice(0, 10).forEach(x => console.log('   ' + x.trim())); }
  console.log('');
  summary.push({ tag, exit: r.status, fail });
}

console.log('########## 汇总 ##########');
summary.forEach(s => console.log(s.tag + ' exit=' + s.exit + ' 失败行=' + s.fail));
console.log('证据目录: ' + RUN_ROOT);
