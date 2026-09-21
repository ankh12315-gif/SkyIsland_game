@echo off
REM Build SkyIsland (M0 gate #1: mvn clean package).
setlocal

set "JDK_HOME=D:\software\jdk-25"
set "MAVEN_HOME=D:\software\maven\apache-maven-3.9.9"
set "PROJ=%~dp0"

set "JAVA_HOME=%JDK_HOME%"
set "PATH=%JDK_HOME%\bin;%MAVEN_HOME%\bin;%PATH%"
set "MAVEN_OPTS=-Dfile.encoding=UTF-8"

cd /d "%PROJ%"
call "%MAVEN_HOME%\bin\mvn.cmd" -s "%PROJ%toolchain\settings.xml" -B clean package
set "RC=%ERRORLEVEL%"
echo [INFO] Build exit code = %RC%
endlocal & exit /b %RC%
