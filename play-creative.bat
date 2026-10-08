@echo off
setlocal enabledelayedexpansion
REM ===========================================================================
REM  SkyIsland - CREATIVE play entry point   (double-click me)
REM
REM  READ THIS IF YOU ARE WONDERING WHY THERE IS A SECOND LAUNCHER
REM
REM  Creative mode is NOT a toggle you can flip inside a running game, and that
REM  is deliberate. PRD_BLOCK_CREATIVE section 4.3 ruled:
REM
REM      the mode is decided when the save is CREATED and never changes.
REM
REM  So "-Dskyisland.gameMode=creative" only works on a save that does not
REM  exist yet. Adding it to play.bat would do NOTHING, because that save
REM  already exists - the game reads level.json, sees survival, and (correctly)
REM  ignores your switch. The symptom is nasty: everything looks normal, the
REM  inventory has no 创造 tab, and the only clue is one log line.
REM
REM  => This launcher exists because it needs its OWN world and save dir:
REM         world    : islands-creative   (NOT islands-play)
REM         save dir : tmp\islands-creative-saves
REM     Two separate worlds, so switching between them never fights the lock.
REM
REM  HOW TO PICK BLOCKS - this is the part that is not obvious:
REM
REM    1. Press E. In creative mode the inventory has TWO tabs: 背包 / 创造.
REM    2. Click the 创造 tab. You get a grid of every placeable block.
REM    3. LEFT-CLICK a block. A stack of 64 appears ON THE CURSOR (it follows
REM       the mouse). Per spec it does NOT occupy a backpack slot.
REM    4. ★ Switch BACK to the 背包 tab and LEFT-CLICK one of the 9 HOTBAR slots
REM       along the bottom row. That moves the 64 into that slot.
REM       -> While you are ON the 创造 tab, the hotbar is not clickable. That is
REM          not a bug you should report: the creative page returns before the
REM          slot hit-test runs, so a click there only ever picks a block.
REM    5. Press E to close, then press that slot's number key (1..9).
REM    6. RIGHT-CLICK in the world to place. Unlimited - placement does not
REM       consume the stack (PRD 5.3).
REM
REM    Two shortcuts worth knowing:
REM      - You only need to do steps 2-4 ONCE per block type you want.
      - Scroll wheel cycles the hotbar while you hold nothing, so 9 different
REM        block types can be kept ready at once.
REM
REM  WHAT CREATIVE MODE GIVES YOU (PRD section 5):
REM    - every placeable block, no crafting needed
REM    - instant break (hold left mouse to keep breaking)
REM    - placement never consumes the stack
REM    - no damage taken, and falling into the void parks you at the bottom
REM      instead of killing you
REM    - double-tap SPACE to fly; hold SHIFT while flying to descend
REM
REM  ★ CREATIVE GIVES YOU NO GUNS (PRD 5.6: guns are a survival-only grant).
REM    That is why this launcher has no -Dskyisland.loadout=dev.
REM    The two play worlds are genuinely different: survival has guns +
REM    crafting, creative has blocks + flight.
REM
REM  IF CREATIVE SILENTLY DID NOT TURN ON, CHECK THIS FIRST:
REM    does tmp\islands-creative-saves\islands-creative\level.json already
REM    exist? If it does, that world is locked to whatever mode it was created
REM    with. Delete or rename that folder to get a fresh creative world.
REM    This launcher warns you loudly when that happens.
REM ===========================================================================

set "JDK_HOME=D:\software\jdk-25"
set "PROJ=%~dp0"
REM  ★ These three must stay in lockstep with each other, and they are also the
REM    values tmp\check_creative_sync.js asserts on. The world name must NOT be
REM    islands-play: sharing it would make creative mode impossible, because the
REM    survival save would already own that name.
set "WORLD=islands-creative"
set "SAVE=%PROJ%tmp\islands-creative-saves"

if not exist "%JDK_HOME%\bin\java.exe" (
  echo [ERROR] JDK 25 not found at "%JDK_HOME%".
  pause
  exit /b 2
)

set "JAR="
set "JARCOUNT=0"
REM  Same single-real-jar rule as play.bat, same reason: two DIFFERENT
REM  skyisland-*.jar files means a stale build, and guessing is how you end up
REM  testing last week's bytecode. original-* (thin backup) and *-shaded.jar
REM  (byte-identical alias) are ignored on purpose.
REM  The suffix test is a fixed-width compare (!CAND:~-11!), NOT a wildcard:
REM  cmd string comparison does NOT glob, so a wildcard would count the alias.
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

REM  ★★ The trap this whole file exists for. PRD 4.3: the mode is fixed when the
REM  save is created. So if this world already exists it is locked, and
REM  -Dskyisland.gameMode=creative will be silently ignored -- the game looks
REM  completely normal except the 创造 tab is missing. Warn instead of letting
REM  someone spend ten minutes wondering whether the build is broken.
if exist "%SAVE%\%WORLD%\level.json" (
  echo.
  echo [WARN] This creative world ALREADY EXISTS: %SAVE%\%WORLD%
  echo [WARN] The game mode is fixed when a save is created (PRD 4.3), so this
  echo [WARN] run will keep whatever mode it was created with. If the CREATIVE
  echo [WARN] tab is missing, that is why - it is not a broken build.
  echo [WARN] To get a fresh creative world, remove or rename:
  echo [WARN]   %SAVE%\%WORLD%
  echo [WARN] Then run this launcher again.
  echo.
  pause
)

cd /d "%PROJ%"

echo.
echo [INFO] Launching SkyIsland - CREATIVE
echo [INFO] Jar      : %JAR%
echo [INFO] World    : %WORLD%
echo [INFO] Save dir : %SAVE%
echo.
echo [INFO] Creative powers: instant break / unlimited blocks / no damage /
echo [INFO]                  void is safe / double-tap SPACE to fly
echo [INFO] No guns here - guns are a survival-only grant (PRD 5.6).
echo.
echo [INFO] TO PICK BLOCKS:
echo [INFO]   E -> click the CREATIVE tab -> LEFT-CLICK a block (64 on cursor)
echo [INFO]   -> click the BACKPACK tab -> LEFT-CLICK a hotbar slot below
echo [INFO]   -> E to close -> press that slot number -> RIGHT-CLICK to place
echo [INFO] The hotbar is NOT clickable while you are on the CREATIVE tab.
echo.

REM  -Dskyisland.gameMode=creative -> the ONLY thing that switches the mode, and
REM  only on a save that does not exist yet. Without it this file would launch
REM  an ordinary survival world and the 创造 tab would never appear.
"%JDK_HOME%\bin\java.exe" --enable-native-access=ALL-UNNAMED %* -Dskyisland.gameMode=creative -Dskyisland.worldName=%WORLD% -Dskyisland.settingsFile=tmp\islands-creative-settings.json -Dskyisland.saveDir="%SAVE%" -jar "%JAR%"
set "RC=%ERRORLEVEL%"

echo.
echo [INFO] Exit code = %RC%
echo [INFO] Full log: the newest file in logs\
pause
endlocal & exit /b %RC%