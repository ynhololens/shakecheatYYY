@echo off
chcp 65001 >nul
echo ====================================
echo  摇一摇作弊模块 - 一键打包安装
echo ====================================
echo.

set JAVA_HOME=C:\Users\YNHol\AppData\Local\Temp\jdk17fix
set GRADLE_OPTS=-Dorg.gradle.daemon=false

cd /d "%~dp0"

echo [1/2] 编译 Debug APK...
call .\gradlew.bat assembleDebug
if %errorlevel% neq 0 (
    echo 编译失败，请检查错误信息
    pause
    exit /b %errorlevel%
)

echo [2/2] 安装到手机...
adb install -r "app\build\outputs\apk\debug\app-debug.apk"
if %errorlevel% equ 0 (
    echo.
    echo 安装成功！
    echo 请打开 LSPosed Manager 确认模块已启用
) else (
    echo 安装失败，请确认手机已连接 USB 并开启调试
)

pause
