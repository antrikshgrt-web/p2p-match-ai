@echo off
REM P2P Match AI - compile and start. Requires a JDK 17+ (javac on PATH).
setlocal
set PORT=%1
if "%PORT%"=="" set PORT=8080
cd /d "%~dp0"

where javac >nul 2>nul
if errorlevel 1 (
  echo javac not found. Install a JDK 17 or later ^(a JRE is not enough^).
  exit /b 1
)

echo Compiling...
if exist out rmdir /s /q out
mkdir out
dir /s /b src\*.java > sources.txt
javac -d out @sources.txt
del sources.txt
echo Starting on port %PORT% ...
java -cp out com.p2pmatchai.App %PORT%
