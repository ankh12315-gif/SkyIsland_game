// 生成 play-m3.bat（M3 两把枪启动器）。
//
// 为什么用脚本而不是手写这个 .bat：Windows 批处理必须纯 ASCII + CRLF 保存，
// 否则中文 Windows 的 cmd.exe 会按 GBK 读乱，多字节字符还可能把后续命令行解析崩
// （这条是本项目已经踩过的坑，见 tmp/gen_play_bat.js 的同一段说明）。
//
// 为什么 M3 必须**新开一个启动器**，而不是沿用 play-m2.bat：
//   play-m2.bat 用的是 worldName=m21-play + tmp\m21-play-saves，那个存档**已经存在**。
//   开局装备只在"新世界"发放（SkyIslandGame:975-981），读旧档时装备本来就在存档里、
//   不再补发。而 M2.1 时代的那个存档里**没有 SMG** —— 于是玩家双击 play-m2.bat
//   会看到一把手枪，并合理地得出"SMG 没做出来"的结论。
//   独立世界名 + 独立空存档目录，才能保证第一次运行一定是新世界、一定拿到两把枪。
//
// ★ 2026-10-03 修正：jar 解析不能用"target\ 下恰好 1 个 skyisland-*.jar"当判据。
//   maven-shade-plugin 会在主 jar 旁边留一个**字节完全相同**的 `-shaded` 别名
//   （实测 sha256 相同：主 5794458 B / 别名 5794458 B / original- 678889 B），
//   而"不 clean 就重新构建"是开发与试玩的常态 —— 于是那条守卫会在最常见的
//   迭代路径上误报，把人卡在"明明刚构建完"。这正是本项目认定的
//   **"用脆弱代理量去表达一条真规则"**：这条守卫真正要挡的是
//   "版本号变了却跑着上一版 jar"，不是"文件数不等于 1"。
//   改法：显式排除 `original-*` 与 `*-shaded`，再要求"恰好 1 个真 jar"，
//   并在失败时把目录里实际有什么 `dir /b` 打出来（让人一眼能继续，而不是卡在猜）。
const fs = require('fs');

