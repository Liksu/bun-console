@echo off
setlocal
rem Runs all tests: the Bun runtime tests, then the IDE tests in a headless WebStorm.
rem
rem   test.bat                                         all tests
rem   test.bat --tests dev.bunconsole.ConsoleImportsTest  one IDE test class (extra arguments go to Gradle)
rem
rem WebStorm is taken from %WEBSTORM_PATH% (default: %LOCALAPPDATA%\Programs\WebStorm);
rem its bundled Java 25 runs Gradle, so JAVA_HOME does not need to be set.

cd /d "%~dp0"
if not defined WEBSTORM_PATH set "WEBSTORM_PATH=%LOCALAPPDATA%\Programs\WebStorm"

set "BUN=bun"
where bun >nul 2>nul || set "BUN=%USERPROFILE%\.bun\bin\bun.exe"
if not "%BUN%"=="bun" if not exist "%BUN%" (
  echo Bun was not found on PATH or in %USERPROFILE%\.bun\bin. Install Bun 1.4 or newer: https://bun.sh
  exit /b 1
)

echo.
echo === Runtime tests (Bun) ===
call "%BUN%" test tests\runtime\bootstrap.test.mjs || exit /b 1

echo.
echo === IDE tests (Gradle, headless WebStorm) ===
if exist "%WEBSTORM_PATH%\jbr\bin\java.exe" (
  set "JAVA_HOME=%WEBSTORM_PATH%\jbr"
  call "%~dp0gradlew.bat" --console=plain "-PwebstormPath=%WEBSTORM_PATH%" test %*
) else (
  echo WebStorm was not found in "%WEBSTORM_PATH%": Gradle downloads WebStorm 2026.2.3,
  echo and JAVA_HOME must point to Java 25. Set WEBSTORM_PATH to use an installed WebStorm.
  call "%~dp0gradlew.bat" --console=plain test %*
)
exit /b %ERRORLEVEL%
