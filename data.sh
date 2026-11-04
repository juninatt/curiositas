#!/bin/sh
# Runs a data command against the local database, for example: ./data.sh export
# Docker must be running. The command runs without a web server and needs no admin password.
if [ -z "$1" ]; then
    echo "Usage: ./data.sh <command>"
    echo "Commands: export, import, check, fetch"
    exit 1
fi
cd "$(dirname "$0")" || exit 1
exec ./mvnw --quiet --batch-mode spring-boot:run -Dspring-boot.run.profiles=data-command \
    "-Dspring-boot.run.arguments=--curiositas.data.command=$1"
