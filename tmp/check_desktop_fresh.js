/*
 * 守门人：桌面那份 exe 必须是 launcher/ 里**当前**那一份。
 *
 * WHY this exists
 * ---------------
 * 桌面上的「SkyIsland 启动.exe」是 **COPY，不是链接**。所以"源码改了、
 * 桌面那份没重新复制"这件事**没有任何东西会提醒你** —— 症状是主理人双击后
 * 跑的是上一次的二进制，而日志里版本号、门禁、单测全都正常。
 *
 * 本项目已经为这个故障付过两次学费（见 NativeLauncherWiringTest 的类注释），
 * 而 2026-10-08 晚上它又发生了一次：连续几轮改了 C 源与 Java 代码，
 * 每次都跑了 `node tmp/build_launcher.js`，但**没人跑 deploy_desktop.js**，
 * 于是桌面那份比 launcher/ 里的旧了将近一小时 —— 主理人直接发现"启动还没改好"。
 *
 * `deploy_desktop.js` 已经有 sha256 核对，但它守的是"复制过程没出错"；
 * **本脚本守的是"到底有没有人去调用它"**。那是缺的那一环。
 *
 * 用法：node tmp/check_desktop_fresh.js
 * 退出码：0 一致（或桌面不存在，见下）/ 1 不一致 / 2 用法错
 */

'use strict';

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PROJ = process.env.SKYISLAND_PROJ || 'F:/minecraftspace';
const SRC = path.join(PROJ, 'launcher', 'SkyIsland.exe');
const DESKTOP = process.env.SKYISLAND_DESKTOP
  || path.join(process.env.USERPROFILE || process.env.HOME || '', 'Desktop');
const DST = path.join(DESKTOP, 'SkyIsland 启动.exe');

const sha = (p) => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');

/*
 * ★ 桌面不存在时的处理必须**明确说出来**，不能静默跳过。
 *
 * 为什么不能静默绿：CI、别的克隆、别的机器上都没有桌面那份，
 * 而一个"找不到桌面 → 当作通过"的检查，会让人以为这条守卫在跑。
 * 那正是本项目反复吃亏的那一类（守卫看起来存在、实际上什么都不验）。
 * ⇒ 打印 SKIP 并解释为什么，然后仍然返回 0（那不是失败，是"这条不适用"），
 * 但**绝不能打印成 OK**。
 */
if (!fs.existsSync(DESKTOP)) {
  console.log('[check_desktop_fresh] SKIP — 桌面目录不存在: ' + DESKTOP);
  console.log('[check_desktop_fresh]        这条检查只在这台机器上适用（桌面按钮是本机拷贝）。');
  console.log('[check_desktop_fresh]        注意：它**不是** "已确认新鲜"。');
  process.exit(0);
}

if (!fs.existsSync(SRC)) {
  console.error('[check_desktop_fresh] 缺少构建产物: ' + SRC);
  console.error('[check_desktop_fresh] 先跑: node tmp/build_launcher.js');
  process.exit(2);
}

if (!fs.existsSync(DST)) {
  console.error('[check_desktop_fresh] 桌面入口不存在: ' + DST);
  console.error('[check_desktop_fresh] 跑: node tmp/deploy_desktop.js');
  process.exit(1);
}

const a = sha(SRC);
const b = sha(DST);

console.log('[check_desktop_fresh] launcher  ' + a.slice(0, 16) + '  ' + SRC);
console.log('[check_desktop_fresh] desktop   ' + b.slice(0, 16) + '  ' + DST);

if (a !== b) {
  const srcTime = fs.statSync(SRC).mtime.toISOString().slice(0, 19).replace('T', ' ');
  const dstTime = fs.statSync(DST).mtime.toISOString().slice(0, 19).replace('T', ' ');
  console.error('');
  console.error('[check_desktop_fresh] FAIL — 桌面那份是旧二进制。');
  console.error('  launcher 里那份构建于 ' + srcTime);
  console.error('  桌面上那份构建于 ' + dstTime);
  console.error('');
  console.error('  ★ 桌面入口是 COPY 不是链接，所以「重新编译了」不等于「桌面那份更新了」。');
  console.error('    跑: node tmp/deploy_desktop.js   （它会复制并核对 sha256）');
  console.error('');
  console.error('  不要告诉别人"已经修好了" —— 主理人双击的仍然是旧的。');
  process.exit(1);
}

console.log('[check_desktop_fresh] OK — 桌面入口与 launcher/ 里的构建一致。');