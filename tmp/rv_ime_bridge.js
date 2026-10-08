/*
 * 反向验证：ImeBridgeWiringTest 的关键断线，逐条注入 → 确认精确变红 → 逐字节还原。
 *
 * ★ 这份守卫特别需要 RV，理由和别的守卫不同：
 *   它守的是一个**修复**。而"修复"最典型的假绿形态是
 *   「类写好了、注释写得很清楚、但没人真的调用」——
 *   编译通过、单测全绿、门禁全绿，而 SHIFT 仍然失灵。
 *   本项目为「已定义、从未被调用」付过 M2.1 一次抓出 8 处的学费。
 *   所以每条注入都必须真的**拆掉接线**，而不只是改个注释里的字眼。
 *
 * 用法：node tmp/rv_ime_bridge.js
 */
'use strict';

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { spawnSync } = require('child_process');

const PROJ = process.env.SKYISLAND_PROJ || 'F:/minecraftspace';
const TEST = 'ImeBridgeWiringTest';

const FILES = {
  window: path.join(PROJ, 'src', 'main', 'java', 'com', 'skyisland', 'render', 'Window.java'),
  bridge: path.join(PROJ, 'src', 'main', 'java', 'com', 'skyisland', 'platform', 'ImeBridge.java'),
  c: path.join(PROJ, 'launcher', 'skyisland_ime.c'),
  build: path.join(PROJ, 'tmp', 'build_launcher.js'),
};

const INJECTIONS = [
  {
    name: 'A · 接线整个拆掉（类留着，没人调用 —— 最典型的假绿）',
    file: 'window',
    find: '        detachIme(handle);',
    repl: '        /* RV-INJECT A: 不再摘 IME */',
    expect: /theBridgeIsActuallyCalledFromWindowCreation/,
  },
  {
    name: 'B · 只查状态不摘除（查了但没改，日志会说谎）',
    file: 'window',
    find: 'final boolean after = com.skyisland.platform.ImeBridge.disableForWindow(handle);',
    repl: 'final boolean after = before;   // RV-INJECT B: 没真摘',
    expect: /theBeforeAndAfterStateIsLogged/,
  },
  {
    name: 'C · 摘除挪到 glfwFocusWindow 之前（聚焦会重新激活 IME，等于没摘）',
    file: 'window',
    find: '        GLFW.glfwFocusWindow(handle);\n\n        // ---- 摘掉 IME',
    repl: '        detachIme(handle);\n        GLFW.glfwFocusWindow(handle);\n\n        // ---- 摘掉 IME',
    expect: /theDetachHappensAfterFocus/,
  },
  {
    name: 'D · 去掉可用性判据（dll 缺失时 UnsatisfiedLinkError 会冒泡到主循环）',
    file: 'window',
    find: 'if (!com.skyisland.platform.ImeBridge.isAvailable()) {',
    repl: 'if (false) {   // RV-INJECT D: 不判可用性',
    expect: /theBridgeDegradesInsteadOfBlockingStartup/,
  },
  {
    name: 'E · 构建脚本不再编这个 dll（源码在库里却没人编）',
    file: 'build',
    find: 'buildImeBridge();',
    repl: '/* RV-INJECT E: 不编 dll */',
    expect: /theCSourceIsTrackedAndBuilt/,
  },
  {
    name: 'F · 链接丢掉 -limm32（ImmAssociateContextEx 的导入库）',
    file: 'build',
    find: "'-limm32',",
    repl: "/* RV-INJECT F: 无 imm32 */",
    expect: /theCSourceIsTrackedAndBuilt/,
  },
  {
    name: 'G · C 源文件被删（dll 不入库，源是唯一可追溯的产物）',
    file: 'c',
    delete: true,
    expect: /theCSourceIsTrackedAndBuilt/,
  },
  {
    // ★ 注入必须产出**能编译**的代码，且必须真的破坏被守的那条判据。
    //   第一版把 `return null;` 插到 loadStatus 方法开头，而方法体后面还有
    //   `return LOAD_ERROR;` ⇒ unreachable statement 编译错误。exit≠0 让
    //   "变红"成立，可失败行是**编译错误**而不是那条断言；症状是"守卫漏判"，
    //   真因是注入自身不可编译。
    //   第二版换成换整个方法体，结果**编译通过但断言照样绿** ——
    //   因为被守的是"诊断里有没有列出候选位置"，与方法体无关。
    //   ⇒ 第三版直接删掉 launcher/ 这个候选路径，那才是这条断言守的东西。
    name: 'H · 加载候选里不再有 launcher/（dll 就在那儿，去掉就永远加载不到）',
    file: 'bridge',
    find: '                cwd.resolve("launcher").resolve(LIB_FILE),\n',
    repl: '                // RV-INJECT H: 不再试 launcher/\n',
    expect: /theLoadDiagnosticsNameTheCandidatesTried/,
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
  const beforeSha = crypto.createHash('sha256').update(before).digest('hex');

  if (inj.delete) {
    fs.unlinkSync(target);
  } else {
    const text = before.toString('utf8');
    if (!text.includes(inj.find)) {
      console.error('[rv] INVALID  ' + inj.name + '  —— 锚点找不到，注入不会生效');
      results.push({ name: inj.name, ok: false, why: 'anchor not found' });
      continue;
    }
    const patched = text.split(inj.find).join(inj.repl);
    if (patched === text) {
      console.error('[rv] INVALID  ' + inj.name + '  —— 替换后文件没变');
      results.push({ name: inj.name, ok: false, why: 'replacement was a no-op' });
      continue;
    }
    fs.writeFileSync(target, patched, 'utf8');
  }

  let r;
  try {
    r = runTest();
  } finally {
    // 逐字节还原
    fs.writeFileSync(target, before);
  }

  const restored = sha(target) === beforeSha;
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

// 全局残渣扫描
let residue = 0;
for (const [k, p] of Object.entries(FILES)) {
  if (fs.existsSync(p) && /RV-INJECT/.test(fs.readFileSync(p, 'utf8'))) {
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