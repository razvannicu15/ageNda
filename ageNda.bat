@echo off
chcp 65001 >nul
title ageNda by norAz
set REPO=razvannicu15/ageNda
set DIR=D:\Apps\ageNda

:menu
cls
echo.
echo   ===============================
echo      ageNda  -  by norAz
echo   ===============================
echo.
echo   1. Descarca ultimul APK pe PC
echo   2. Trimite modificarile pe GitHub (porneste build-ul)
echo   3. Deschide pagina de build (Actions)
echo   4. Instaleaza APK pe telefon (ADB / cablu USB)
echo   5. Deschide folderul proiectului
echo   0. Iesire
echo.
set /p c=  Alege: 
if "%c%"=="1" goto dl
if "%c%"=="2" goto push
if "%c%"=="3" start https://github.com/%REPO%/actions & goto menu
if "%c%"=="4" goto adb
if "%c%"=="5" start "" "%DIR%" & goto menu
if "%c%"=="0" exit /b
goto menu

:dl
echo.
echo   Descarc ageNda.apk ...
curl -L --fail -o "%DIR%\ageNda.apk" https://github.com/%REPO%/releases/latest/download/ageNda.apk
if errorlevel 1 (echo   Eroare: build-ul nu e gata sau repo-ul e privat.) else (echo   Gata: %DIR%\ageNda.apk & start "" "%DIR%")
pause
goto menu

:push
cd /d "%DIR%"
git add -A
git commit -m "update %date% %time%"
git push
echo.
echo   Trimis. Build-ul dureaza ~4 minute, apoi alege 1.
pause
goto menu

:adb
where adb >nul 2>nul || (echo   ADB nu e instalat. Copiaza APK-ul pe telefon manual. & pause & goto menu)
adb install -r "%DIR%\ageNda.apk"
pause
goto menu
