#!/bin/sh
set -eu
exec mvn spring-boot:run "$@"
