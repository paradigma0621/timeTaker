@echo off
REM Compila e empacota o TimeTaker para Java 8 no Windows 11.
setlocal

set SRC=src\main\java\com\timetaker\TimeTakerApp.java
set OUT=out

if not exist "%OUT%" mkdir "%OUT%"

echo Compilando (alvo Java 8)...
javac -encoding UTF-8 --release 8 -d "%OUT%" %SRC%
if errorlevel 1 goto :erro

echo Gerando timetaker.jar...
jar cfe timetaker.jar com.timetaker.TimeTakerApp -C "%OUT%" .
if errorlevel 1 goto :erro

echo.
echo OK. Execute com:  java -jar timetaker.jar
goto :fim

:erro
echo.
echo Falha na compilacao.
exit /b 1

:fim
endlocal