const L = [
  '@echo off',
  'setlocal enabledelayedexpansion',
  'REM ===========================================================================',
  'REM  SkyIsland - M3 Weapon Generalization  (pistol + SMG)   (double-click me)',
  'REM',
  'REM  WHAT IS NEW IN M3:',
  'REM    1) TWO GUNS. Your starting gear is now BOTH guns:',
  'REM         hotbar 1 = PISTOL  SINGLE  8 dmg  12-round mag  1.2 s reload',
  'REM         hotbar 2 = PISTOL AMMO x24  (shared by BOTH guns)',
  'REM         hotbar 3 = SMG     AUTO    5 dmg  24-round mag  1.5 s reload',
  'REM       Same ammo item, two guns. Switching is just the number keys.',
  'REM    2) THIS LAUNCHER RUNS THE DEV / TEST LOADOUT (-Dskyisland.loadout=dev).',
  'REM       On top of the two M3 guns you also get:',
  'REM         hotbar 4 = RIFLE      SINGLE  14 dmg  10-round mag  2.0 s reload',
  'REM         hotbar 5 = RIFLE AMMO x20',
  'REM         backpack = DEV / TRANSITION MATERIAL KIT (log 4, iron ore 9,',
  'REM                    copper ore 3, coal 20, sand 4, crystal 1)',
  'REM       The material kit is a TEMPORARY stand-in: ore world generation and the',
  'REM       real gathering chain are NOT in this milestone, so without it every',
  'REM       recipe would read "missing xN" forever. It is deleted once that chain',
  'REM       closes. The FORMAL M3 Survival loadout (the product default) has NO',
  'REM       rifle and NO kit - it is the two guns only.',
  'REM    3) CRAFTING IS LIVE. Press E to open the inventory: the RIGHT column is',
  'REM       the recipe list. Each row shows the product, what you have / what it',
  'REM       needs (e.g. "iron ingot 8/8"), and either a [CRAFT] button or the',
  'REM       shortfall ("missing iron ingot x3") - a row you cannot afford always',
  'REM       says why, it never just sits there. Clicking a row spends the',
  'REM       materials and puts the product in your backpack.',
  'REM    4) RESERVE AMMO IS INFINITE IN THIS LAUNCHER (by default). This is the',
  'REM       Debug / Prototype caliber (v2 sec 19-7): reloading never drains the',
  'REM       reserve, so you can test fire modes and reload timings back to back',
  'REM       without ever running dry. The startup log prints which caliber is',
  'REM       active, so this is never a guess.',
  'REM       The FORMAL M3 Survival caliber is the opposite - FINITE reserve that',
  'REM       really spends rounds out of your inventory (v2 sec 19-6) - and it is',
  'REM       still the product default. This launcher only flips the switch for',
  'REM       convenience. To play the formal caliber, either delete',
  'REM           -Dskyisland.infiniteReserve=true',
  'REM       from the java line near the bottom, or set it to false.',
  'REM    5) RELOAD IS NOT INTERRUPTED BY MOVEMENT. Walk while reloading and the',
  'REM       reload still finishes (this is deliberate, not a bug).',
  'REM    6) AIMING DIFFERS: hold RMB -> pistol FOV 45 / move speed x0.60,',
  'REM       SMG FOV 48 / move speed x0.65, rifle FOV 40 / move speed x0.55.',
  'REM       These numbers are marked as "tunable in playtest" - say so if the',
  'REM       difference feels wrong.',
  'REM',
  'REM  DO THESE FIRST (each one is a claim that must be checked):',
  'REM    1. press 3            -> the SMG is in your hand: different silhouette',
  'REM                             and a different backpack icon than the pistol.',
  'REM    2. ONE CLICK, then HOLD LMB:',
  'REM         with the pistol (key 1) one click = exactly ONE shot,',
  'REM         with the SMG    (key 3) holding  = a full burst.',
  'REM       This contrast is the whole point of M3 - if both feel the same,',
  'REM       the fire-mode split did NOT take. Say so.',
  'REM    3. empty a magazine and press R -> pistol ~1.2 s, SMG ~1.5 s.',
  'REM       While it reloads, hold W: the reload must NOT be cancelled.',
  'REM    4. press F4 -> a monster walks up; shoot it with both guns and compare',
  'REM       (pistol hits harder but slower; SMG is faster but weaker per shot).',
  'REM    5. press E -> the RIGHT column is the recipe list. Craft "oak planks"',
  'REM       from logs, then "iron ingot" (iron ore + coal), then "gunpowder"',
  'REM       (coal x2 + sand). If a row says "missing ...", that is the point -',
  'REM       it must name the item and the count, never stay silent.',
  'REM    6. with the rifle (key 4): ONE CLICK = exactly ONE shot. Holding LMB',
  'REM       must NOT keep firing (SINGLE). Compare with the SMG (key 3), where',
  'REM       holding DOES keep firing (AUTO).',
  'REM',
  'REM  WHY THIS LAUNCHER IS SEPARATE FROM play-m2.bat:',
  'REM    Starting gear is granted ONLY on a brand new world. This launcher uses',
  'REM    its own world name and its own save dir (tmp\\m3-play-saves), so the',
  'REM    first run is guaranteed to be a new world and to grant both guns.',
  'REM    The M2.1 save (tmp\\m21-play-saves) contains NO SMG - reusing it would',
  'REM    show you a pistol only. Do not reuse it to judge M3.',
  'REM',
  'REM  KEYS: number keys switch hotbar / LMB fire / RMB aim / R reload /',
  'REM        F2 screenshot  F3 debug overlay  F4 spawn monster  F5 save',
  'REM        F6 resupply  F7 clear entities  F9 force respawn  ESC pause menu',
  'REM  NOTE ON F6: it grants the WHOLE starting kit again, so you get another',
  'REM  pistol and another SMG each time. That is existing behaviour, not a bug.',
  'REM',
  'REM  Exit via ESC -> pause menu -> quit, or just close the window.',
  'REM    Both save properly. Do NOT kill it from Task Manager (that does not save).',
  'REM',
  'REM  Logs: the game writes its own file, see the newest one in logs\\',
  'REM ===========================================================================',
  '',
  'set "JDK_HOME=D:\\software\\jdk-25"',
  'set "PROJ=%~dp0"',
  'set "WORLD=m3-play"',
  'set "SAVE=%PROJ%tmp\\m3-play-saves"',
  '',
  'if not exist "%JDK_HOME%\\bin\\java.exe" (',
  '  echo [ERROR] JDK 25 not found at "%JDK_HOME%".',
  '  pause',
  '  exit /b 2',
  ')',
  '',
  'set "JAR="',
  'set "JARCOUNT=0"',
  'REM  Ignore two files on purpose:',
  'REM    original-*.jar  = the thin pre-shade backup (678 KB, no deps bundled);',
  'REM                     it does not match the skyisland-* glob anyway.',
  'REM    *-shaded.jar    = maven-shade-plugin leaves a BYTE-IDENTICAL alias next',
  'REM                      to the main artifact (verified: same sha256).',
  'REM  They are not a second "version" -- the real risk this guard exists for is',
  'REM  running a STALE jar after a version bump, so the check must be',
  'REM  "exactly one REAL jar", not "exactly one file".',
  'REM',
  'REM  NB: the suffix test is a fixed-width compare (!CAND:~-11!), NOT a wildcard.',
  'REM  cmd string comparison does NOT glob -- "if /i neq skyisland-*-shaded"',
  'REM  silently matches everything and counts the alias (verified: JARCOUNT=2).',
  'REM  Delayed expansion is what makes the substring readable inside the loop.',
  'for %%f in ("%PROJ%target\\skyisland-*.jar") do (',
  '  set "CAND=%%~nxf"',
  '  if not "!CAND:~-11!"=="-shaded.jar" (',
  '    set "JAR=%%~ff"',
  '    set /a JARCOUNT+=1',
  '  )',
  ')',
  '',
  'if not "%JARCOUNT%"=="1" (',
  '  echo [ERROR] Expected exactly 1 runnable jar in target\\, found %JARCOUNT%.',
  '  echo         original-*.jar and *-shaded.jar are ignored on purpose.',
  '  echo         What is actually in target\\:',
  '  dir /b "%PROJ%target\\*.jar"',
  '  echo         Build it first:  node tmp/build.js clean package',
  '  pause',
  '  exit /b 3',
  ')',
  '',
  'if not exist "%PROJ%tmp" mkdir "%PROJ%tmp"',
  'if not exist "%SAVE%" mkdir "%SAVE%"',
  '',
  'REM  Guard against the one mistake that silently hides the SMG: if this world',
  'REM  already exists, the starting gear is NOT re-granted and you may be',
  'REM  looking at an old inventory. Warn loudly instead of pretending.',
  'if exist "%SAVE%\\%WORLD%\\level.json" (',
  '  echo.',
  '  echo [WARN] A save already exists: %SAVE%\\%WORLD%',
  '  echo [WARN] Starting gear is granted ONLY on a NEW world, so this run will',
  '  echo [WARN] load the old inventory instead. If you do not see the SMG in',
  '  echo [WARN] hotbar slot 3, that is why. To start fresh, rename or remove:',
  '  echo [WARN]   %SAVE%\\%WORLD%',
  '  echo [WARN] Then run this launcher again.',
  '  echo.',
  '  pause',
  ')',
  '',
  'cd /d "%PROJ%"',
  '',
  'echo.',
  'echo [INFO] Launching SkyIsland M3 - pistol + SMG',
  'echo [INFO] Jar      : %JAR%',
  'echo [INFO] World    : %WORLD%',
  'echo [INFO] Save dir : %SAVE%',
  'echo.',
  'echo [INFO] Starting gear: 1=pistol  2=pistol ammo x24  3=SMG',
  'echo [INFO]         (DEV loadout)     4=rifle  5=rifle ammo x20  + material kit',
  'echo [INFO] Reserve    : INFINITE (Debug caliber, v2 sec 19-7)',
  'echo [INFO] Check first:  press 3  then  compare one-click vs hold-LMB',
  'echo [INFO]              press E  then  craft in the RIGHT column',
  'echo.',
  '',
  'REM  -Dskyisland.infiniteReserve=true  -> infinite reserve for THIS launcher',
  'REM  (Debug / Prototype caliber). Remove it (or set false) for the formal',
  'REM  finite Survival caliber. See the REM header item 4 for why.',
  'REM  -Dskyisland.loadout=dev           -> DEV / TEST loadout: adds the rifle,',
  'REM  rifle ammo and the transition material kit (see header item 2). Remove it',
  'REM  to play the formal Survival loadout: two guns, no rifle, no kit.',
  '"%JDK_HOME%\\bin\\java.exe" --enable-native-access=ALL-UNNAMED %* -Dskyisland.worldName=%WORLD% -Dskyisland.settingsFile=tmp\\m3-play-settings.json -Dskyisland.saveDir="%SAVE%" -Dskyisland.infiniteReserve=true -Dskyisland.loadout=dev -jar "%JAR%"',
  'set "RC=%ERRORLEVEL%"',
  '',
  'echo.',
  'echo [INFO] Exit code = %RC%',
  'echo [INFO] Full log: the newest file in logs\\',
  'pause',
  'endlocal & exit /b %RC%',
  '',
];

const content = L.join('\r\n');
const offenders = [...content].filter((c) => c.charCodeAt(0) > 127);
if (offenders.length > 0) {
  console.error('[FATAL] 内容含非 ASCII 字符，批处理在 GBK 的 cmd.exe 下会被读乱：'
    + offenders.join(''));
  process.exit(1);
}
if (content.indexOf('\n\n') < 0 && content.indexOf('\r\n\r\n') < 0) {
  console.error('[FATAL] 未检测到 CRLF 空行，行尾可能不是 CRLF');
  process.exit(1);
}

const out = 'F:/minecraftspace/play-m3.bat';
fs.writeFileSync(out, content, { encoding: 'ascii' });
const stat = fs.statSync(out);
console.log('已写入 ' + out);
console.log('  字节数 = ' + stat.size + '（纯 ASCII，CRLF 行尾）');
console.log('  行数   = ' + L.length);
