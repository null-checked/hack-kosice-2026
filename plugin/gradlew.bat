@rem Minimal Gradle wrapper launcher for Windows.
@rem If gradle-wrapper.jar is missing, run `gradle wrapper --gradle-version 8.10.2`
@rem once to regenerate it, then use the regular `gradlew.bat` afterwards.

@echo off
setlocal

set DIR=%~dp0
set WRAPPER_JAR=%DIR%gradle\wrapper\gradle-wrapper.jar

if not exist "%WRAPPER_JAR%" (
  echo gradle-wrapper.jar is missing. Bootstrap with:
  echo   cd /d "%DIR%" ^&^& gradle wrapper --gradle-version 8.10.2
  exit /b 1
)

java -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
