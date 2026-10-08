@echo off
setlocal enabledelayedexpansion
REM ===========================================================================
REM  SkyIsland - play entry point   (double-click me)
REM
REM  *** THE WORLD CHANGED ON 2026-10-08. READ THIS FIRST. ***
REM  You now spawn on a SKYBLOCK ISLAND, not on the old test platform:
REM    - main island 32x32 at (0,0), grass on top, 8-14 blocks thick, void below
REM    - FOUR resource islands you can walk to (or bridge to):
REM        stone island  (48,0)  14x14  coal + iron + stone
REM        forest island (-44,12) 14x14  trees
REM        metal island  (10,-48) 12x12 iron + copper + gold
REM        crystal island(-6,52) 10x10 crystal (rare)
REM    - a HALF-FINISHED STARTER HUT at the centre of the main island:
REM        plank floor, walls, plank roof with ONE square left unfinished,
REM        a door opening on the south side, one glass window north and south.
REM        You spawn INSIDE it. Walk out the south door.
REM    - one unbreakable RESOURCE CORE glows at each resource island centre.
REM  This is PRD section 4.2 / 4.3 / 5.7. See
REM    docs/testing/WORLD_ISLAND_GENERATOR_REPORT.md for what is asserted.
REM
REM  The save dir was RENAMED (m3-play -> islands-play) for a reason, not a
REM  detail: a save is only "block deltas on top of whatever the generator
REM  makes", so reusing the old save would stamp holes in the new terrain that
REM  nobody dug and nobody can explain. Old save: tmp\m3-play-saves (abandoned,
REM  not deleted, in case you want to look).
REM
REM  WHAT WAS NEW IN M3 (guns and crafting, all still true):
REM    1) TWO GUNS. Your starting gear is now BOTH guns:
REM         hotbar 1 = PISTOL  SINGLE  8 dmg  12-round mag  1.2 s reload
REM         hotbar 2 = PISTOL AMMO x24  (shared by BOTH guns)
REM         hotbar 3 = SMG     AUTO    5 dmg  24-round mag  1.5 s reload
REM       Same ammo item, two guns. Switching is just the number keys.
REM    2) THIS LAUNCHER RUNS THE DEV / TEST LOADOUT (-Dskyisland.loadout=dev).
REM       On top of the two M3 guns you also get:
REM         hotbar 4 = RIFLE      SINGLE  14 dmg  10-round mag  2.0 s reload
REM         hotbar 5 = RIFLE AMMO x20
REM         backpack = DEV / TRANSITION MATERIAL KIT (log 4, iron ore 9,
REM                    copper ore 3, coal 20, sand 4, crystal 1)
REM       The material kit is a TEMPORARY stand-in: ore world generation and the
REM       real gathering chain are NOT in this milestone, so without it every
REM       recipe would read "missing xN" forever. It is deleted once that chain
REM       closes. The FORMAL M3 Survival loadout (the product default) has NO
REM       rifle and NO kit - it is the two guns only.
REM    3) CRAFTING IS LIVE. Press E to open the inventory: the RIGHT column is
REM       the recipe list. Each row shows the product, what you have / what it
REM       needs (e.g. "iron ingot 8/8"), and either a [CRAFT] button or the
REM       shortfall ("missing iron ingot x3") - a row you cannot afford always
REM       says why, it never just sits there. Clicking a row spends the
REM       materials and puts the product in your backpack.
REM    4) RESERVE AMMO IS INFINITE IN THIS LAUNCHER (by default). This is the
REM       Debug / Prototype caliber (v2 sec 19-7): reloading never drains the
REM       reserve, so you can test fire modes and reload timings back to back
REM       without ever running dry. The startup log prints which caliber is
REM       active, so this is never a guess.
REM       The FORMAL M3 Survival caliber is the opposite - FINITE reserve that
REM       really spends rounds out of your inventory (v2 sec 19-6) - and it is
REM       still the product default. This launcher only flips the switch for
REM       convenience. To play the formal caliber, either delete
REM           -Dskyisland.infiniteReserve=true
REM       from the java line near the bottom, or set it to false.
REM    5) RELOAD IS NOT INTERRUPTED BY MOVEMENT. Walk while reloading and the
REM       reload still finishes (this is deliberate, not a bug).
REM    6) AIMING DIFFERS: hold RMB -> pistol FOV 45 / move speed x0.60,
REM       SMG FOV 48 / move speed x0.65, rifle FOV 40 / move speed x0.55.
REM       These numbers are marked as "tunable in playtest" - say so if the
REM       difference feels wrong.
REM
REM  DO THESE FIRST (each one is a claim that must be checked):
REM    0. LOOK AROUND BEFORE YOU DO ANYTHING ELSE.
REM       You spawn inside the starter hut on the main island. Turn around.
REM       You should see plank walls, a glass window, and a door opening to the
REM       south. Walk out of it, and you are on a 32x32 grass island with
REM       NOTHING under you but sky.
REM       If you instead spawn on a flat 64x64 platform with a 3-block wall and
REM       a pit in it, you are running an OLD jar - rebuild, do not report a bug.
REM    1. press 3            -> the SMG is in your hand: different silhouette
REM                             and a different backpack icon than the pistol.
REM    2. ONE CLICK, then HOLD LMB:
REM         with the pistol (key 1) one click = exactly ONE shot,
REM         with the SMG    (key 3) holding  = a full burst.
REM       This contrast is the whole point of M3 - if both feel the same,
REM       the fire-mode split did NOT take. Say so.
REM    3. empty a magazine and press R -> pistol ~1.2 s, SMG ~1.5 s.
REM       While it reloads, hold W: the reload must NOT be cancelled.
REM    4. press F4 -> a monster walks up; shoot it with both guns and compare
REM       (pistol hits harder but slower; SMG is faster but weaker per shot).
REM    5. press E -> the RIGHT column is the recipe list. Craft "oak planks"
REM       from logs, then "iron ingot" (iron ore + coal), then "gunpowder"
REM       (coal x2 + sand). If a row says "missing ...", that is the point -
REM       it must name the item and the count, never stay silent.
REM    6. with the rifle (key 4): ONE CLICK = exactly ONE shot. Holding LMB
REM       must NOT keep firing (SINGLE). Compare with the SMG (key 3), where
REM       holding DOES keep firing (AUTO).
REM
REM  WHY THIS LAUNCHER IS SEPARATE FROM play-m2.bat:
REM    Starting gear is granted ONLY on a brand new world. This launcher uses
REM    its own world name and its own save dir (tmp\islands-play-saves), so the
REM    first run is guaranteed to be a new world and to grant both guns.
REM    The old m3-play save was built on the TEST PLATFORM, which no longer
REM    exists - do not reuse it.
REM
REM  KEYS: number keys switch hotbar / LMB fire / RMB aim / R reload /
REM        F2 screenshot  F3 debug overlay  F4 spawn monster  F5 save
REM        F6 resupply  F7 clear entities  F9 force respawn  ESC pause menu
REM  NOTE ON F6: it grants the WHOLE starting kit again, so you get another
REM  pistol and another SMG each time. That is existing behaviour, not a bug.
REM
REM  Exit via ESC -> pause menu -> quit, or just close the window.
REM    Both save properly. Do NOT kill it from Task Manager (that does not save).
REM
REM  Logs: the game writes its own file, see the newest one in logs\
REM ===========================================================================
REM
REM  CREATIVE MODE (all blocks, no crafting, flight) -- there is NO second
REM  launcher for it.  Double-tap SPACE in this world: that enters a creative
REM  session AND takes off immediately.  Double-tap again to stop flying, and
REM  once more to leave the session.  The session lives only for this run --
REM  it is never written to the save (PRD_BLOCK_CREATIVE 4.3').
REM  A separate creative launcher existed on 2026-10-08 and was removed the
REM  same day: two entry points for one game is one too many, and the same key
REM  behaved differently in the two worlds.
REM  See the "创造模式" section of README.md for the block-picking steps.
REM
REM  NOTE: -Dskyisland.gameMode=creative would do NOTHING here.  This save
REM  already exists, so the game reads survival from level.json and silently
REM  ignores the switch (PRD 4.3).  Do not add it -- it only looks effective.
REM
REM ===========================================================================

set "JDK_HOME=D:\software\jdk-25"
set "PROJ=%~dp0"
REM  ★ Renamed 2026-10-08 together with the world generator (see the header).
REM    Keep this in lockstep with launcher\skyisland_launcher.c - the .exe and
REM    this .bat are two entry points to the SAME play world, and when they
REM    disagree you end up with two saves that each look "wrong" for a
REM    different reason.
set "WORLD=islands-play"
set "SAVE=%PROJ%tmp\islands-play-saves"

if not exist "%JDK_HOME%\bin\java.exe" (
  echo [ERROR] JDK 25 not found at "%JDK_HOME%".
  pause
  exit /b 2
)

set "JAR="
set "JARCOUNT=0"
REM  Ignore two files on purpose:
REM    original-*.jar  = the thin pre-shade backup (678 KB, no deps bundled);
REM                     it does not match the skyisland-* glob anyway.
REM    *-shaded.jar    = maven-shade-plugin leaves a BYTE-IDENTICAL alias next
REM                      to the main artifact (verified: same sha256).
REM  They are not a second "version" -- the real risk this guard exists for is
REM  running a STALE jar after a version bump, so the check must be
REM  "exactly one REAL jar", not "exactly one file".
REM
REM  NB: the suffix test is a fixed-width compare (!CAND:~-11!), NOT a wildcard.
REM  cmd string comparison does NOT glob -- "if /i neq skyisland-*-shaded"
REM  silently matches everything and counts the alias (verified: JARCOUNT=2).
REM  Delayed expansion is what makes the substring readable inside the loop.
for %%f in ("%PROJ%target\skyisland-*.jar") do (
  set "CAND=%%~nxf"
  if not "!CAND:~-11!"=="-shaded.jar" (
    set "JAR=%%~ff"
    set /a JARCOUNT+=1
  )
)

if not "%JARCOUNT%"=="1" (
  echo [ERROR] Expected exactly 1 runnable jar in target\, found %JARCOUNT%.
  echo         original-*.jar and *-shaded.jar are ignored on purpose.
  echo         What is actually in target\:
  dir /b "%PROJ%target\*.jar"
  echo         Build it first:  node tmp/build.js clean package
  pause
  exit /b 3
)

if not exist "%PROJ%tmp" mkdir "%PROJ%tmp"
if not exist "%SAVE%" mkdir "%SAVE%"

REM  Guard against the one mistake that silently hides the SMG: if this world
REM  already exists, the starting gear is NOT re-granted and you may be
REM  looking at an old inventory. Warn loudly instead of pretending.
if exist "%SAVE%\%WORLD%\level.json" (
  echo.
  echo [WARN] A save already exists: %SAVE%\%WORLD%
  echo [WARN] Starting gear is granted ONLY on a NEW world, so this run will
  echo [WARN] load the old inventory instead. If you do not see the SMG in
  echo [WARN] hotbar slot 3, that is why. To start fresh, rename or remove:
  echo [WARN]   %SAVE%\%WORLD%
  echo [WARN] Then run this launcher again.
  echo.
  pause
)

cd /d "%PROJ%"

echo.
echo [INFO] Launching SkyIsland - skyblock islands world
echo [INFO] Jar      : %JAR%
echo [INFO] World    : %WORLD%
echo [INFO] Save dir : %SAVE%
echo.
echo [INFO] YOU SPAWN INSIDE THE STARTER HUT on the main island.
echo [INFO] Walk out the south door, then look around: void under you,
echo [INFO] and four resource islands at (48,0) (-44,12) (10,-48) (-6,52).
echo [INFO] Starting gear: 1=pistol  2=pistol ammo x24  3=SMG
echo [INFO]         (DEV loadout)     4=rifle  5=rifle ammo x20  + material kit
echo [INFO] Reserve    : INFINITE (Debug caliber, v2 sec 19-7)
echo [INFO] Check first:  look around before anything else
echo [INFO]              press 3  then  compare one-click vs hold-LMB
echo [INFO]              press E  then  craft in the RIGHT column
echo.

REM  -Dskyisland.infiniteReserve=true  -> infinite reserve for THIS launcher
REM  (Debug / Prototype caliber). Remove it (or set false) for the formal
REM  finite Survival caliber. See the REM header item 4 for why.
REM  -Dskyisland.loadout=dev           -> DEV / TEST loadout: adds the rifle,
REM  rifle ammo and the transition material kit (see header item 2). Remove it
REM  to play the formal Survival loadout: two guns, no rifle, no kit.
"%JDK_HOME%\bin\java.exe" --enable-native-access=ALL-UNNAMED %* -Dskyisland.worldName=%WORLD% -Dskyisland.settingsFile=tmp\islands-play-settings.json -Dskyisland.saveDir="%SAVE%" -Dskyisland.infiniteReserve=true -Dskyisland.loadout=dev -jar "%JAR%"
set "RC=%ERRORLEVEL%"

echo.
echo [INFO] Exit code = %RC%
echo [INFO] Full log: the newest file in logs\
pause
endlocal & exit /b %RC%
