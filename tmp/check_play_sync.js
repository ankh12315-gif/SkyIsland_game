/*
 * 守门人：play.bat 与 launcher/skyisland_launcher.c 必须指向同一个游玩世界。
 *
 * WHY this exists
 * ---------------
 * 这两个是同一个"游玩入口"的两种实现（.bat 与 .exe）。它们历史上**曾经**各自
 * 漂移过：桌面 .exe 是一份冻结的 09-23 二进制，世界名还停在 m21-play，而
 * .bat 早就改成了 islands-play —— 症状是"桌面按钮进的是一个只有手枪的世界"，
 * 而日志里每一行都写着正常。
 *
 * 漂移本身不难发现，难的是**它会再次发生**：任何人改世界名时都要记得同时改
 * 两个文件，而漏改的那个没有任何报错。所以把它变成一条断言。
 *
 * 判据
 * ----
 * 两边必须出现**同一个** worldName / saveDir 尾巴。这一条刻意不做"值对不对"
 * 的判断 —— 世界名什么时候改是产品决定，不是本守卫的事；
 * 它只守"两边不许不一致"。
 *
 * 用法：node tmp/check_play_sync.js
 */

'use strict';

const fs = require('fs');
const path = require('path');

const PROJ = process.env.SKYISLAND_PROJ || 'F:/minecraftspace';
const BAT = path.join(PROJ, 'play.bat');
const C = path.join(PROJ, 'launcher', 'skyisland_launcher.c');

const failures = [];

/**
 * 把一条路径折叠成可比的形式：`\` → `/`，并折叠连续分隔符。
 *
 * 为什么不直接比较原串：两边的写法天然不同 —— C 里是字符串字面量里的
 * `L"tmp\\islands-play-saves"`（`\\` 是一个反斜杠），.bat 里是 `tmp\islands-play-saves`。
 * 于是朴素比较会把**同一个目录**判成不一致，而这条守卫一旦有假红，
 * 就会被当成"又漂移了"而被人加宽或注释掉 —— 假红的守卫比没有守卫更坏。
 */
function norm(p) {
  return String(p).replace(/\\+/g, '/').replace(/^\.\//, '');
}

function read(p, what) {
  if (!fs.existsSync(p)) {
    console.error(`[check_play_sync] missing ${what}: ${p}`);
    process.exit(2);
  }
  return fs.readFileSync(p, 'utf8');
}

// ---- .bat：用 set "WORLD=..." / set "SAVE=..." 取值 ----
const bat = read(BAT, 'play.bat');
const batWorld = /set "WORLD=([^"]*)"/.exec(bat);
const batSave = /set "SAVE=%PROJ%([^"]*)"/.exec(bat);
if (!batWorld) {
  failures.push('play.bat: no set "WORLD=..." line');
}
if (!batSave) {
  failures.push('play.bat: no set "SAVE=%PROJ%..." line');
}

// ---- .c：kWorldName / kSaveRelDir ----
const c = read(C, 'skyisland_launcher.c');
const cWorld = /kWorldName\s*=\s*L"([^"]*)"/.exec(c);
const cSave = /kSaveRelDir\s*=\s*L"([^"]*)"/.exec(c);
if (!cWorld) {
  failures.push('skyisland_launcher.c: no kWorldName definition');
}
if (!cSave) {
  failures.push('skyisland_launcher.c: no kSaveRelDir definition');
}

const batSaveLabel = batSave ? norm(batSave[1]) : '(none)';
const cSaveLabel = cSave ? norm(cSave[1]) : '(none)';

if (batWorld && cWorld && batWorld[1] !== cWorld[1]) {
  failures.push(
    `worldName differs: play.bat="${batWorld[1]}" vs launcher.c="${cWorld[1]}"`
  );
}
if (batSave && cSave && norm(batSave[1]) !== norm(cSave[1])) {
  failures.push(
    `saveDir differs: play.bat="${batSave[1]}" vs launcher.c="${cSave[1]}"`
  );
}

// ---- 附带检查：settings 文件名也不能分叉（它决定灵敏度等设置落在哪）----
const batSettings = /-Dskyisland\.settingsFile=(\S+)/.exec(bat);
const cSettings = /kSettingsRel\s*=\s*L"([^"]*)"/.exec(c);
if (batSettings && cSettings) {
  const batName = norm(batSettings[1]);
  // kSettingsRel 已经是相对项目根的完整路径（含 tmp\ 前缀），不要再拼 tmp/
  const cName = norm(cSettings[1]);
  if (batName !== cName) {
    failures.push(`settingsFile differs: bat="${batName}" vs c="${cName}"`);
  }
}

console.log('[check_play_sync] play.bat         world=' + (batWorld && batWorld[1])
  + '  save=' + batSaveLabel);
console.log('[check_play_sync] skyisland_launcher.c world=' + (cWorld && cWorld[1])
  + '  save=' + cSaveLabel);

if (failures.length > 0) {
  console.error('');
  console.error('[check_play_sync] FAIL — the two play entry points disagree:');
  for (const f of failures) {
    console.error('  - ' + f);
  }
  console.error('');
  console.error('  The .exe on the Desktop is a COPY, so a rebuilt .bat alone does');
  console.error('  not fix anything there. Fix both, then:');
  console.error('      node tmp/build_launcher.js');
  console.error('      node tmp/deploy_desktop.js');
  process.exit(1);
}

console.log('[check_play_sync] OK — both entry points agree.');

// ---- 反向验证（RV）：把 .bat 的世界名改歪，它必须变红 ----
// ★ 为什么必须做：这条守卫的价值全在"它会红"。一个从不失败的检查，
//   与没有检查是同一件东西 —— 本项目为「断言在失败场景下仍能通过」
//   付过学费（见 DeadLocalizationKeyTest 的正向对照）。
//   这里在**内存副本**上注入，不碰磁盘文件，注入后立刻丢弃。
{
  const broken = bat.replace(/set "WORLD=[^"]*"/, 'set "WORLD=deliberately-drifted"');
  const m = /set "WORLD=([^"]*)"/.exec(broken);
  const wouldFail = !m || m[1] !== (cWorld && cWorld[1]);
  if (!wouldFail) {
    console.error('[check_play_sync] RV FAIL — a drifted .bat world name was NOT detected.');
    console.error('  The guard would have stayed green while the two entry points pointed');
    console.error('  at different saves, which is the exact bug it exists to catch.');
    process.exit(4);
  }
  console.log('[check_play_sync] RV ok — a drifted world name is detected.');
}