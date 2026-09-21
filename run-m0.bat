@echo off
REM ===========================================================================
REM  SkyIsland - M0 Technical Spike launcher
REM
REM  Scope reminder: M0 only opens a window, runs a fixed-step loop, reads
REM  keyboard/mouse, logs, measures FPS/frame-time and shuts down cleanly.
REM  There is NO game content in M0 (no chunk, block, world, physics, save).
REM
REM  Usage:
REM    run-m0.bat                    normal 60s measurement run
REM    run-m0.bat -Dskyisland.measureSeconds=15
REM    run-m0.bat -Dskyisland.selfTest=true
REM    run-m0.bat -Dskyisland.vsync=true
REM
REM  Extra JVM flags: set SKYISLAND_JVM_ARGS before calling this script.
REM
REM  Default JVM flag (T-4, see TECH_DESIGN_v0.1.1 section A'.2):
REM    --enable-native-access=ALL-UNNAMED
REM  Removes the JDK 25 native-access warning raised by LWJGL loading its DLLs.
REM  Measured to have zero functional impact. It is noise suppression, NOT a
REM  runtime requirement: the game must also run correctly with an empty
REM  argument set.
REM ===========================================================================
setlocal

set "JDK_HOME=D:\software\jdk-25"
set "PROJ=%~dp0"
set "JAR=%PROJ%target\skyisland-0.1.0-M0.jar"
set "DEFAULT_JVM_ARGS=--enable-native-access=ALL-UNNAMED"

if not exist "%JDK_HOME%\bin\java.exe" (
  echo [ERROR] JDK 25 not found at "%JDK_HOME%".
  echo         Edit JDK_HOME in run-m0.bat, or set JAVA_HOME and rerun.
  exit /b 2
)

if not exist "%JAR%" (
  echo [ERROR] Executable jar not found:
  echo         "%JAR%"
  echo         Build it first:
  echo           mvn -s toolchain\settings.xml clean package
  exit /b 3
)

cd /d "%PROJ%"
echo [INFO] Launching SkyIsland M0 ...
"%JDK_HOME%\bin\java.exe" %DEFAULT_JVM_ARGS% %SKYISLAND_JVM_ARGS% -jar "%JAR%" %*
set "RC=%ERRORLEVEL%"
echo [INFO] Exit code = %RC%
endlocal & exit /b %RC%
