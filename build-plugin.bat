@echo off
echo Building AI Copilot Plugin...
echo.

REM Check Java version
java -version
echo.

REM Create directories
if not exist build\classes mkdir build\classes
if not exist build\libs mkdir build\libs
if not exist build\resources mkdir build\resources

REM Copy resources
xcopy /E /Y "src\main\resources\*" "build\resources\"

REM Compile Kotlin files using kotlinc if available, otherwise show manual instructions
where kotlinc >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    echo Compiling Kotlin files...
    kotlinc -cp "build\resources" -d "build\classes" src\main\kotlin\com\example\copilot\*.kt
    
    echo Creating plugin JAR...
    cd build\classes
    jar cf ..\libs\ai-copilot-plugin.jar .
    cd ..\..
    cd build\resources
    jar uf ..\libs\ai-copilot-plugin.jar .
    cd ..
    
    echo Plugin built successfully: build\libs\ai-copilot-plugin.jar
    echo Install this JAR in IntelliJ: File ^> Settings ^> Plugins ^> Install from Disk
) else (
    echo Kotlin compiler not found.
    echo.
    echo To build this plugin, you need:
    echo 1. Kotlin compiler (kotlinc)
    echo 2. Or use IntelliJ IDEA with Gradle support
    echo 3. Or install Gradle and run: gradle build
    echo.
    echo Plugin files ready for compilation:
    dir /s src\main\kotlin\*.kt
    dir /s src\main\resources\*.xml
)

pause