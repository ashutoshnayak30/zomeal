@echo off
setlocal
cd /d "%~dp0.."
set "JAVA_HOME=C:\Program Files\Java\jdk-17"
set "GRADLE_USER_HOME=%CD%\.gradle-home"
set "ZOMEAL_SIGNING_PROPERTIES=D:\Codex\2026-08-09\build\Zomeal-Release-Keys\signing.properties"
if not exist "%ZOMEAL_SIGNING_PROPERTIES%" (
  echo Missing private signing configuration. Restore your original upload keys; do not create replacement keys.
  exit /b 1
)
call gradlew.bat :app:bundleRelease :provider-app:bundleRelease :provider-app:testReleaseUnitTest --no-daemon --console=plain
exit /b %errorlevel%
