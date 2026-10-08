// 部署桌面入口：把 launcher/SkyIsland.exe 复制为「SkyIsland 启动.exe」，
// 并把旧名（M3 一键启动.exe）移成 .bak。
//
// 为什么用 node 而不是 PowerShell：本项目所有脚本都是 UTF-8，
// 而 PowerShell 的 -Command 走 GBK 控制台，中文文件名会被改写。
// 同一个坑在 tmp/run_frozen_gate.js 的文件头记过一次。
//
// 用法：node tmp/deploy_desktop.js [--dry]
'use strict';

const fs = require('fs');
const path = require('path');

const PROJ = process.env.SKYISLAND_PROJ || 'F:/minecraftspace';
const SRC = path.join(PROJ, 'launcher', 'SkyIsland.exe');
const DESKTOP = process.env.SKYISLAND_DESKTOP
  || path.join(process.env.USERPROFILE || process.env.HOME || '', 'Desktop');

const NEW_NAME = 'SkyIsland 启动.exe';   // 新入口
const OLD_NAME = 'SkyIsland M3 一键启动.exe';  // 旧入口（M3 时代）
const DRY = process.argv.includes('--dry');

function kb(p) {
  return (fs.statSync(p).size / 1024).toFixed(0) + ' KB';
}

if (!fs.existsSync(SRC)) {
  console.error('[deploy] missing build output: ' + SRC);
  console.error('[deploy] run: node tmp/build_launcher.js');
  process.exit(2);
}
if (!fs.existsSync(DESKTOP)) {
  console.error('[deploy] Desktop not found: ' + DESKTOP);
  process.exit(2);
}

const dst = path.join(DESKTOP, NEW_NAME);
const old = path.join(DESKTOP, OLD_NAME);

console.log('[deploy] src      = ' + SRC + '  (' + kb(SRC) + ')');
console.log('[deploy] desktop  = ' + DESKTOP);
console.log('[deploy] new name = ' + NEW_NAME);

if (DRY) {
  console.log('[deploy] DRY — nothing written.');
  process.exit(0);
}

// 旧入口：改名成 .bak 而不是删掉。
// 理由：桌面上的 exe 不在 git 里，删掉就再也拿不回来那份二进制。
// 而它曾经是"能用"的那个 —— 万一新构建有问题，这是唯一的后备。
if (fs.existsSync(old)) {
  const bak = old + '.bak';
  if (fs.existsSync(bak)) {
    fs.rmSync(bak);
  }
  fs.renameSync(old, bak);
  console.log('[deploy] old entry moved -> ' + path.basename(bak));
}

// 复制前先删旧的 .bak 目标，避免 rename 失败时留下两个并存入口
if (fs.existsSync(dst)) {
  fs.rmSync(dst);
}
fs.copyFileSync(SRC, dst);
console.log('[deploy] deployed  -> ' + dst + '  (' + kb(dst) + ')');

// ---- 部署后自证：sha256 必须与源一致 ----
// 理由：本文件历史上最大的坑就是"桌面那份是旧二进制"，症状是
// 改了源码却在桌面上跑了两个里程碑前的版本，而且没有任何报错。
// ⇒ 复制之后必须核对字节，不核对等于没验。
const crypto = require('crypto');
const sha = (p) => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const a = sha(SRC);
const b = sha(dst);
if (a !== b) {
  console.error('[deploy] sha256 MISMATCH — deployment is not trustworthy:');
  console.error('  src ' + a);
  console.error('  dst ' + b);
  process.exit(3);
}
console.log('[deploy] sha256 ok = ' + a.slice(0, 16) + '...');
console.log('[deploy] done.');