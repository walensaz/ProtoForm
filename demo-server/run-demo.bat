@echo off
setlocal EnableDelayedExpansion
pushd "%~dp0.."

set "JAR_PATH=%~dp0target\scala-3.3.3\protoform-demo-server-1.0.0.jar"
set "DO_BUILD=1"
set "APP_ARGS="

:parse_args
if "%~1"=="" goto :after_args
if /i "%~1"=="--no-build" (
    set "DO_BUILD=0"
) else (
    set "APP_ARGS=!APP_ARGS! %1"
)
shift
goto :parse_args

:after_args
if "%DO_BUILD%"=="1" (
    echo Building ProtoForm Demo Server JAR...
    call sbt "demoServer/assembly"
    if errorlevel 1 (
        echo Build failed.
        popd
        exit /b 1
    )
)

if not exist "%JAR_PATH%" (
    echo Expected JAR not found: "%JAR_PATH%"
    popd
    exit /b 1
)

echo Starting ProtoForm Demo gRPC Server (standalone JVM, no sbt lock)...
java -jar "%JAR_PATH%"%APP_ARGS%
popd

