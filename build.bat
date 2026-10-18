@echo off
setlocal
if exist "C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot\bin\java.exe" set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot"
call "%~dp0gradlew.bat" build %*
exit /b %ERRORLEVEL%
