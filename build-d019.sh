#!/usr/bin/env bash
# Build the D-019 extension jar (JDK 17) — build/libs/aidial-keycloak-mappers-<version>.jar
#
# NOTE: `check` runs checkstyle 13.3, which needs a JDK newer than 17 (class file 65
# = Java 21). On a JDK 17 machine, skip it — upstream CI enforces checkstyle:
#   ./build-d019.sh -x checkstyleMain -x checkstyleTest
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
./gradlew clean build check --console=plain "$@"
