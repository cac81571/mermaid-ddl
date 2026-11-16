@echo off
rem mermaid-ddl launcher for Windows (requires Java 17 or later on PATH)
cd /d "%~dp0"
set "JAR=target\mermaid-ddl-1.0.0-all.jar"
if exist "%JAR%" goto launch
if exist "dist\mermaid-ddl-1.0.0-win.jar" (
  set "JAR=dist\mermaid-ddl-1.0.0-win.jar"
  goto launch
)

echo JAR not found. Running "mvn package"...
call mvn package
if errorlevel 1 (
  echo "mvn package" failed.
  pause
  exit /b 1
)
set "JAR=target\mermaid-ddl-1.0.0-all.jar"
if not exist "%JAR%" (
  echo JAR was not created: %JAR%
  pause
  exit /b 1
)

:launch
start "" javaw -Dfile.encoding=UTF-8 -jar "%JAR%"
