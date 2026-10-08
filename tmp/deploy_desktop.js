// 部署桌面入口：把 launcher/*.exe 复制到桌面，并核对 sha256。
//
// 为什么用 node 而不是 PowerShell：本项目所有脚本都是 UTF-8，
// 而 PowerShell 的 -Command 走 GBK 控制台，中文文件名会被改写。
// 同一个坑在 tmp/run_frozen_gate.js 的文件头记过一次。
//
// 两个入口，刻意分成两个二进制而不是"同一个 exe 加参数"：
//   SkyIsland.exe          ->「SkyIsland 启动.exe」生存（有枪 + 合成）
//   SkyIsland-creative.exe ->「SkyIsland 创造.exe」创造（无限方块 + 飞行）
// 理由是 PRD_BLOCK_CREATIVE §4.3：模式在存档创建时定死。给生存 exe 加上
// -Dskyisland.gameMode=creative 能传到 JVM，但**没有任何作用** —— 生存世界
// 已经存在，游戏会照 level.json 读生存。症状是画面完全正常、只是背包少了
// 「创造」标签，是最容易被误判成"构建坏了"的那种失败。
//
// 为什么用 node 而不是 PowerShell：本项目所有脚本都是 UTF-8，
// 而 PowerShell 的 -Command 走 GBK 控制台，中文文件名会被改写。
//
// 用法：node tmp/deploy_desktop.js [--dry]
'use strict';

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PROJ = process.env.SKYISLAND_PROJ || 'F:/minecraftspace';
const LAUNCHER = path.join(PROJ, 'launcher');
const DESKTOP = process.env.SKYISLAND_DESKTOP
  || path.join(process.env.USERPROFILE || process.env.HOME || '', 'Desktop');

/**
 * 桌面入口表。
 *
 * ★ 为什么是数组而不是两个散落的常量：两个入口漂移过一次（桌面 exe 是
 *   一份冻结的 09-23 二进制，世界名还停在 m21-play，而 .bat 早已改成
 *   islands-play —— 症状是"桌面按钮进的是一个只有手枪的世界"，
 *   而日志里每一行都正常）。把它写成表，"两个都要部署、两个都要核对
 *   sha256" 就是循环里的一行，而不是两份必须同步的代码。
 */
const ENTRIES = [
  {
    src: 'SkyIsland.exe',
    dstName: 'SkyIsland 启动.exe',      // 生存入口
    legacy: 'SkyIsland M3 一键启动.exe',  // M3 时代的旧名
  },
  {
    src: 'SkyIsland-creative.exe',
    dstName: 'SkyIsland 创造.exe',       // 创造入口
    legacy: null,                        // 没有旧名
  },
];

const DRY = process.argv.includes('--dry');

function kb(p) {
  return (fs.statSync(p).size / 1024).toFixed(0) + ' KB';
}

const sha = (p) => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');

if (!fs.existsSync(DESKTOP)) {
  console.error('[deploy] Desktop not found: ' + DESKTOP);
  process.exit(2);
}

console.log('[deploy] launcher  = ' + LAUNCHER);
console.log('[deploy] desktop   = ' + DESKTOP);

if (DRY) {
  for (const e of ENTRIES) {
    const src = path.join(LAUNCHER, e.src);
    console.log('[deploy] DRY — ' + (fs.existsSync(src)
      ? e.src + ' (' + kb(src) + ')  ->  ' + e.dstName
      : e.src + ' MISSING  ->  ' + e.dstName));
  }
  console.log('[deploy] DRY — nothing written.');
  process.exit(0);
}

let failures = 0;

for (const e of ENTRIES) {
  const src = path.join(LAUNCHER, e.src);
  if (!fs.existsSync(src)) {
    console.error('[deploy] missing build output: ' + src);
    console.error('[deploy] run: node tmp/build_launcher.js');
    failures++;
    continue;
  }

  const dst = path.join(DESKTOP, e.dstName);
  console.log('');
  console.log('[deploy] src      = ' + src + '  (' + kb(src) + ')');
  console.log('[deploy] new name = ' + e.dstName);

  // 旧入口：改名成 .bak 而不是删掉。
  // 理由：桌面上的 exe 不在 git 里，删掉就再也拿不回来那份二进制。
  // 而它曾经是"能用"的那个 —— 万一新构建有问题，这是唯一的后备。
  if (e.legacy) {
    const old = path.join(DESKTOP, e.legacy);
    if (fs.existsSync(old)) {
      const bak = old + '.bak';
      if (fs.existsSync(bak)) {
        fs.rmSync(bak);
      }
      fs.renameSync(old, bak);
      console.log('[deploy] old entry moved -> ' + path.basename(bak));
    }
  }

  // 复制前先删旧目标，避免留下半个文件
  if (fs.existsSync(dst)) {
    fs.rmSync(dst);
  }
  fs.copyFileSync(src, dst);

  // ---- 部署后自证：sha256 必须与源一致 ----
  // 理由：本文件历史上最大的坑就是"桌面那份是旧二进制"，症状是
  // 改了源码却在桌面上跑了两个里程碑前的版本，而且没有任何报错。
  // ⇒ 复制之后必须核对字节，不核对等于没验。
  const a = sha(src);
  const b = sha(dst);
  if (a !== b) {
    console.error('[deploy] sha256 MISMATCH — ' + e.dstName + ' is NOT the binary we built:');
    console.error('  src ' + a);
    console.error('  dst ' + b);
    failures++;
    continue;
  }
  console.log('[deploy] deployed -> ' + dst + '  (' + kb(dst) + ')');
  console.log('[deploy] sha256 ok = ' + a.slice(0, 16) + '...');
}

console.log('');
if (failures > 0) {
  console.error('[deploy] FAILED — ' + failures + ' entry point(s) not trustworthy.');
  console.error('[deploy] Do not tell anyone the Desktop buttons were updated.');
  process.exit(3);
}
console.log('[deploy] done — both Desktop entry points match their build output.');