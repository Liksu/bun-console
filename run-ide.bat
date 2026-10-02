@echo off
setlocal
rem Opens a sandbox WebStorm with the plugin built from this folder (double-click or run).
rem Your own WebStorm profile and windows are not touched; the sandbox lives in build\isolated-ide.
rem In the sandbox, open the project tests\runtime\fixtures.
rem
rem WebStorm is taken from %WEBSTORM_PATH% (default: %LOCALAPPDATA%\Programs\WebStorm);
rem its bundled Java 25 runs Gradle, so JAVA_HOME does not need to be set.

cd /d "%~dp0"
if not defined WEBSTORM_PATH set "WEBSTORM_PATH=%LOCALAPPDATA%\Programs\WebStorm"
if not exist "%WEBSTORM_PATH%\jbr\bin\java.exe" (
  echo WebStorm was not found in "%WEBSTORM_PATH%".
  echo Set the WEBSTORM_PATH environment variable to the WebStorm installation folder.
  pause
  exit /b 1
)
set "JAVA_HOME=%WEBSTORM_PATH%\jbr"

call "%~dp0gradlew.bat" --console=plain "-PwebstormPath=%WEBSTORM_PATH%" runIde
if errorlevel 1 (
  echo.
  echo The sandbox IDE could not be started; see the messages above.
  pause
  exit /b 1
)
