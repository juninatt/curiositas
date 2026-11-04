@echo off
rem Runs a data command against the local database, for example: data.cmd export
rem Docker must be running. The command runs without a web server and needs no admin password.
if "%~1"=="" (
    echo Usage: data.cmd ^<command^>
    echo Commands: export, import, check, fetch
    exit /b 1
)
pushd "%~dp0"
call "%~dp0mvnw.cmd" --quiet --batch-mode spring-boot:run "-Dspring-boot.run.profiles=data-command" "-Dspring-boot.run.arguments=--curiositas.data.command=%~1"
set EXIT_CODE=%ERRORLEVEL%
popd
exit /b %EXIT_CODE%
