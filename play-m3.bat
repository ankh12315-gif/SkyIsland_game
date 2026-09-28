@echo off
setlocal
REM ===========================================================================
REM  SkyIsland - M3 Weapon Generalization  (pistol + SMG)   (double-click me)
REM
REM  WHAT IS NEW IN M3:
REM    1) TWO GUNS. Your starting gear is now BOTH guns:
REM         hotbar 1 = PISTOL  SINGLE  8 dmg  12-round mag  1.2 s reload
REM         hotbar 2 = PISTOL AMMO x24  (shared by BOTH guns)
REM         hotbar 3 = SMG     AUTO    5 dmg  24-round mag  1.5 s reload
REM       Same ammo item, two guns. Switching is just the number keys.
REM    2) RESERVE AMMO IS INFINITE IN THIS LAUNCHER (by default). This is the
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
REM    3) RELOAD IS NOT INTERRUPTED BY MOVEMENT. Walk while reloading and the
REM       reload still finishes (this is deliberate, not a bug).
REM    4) AIMING DIFFERS: hold RMB -> pistol FOV 45 / move speed x0.60,
REM       SMG FOV 48 / move speed x0.65. These two numbers are marked as
REM       "tunable in M3 playtest" - say so if the difference feels wrong.
REM
REM  DO THESE FOUR FIRST (each one is a claim that must be checked):
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
REM
REM  WHY THIS LAUNCHER IS SEPARATE FROM play-m2.bat:
REM    Starting gear is granted ONLY on a brand new world. This launcher uses
REM    its own world name and its own save dir (tmp\m3-play-saves), so the
REM    first run is guaranteed to be a new world and to grant both guns.
REM    The M2.1 save (tmp\m21-play-saves) contains NO SMG - reusing it would
REM    show you a pistol only. Do not reuse it to judge M3.
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

set "JDK_HOME=D:\software\jdk-25"
set "PROJ=%~dp0"
set "WORLD=m3-play"
set "SAVE=%PROJ%tmp\m3-play-saves"

if not exist "%JDK_HOME%\bin\java.exe" (
  echo [ERROR] JDK 25 not found at "%JDK_HOME%".
  pause
  exit /b 2
)

set "JAR="
set "JARCOUNT=0"
for %%f in ("%PROJ%target\skyisland-*.jar") do (
  set "JAR=%%~ff"
  set /a JARCOUNT+=1
)

if not "%JARCOUNT%"=="1" (
  echo [ERROR] Expected exactly 1 jar in target\, found %JARCOUNT%.
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
echo [INFO] Launching SkyIsland M3 - pistol + SMG
echo [INFO] Jar      : %JAR%
echo [INFO] World    : %WORLD%
echo [INFO] Save dir : %SAVE%
echo.
echo [INFO] Starting gear: 1=pistol  2=pistol ammo x24  3=SMG
echo [INFO] Reserve    : INFINITE (Debug caliber, v2 sec 19-7)
echo [INFO] Check first:  press 3  then  compare one-click vs hold-LMB
echo.

REM  -Dskyisland.infiniteReserve=true  -> infinite reserve for THIS launcher
REM  (Debug / Prototype caliber). Remove it (or set false) for the formal
REM  finite Survival caliber. See the REM header item 2 for why.
"%JDK_HOME%\bin\java.exe" --enable-native-access=ALL-UNNAMED %* -Dskyisland.worldName=%WORLD% -Dskyisland.settingsFile=tmp\m3-play-settings.json -Dskyisland.saveDir="%SAVE%" -Dskyisland.infiniteReserve=true -jar "%JAR%"
set "RC=%ERRORLEVEL%"

echo.
echo [INFO] Exit code = %RC%
echo [INFO] Full log: the newest file in logs\
pause
endlocal & exit /b %RC%
