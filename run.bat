@echo off
setlocal
pushd "%~dp0"

echo Building ProtoForm...
call sbt assembly
if errorlevel 1 (
    echo Build failed.
    popd
    exit /b 1
)

set "JAR_PATH=%~dp0target\scala-3.3.7\protoform-1.0.0.jar"
if not exist "%JAR_PATH%" (
    echo Expected JAR not found: "%JAR_PATH%"
    popd
    exit /b 1
)

echo Launching ProtoForm...
start "" javaw -jar "%JAR_PATH%"
popd
