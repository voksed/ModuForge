@echo off
rem Runs the module packer from the project root: mfrg keygen|init|pack|verify ...
setlocal
set "PACKER=%~dp0tools\packer\build\install\mfrg\bin\mfrg.bat"
if not exist "%PACKER%" (
    echo The packer is not built yet. Run: gradlew :tools:packer:installDist
    exit /b 1
)
if "%JAVA_HOME%"=="" (
    where java >nul 2>nul
    if errorlevel 1 (
        echo Java 17 or newer is required. Install a JDK and set JAVA_HOME, or add java to PATH.
        exit /b 1
    )
)
call "%PACKER%" %*
