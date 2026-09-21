@echo off
REM ===========================================================================
REM  SkyIsland - M2 Combat Prototype build (mvn clean package)
REM
REM  Produces: target\skyisland-<version>.jar   (fat jar, shaded)
REM            <version> comes from pom.xml -> project.version
REM  Also runs the JUnit suite (surefire) before packaging.
REM
REM  Usage:
REM    build-m1.bat                  clean package
REM    build-m1.bat -DskipTests      compile + package only
REM    build-m1.bat test             tests only
REM
REM  Notes:
REM    - Toolchain is pinned locally: toolchain\settings.xml (offline mirror).
REM    - -Werror is intentionally NOT enabled: JDK 25 raises native-access
REM      warnings for LWJGL, which would abort the build for no benefit.
REM    - M2 (G6): the jar name is no longer hardcoded. It is resolved by glob
REM      after the build, so a pom version bump no longer requires editing
REM      this file. Forgetting that edit used to turn a successful build into
REM      "run-m1.bat cannot find the jar".
REM ===========================================================================
setlocal

set "JDK_HOME=D:\software\jdk-25"
set "MVN=D:\software\maven\apache-maven-3.9.9\bin\mvn.cmd"
set "PROJ=%~dp0"

if not exist "%JDK_HOME%\bin\java.exe" (
  echo [ERROR] JDK 25 not found at "%JDK_HOME%".
  exit /b 2
)
if not exist "%MVN%" (
  echo [ERROR] Maven not found at "%MVN%".
  echo         Edit MVN in build-m1.bat.
  exit /b 3
)

set "JAVA_HOME=%JDK_HOME%"
set "MAVEN_OPTS=-Dfile.encoding=UTF-8"

cd /d "%PROJ%"
if "%~1"=="" (
  echo [INFO] mvn clean package
  call "%MVN%" -s toolchain\settings.xml -B clean package
) else (
  echo [INFO] mvn %*
  call "%MVN%" -s toolchain\settings.xml -B %*
)
set "RC=%ERRORLEVEL%"

if not "%RC%"=="0" (
  echo [ERROR] Build failed with exit code %RC%.
  endlocal & exit /b %RC%
)

REM  Resolve the shaded jar by glob.
REM  The shade plugin replaces the main artifact and keeps the pre-shade copy
REM  as original-skyisland-*.jar. That name does not match skyisland-*.jar
REM  (the pattern is anchored at the start), so no extra filter is needed.
set "JARNAME="
set "JARCOUNT=0"
for %%f in ("%PROJ%target\skyisland-*.jar") do (
  set "JARNAME=%%~nxf"
  set /a JARCOUNT+=1
)

if "%JARCOUNT%"=="1" (
  echo [INFO] Artifact: target\%JARNAME%
  echo [INFO] Run it with: run-m1.bat
  endlocal & exit /b 0
)
if "%JARCOUNT%"=="0" (
  echo [INFO] No skyisland-*.jar in target\ - expected for a test-only run.
  endlocal & exit /b 0
)

echo [ERROR] Expected at most 1 jar matching target\skyisland-*.jar, found %JARCOUNT%.
echo         Stale artifacts from an earlier version are the usual cause.
echo         Fix:  build-m1.bat clean package
endlocal & exit /b 4
