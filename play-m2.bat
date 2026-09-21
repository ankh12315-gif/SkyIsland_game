@echo off
setlocal
REM ===========================================================================
REM  SkyIsland - M2.1 Combat Feel and Readability  (double-click me)
REM
REM  What changed since M2 (0.3.0 -> 0.3.1):
REM    1) AMMO: reserve is now INFINITE in Combat Prototype. The magazine is
REM       still 12 and you still press R to load it, but the HUD now reads
REM       "12 / infinity", and reloading no longer eats your pickups.
REM       (M3 brings finite reserve back.)
REM    2) GUN FEEL: muzzle flash, brighter tracer, impact particles,
REM       hit marker, slight recoil.
REM    3) MONSTER: the single dark red box is gone. It is now a voxel figure
REM       with head / torso / arms / legs / glowing eyes, and it flashes
REM       white when hit. NOTE: it is still RED (per-part shades), NOT grey -
REM       see docs/testing/M2_1_PLAYTEST_CHECKLIST.md item C7 and judge for
REM       yourself whether red reads better than grey on this terrain.
REM    4) VIEWMODEL: you can finally see your own gun in the bottom right
REM       corner (and your blocks, and your empty hand).
REM    5) AUDIO: gun_fire, gun_empty, reload, hit_enemy, player_hurt.
REM       The SFX slider under Settings now actually does something.
REM
REM  THE THREE STEPS THAT MUST BE DONE (skip one and you get the old reading):
REM    1. press R            -> HUD ammo: 0 / infinity  ->  12 / infinity
REM    2. look at the sky and left-click -> muzzle flash + tracer + recoil;
REM       the gun model in the bottom right kicks back
REM    3. press F4           -> a voxel monster walks up; 3 shots kill it
REM
REM  FIXED IN THIS BUILD, WORTH LOOKING AT HARD: muzzle flash, hit marker and
REM  recoil were written but never actually called (dead code: build green,
REM  815 unit tests green, all three gates green, and nothing on screen).
REM  They are wired up now. So if you still see no flash, no crosshair
REM  feedback, or a view that never kicks, the fix did NOT take - say so.
REM
REM  ALSO WORTH A LOOK THIS ROUND:
REM    - Shoot a wall: dust puffs at the impact point (blocks stay silent).
REM    - Let the monster bite you: damage feedback plus a hurt sound.
REM    - Break a block while holding a block: the viewmodel changes shape
REM      and plays a swing.
REM    - Hold RMB to aim: the gun model pulls in toward the centre.
REM
REM  If you hear nothing at all: that is a valid result on a machine with no
REM  audio device. The session log still records every sound event, and the
REM  shutdown summary prints how many of each were triggered.
REM  To force the audio subsystem off: add -Dskyisland.audio=off
REM
REM  Note: ammo is not craftable in M2.1 (that is M4). You start with a
REM  full magazine plus an infinite reserve, so ammo should never run out.
REM  F6 is still the debug resupply if you want pickups to carry around.
REM
REM  Exit via ESC -> pause menu -> quit, or just close the window.
REM    Both save properly. Do NOT kill it from Task Manager (that does not save).
REM
REM  Logs: the game writes its own file, see the newest one in logs\
REM ===========================================================================

set "JDK_HOME=D:\software\jdk-25"
set "PROJ=%~dp0"
REM  M2.1 keeps its own save dir: a brand new world is the only way to be
REM  granted the starting gear, and reading an M2-era save would also leave
REM  the player standing where the last round ended.
set "SAVE=%PROJ%tmp\m21-play-saves"

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

cd /d "%PROJ%"

echo.
echo [INFO] Launching SkyIsland M2.1 - Combat Feel and Readability
echo [INFO] Jar      : %JAR%
echo [INFO] World    : m21-play
echo [INFO] Save dir : %SAVE%
echo.
echo [INFO] Do these three first:   R    then   left-click at the sky   then   F4
echo [INFO] Ammo: 12-round magazine, INFINITE reserve (HUD shows 12 / infinity).
echo.

"%JDK_HOME%\bin\java.exe" --enable-native-access=ALL-UNNAMED %* -Dskyisland.worldName=m21-play -Dskyisland.settingsFile=tmp\m21-play-settings.json -Dskyisland.saveDir="%SAVE%" -jar "%JAR%"
set "RC=%ERRORLEVEL%"

echo.
echo [INFO] Exit code = %RC%
echo [INFO] Full log: the newest file in logs\
pause
endlocal & exit /b %RC%
