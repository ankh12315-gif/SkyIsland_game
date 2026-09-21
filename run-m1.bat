@echo off
REM ===========================================================================
REM  SkyIsland - M2 Combat Prototype launcher
REM
REM  Scope reminder: M2 adds the combat slice on top of M1.5's front-end shell.
REM    pistol starting gear + left-click fire + R reload +
REM    right-click aim (FOV 70 -> 45, move speed x0.60) +
REM    hitscan nearest-hit resolution + distance falloff +
REM    melee monsters + health/death/respawn + hit and break particles +
REM    tracers + two-form crosshair + health bar and ammo readout +
REM    Simplified Chinese UI (self-baked CJK bitmap font).
REM  M2 does NOT add: audio, day/night cycle, hunger, crafting, extra guns,
REM    extra monster types, crouch, inventory UI.
REM
REM  Usage:
REM    run-m1.bat                       normal interactive play
REM    run-m1.bat -Dskyisland.combatSelfTest=true
REM                                     M2 combat self test, auto exit
REM    run-m1.bat -Dskyisland.uiSelfTest=true
REM                                     in-process M1.5 UI self test, auto exit
REM    run-m1.bat -Dskyisland.selfTest=true
REM                                     M1 regression self test, auto exit
REM    run-m1.bat -Dskyisland.startState=menu
REM                                     boot straight into the main menu
REM    run-m1.bat -Dskyisland.startState=playing
REM                                     boot straight into gameplay
REM    run-m1.bat -Dskyisland.settingsFile=tmp\settings.json
REM                                     use a throwaway settings file
REM    run-m1.bat -Dskyisland.measureSeconds=90 -Dskyisland.noSave=true
REM                                     performance measurement run, auto exit
REM
REM  NOTE on -D arguments:
REM    Everything passed to this script is forwarded to the JVM BEFORE -jar,
REM    so -Dkey=value really does become a system property.
REM    The game also promotes -Dkey=value program arguments itself, so both
REM    spellings work. Each override is printed in the log.
REM
REM  Extra JVM flags: set SKYISLAND_JVM_ARGS before calling this script.
REM
REM  Default JVM flag (T-4, see TECH_DESIGN_v0.1.1 section A'.2):
REM    --enable-native-access=ALL-UNNAMED
REM  Removes the JDK 25 native-access warning raised by LWJGL loading its DLLs.
REM  Noise suppression, NOT a runtime requirement.
REM
REM  M2 (G6): the jar is resolved by glob, so a pom version bump no longer
REM  requires editing this file.
REM ===========================================================================
setlocal

set "JDK_HOME=D:\software\jdk-25"
set "PROJ=%~dp0"
set "DEFAULT_JVM_ARGS=--enable-native-access=ALL-UNNAMED"

if not exist "%JDK_HOME%\bin\java.exe" (
  echo [ERROR] JDK 25 not found at "%JDK_HOME%".
  echo         Edit JDK_HOME in run-m1.bat, or set JAVA_HOME and rerun.
  exit /b 2
)

REM  Resolve the shaded jar by glob.
REM  The shim name original-skyisland-*.jar does not match skyisland-*.jar,
REM  so no extra filter is needed.
set "JAR="
set "JARCOUNT=0"
for %%f in ("%PROJ%target\skyisland-*.jar") do (
  set "JAR=%%~ff"
  set /a JARCOUNT+=1
)

if not "%JARCOUNT%"=="1" (
  echo [ERROR] Expected exactly 1 jar matching target\skyisland-*.jar, found %JARCOUNT%.
  echo         Build it first:  build-m1.bat
  echo         Stale artifacts from an earlier version are the usual cause;
  echo         a clean package clears them.
  exit /b 3
)

cd /d "%PROJ%"
echo [INFO] Launching SkyIsland M2 Combat Prototype ...
echo [INFO] Jar: %JAR%
"%JDK_HOME%\bin\java.exe" %DEFAULT_JVM_ARGS% %SKYISLAND_JVM_ARGS% %* -jar "%JAR%"
set "RC=%ERRORLEVEL%"
echo [INFO] Exit code = %RC%
endlocal & exit /b %RC%
