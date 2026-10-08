// 反向验证：把 play.bat 的世界名改歪，check_play_sync.js 必须变红。
//
// 纪律（项目 handoff §四）：每条新断言都要做反向验证 ——
// 注入破坏 → 确认它精确变红并给出可读归因 → 恢复 → 确认全绿。
// 失败的标准长相是"断言在失败场景下仍能通过"。
//
// 本脚本**只在磁盘上临时改一行**，跑完立刻逐字节恢复并校验 sha256。
// 用法：node tmp/rv_play_sync.js
'use strict';

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { spawnSync } = require('child_process');

const PROJ = process.env.SKYISLAND_PROJ || 'F:/minecraftspace';
const BAT = path.join(PROJ, 'play.bat');
const GUARD = path.join(PROJ, 'tmp', 'check_play_sync.js');

const DRIFT = 'islands-play';
const BROKEN = 'deliberately-drifted-rv';

function sha(p) {
  return crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
}

const before = fs.readFileSync(BAT);
const beforeSha = sha(BAT);

function runGuard() {
  const r = spawnSync('node', [GUARD], { cwd: PROJ, encoding: 'utf8' });
  return { code: r.status, out: (r.stdout || '') + (r.stderr || '') };
}

const base = runGuard();
console.log('[rv] baseline exit=' + base.code + ' (expect 0)');
if (base.code !== 0) {
  console.error('[rv] baseline is already failing — fix that first, do not inject into a red tree.');
  console.error(base.out);
  process.exit(2);
}

// ---- 注入：把 bat 里的世界名改歪 ----
const text = before.toString('utf8');
const needle = `set "WORLD=${DRIFT}"`;
if (!text.includes(needle)) {
  console.error('[rv] cannot find the line ' + needle + ' — structure changed?');
  process.exit(2);
}
fs.writeFileSync(BAT, text.split(needle).join(`set "WORLD=${BROKEN}"`), 'utf8');
console.log('[rv] injected: play.bat WORLD -> ' + BROKEN);

let injected;
try {
  injected = runGuard();
  console.log('[rv] injected exit=' + injected.code + ' (expect non-zero)');
  const named = /worldName differs/.test(injected.out);
  const readable = injected.out.includes(BROKEN) && injected.out.includes(DRIFT);
  console.log('[rv] blame names the drifted value: ' + named);
  console.log('[rv] blame is readable (both values shown): ' + readable);
} finally {
  // ---- 恢复：无论注入结果如何都还原 ----
  fs.writeFileSync(BAT, before);
  const afterSha = sha(BAT);
  console.log('[rv] restored byte-identical: ' + (afterSha === beforeSha));
  if (afterSha !== beforeSha) {
    console.error('[rv] RESTORE FAILED — play.bat is left modified!');
    process.exit(3);
  }
}

const after = runGuard();
console.log('[rv] restored exit=' + after.code + ' (expect 0)');

const ok = injected.code !== 0
  && /worldName differs/.test(injected.out)
  && injected.out.includes(BROKEN)
  && after.code === 0;

console.log(ok
  ? '[rv] OK — the guard turns red on a drifted world name and green again after restore.'
  : '[rv] FAIL — the guard did not behave as required.');
process.exit(ok ? 0 : 1);