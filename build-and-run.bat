@echo off
echo Building AI Copilot Plugin...

REM Try to use gradle if available
where gradle >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    echo Using system Gradle...
    gradle build
    gradle runIde
    goto :end
)

REM Try to use gradlew if available
if exist gradlew.bat (
    echo Using Gradle wrapper...
    gradlew.bat build
    gradlew.bat runIde
    goto :end
)

echo No Gradle found. Please install Gradle or use IntelliJ IDEA to open this project.
echo Plugin structure:
echo - Plugin ID: com.example.ai.copilot
echo - Main class: com.example.copilot.CopilotAction
echo - Function: Adds "AI Copilot Demo" action to editor context menu

:end
pause