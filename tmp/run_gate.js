// Pre-M2 gate: package -> M1.5 UI selftest -> M1 regression selftest
const { spawnSync } = require('child_process');
const fs = require('fs');
const PROJ = 'F:/minecraftspace';

function run(cmd, args, opts) {
  const r = spawnSync(cmd, args, Object.assign({ cwd: PROJ, shell: true, windowsHide: true }, opts || {}));
  return { code: r.status, out: (r.stdout || '').toString('utf8') + (r.stderr || '').toString('utf8') };
}

const KEYS = [
  'ui_selftest_result', 'ui_selftest_asserts', 'ui_selftest_failures',
  'm1_selftest_passed', 'm1_selftest_scope', 'functional_closure',
  'selftest', 'PASS', 'FAIL', 'GATE',
];

function report(tag, txt) {
  const lines = txt.split(/\r?\n/);
  const hits = lines.filter(l => KEYS.some(k => l.indexOf(k) !== -1));
  console.log('--- ' + tag + ' (exit ' + tag.length + ') ---');
  console.log(hits.slice(-30).join('\n'));
}

// 1. package
let r = run('node', ['tmp/build.js', 'clean', 'package']);
console.log('=== PACKAGE exit=' + r.code + ' ===');
console.log(r.out.split(/\r?\n/).filter(l => /BUILD|Tests run:|ERROR/.test(l)).slice(-8).join('\n'));
if (r.code !== 0) { console.log('PACKAGE FAILED, abort'); process.exit(1); }

// 2. M1.5 UI selftest
r = run('cmd.exe', ['/c', 'run-m1.bat -Dskyisland.uiSelfTest=true -Dskyisland.settingsFile=tmp\\gate-settings.json']);
fs.writeFileSync(PROJ + '/tmp/gate-ui.txt', r.out, 'utf8');
console.log('=== M1.5 UI SELFTEST exit=' + r.code + ' ===');
report('m15', r.out);

// 3. M1 regression selftest
r = run('cmd.exe', ['/c', 'run-m1.bat -Dskyisland.selfTest=true -Dskyisland.worldName=gate-m1-selftest']);
fs.writeFileSync(PROJ + '/tmp/gate-m1.txt', r.out, 'utf8');
console.log('=== M1 REGRESSION exit=' + r.code + ' ===');
report('m1', r.out);
