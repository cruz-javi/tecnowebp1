@echo off
REM Uso: scripts\run.bat [init|daemon]
cd /d "%~dp0\.."
if not exist target\unexo.jar call mvn -q -DskipTests package
if "%1"=="init" (
  java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp target\unexo.jar unexo.App init
) else (
  java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -jar target\unexo.jar
)
