#!/usr/bin/env bash
# Builds and tests Quarry with nothing but a JDK (21+). No Maven or Gradle needed.
#   ./build.sh          compile, run tests, package build/quarry.jar
#   ./build.sh compile  compile only
set -euo pipefail
cd "$(dirname "$0")"
rm -rf build/classes build/test-classes
mkdir -p build/classes build/test-classes
javac --release 21 -Xlint:all -Werror -d build/classes $(find src/main/java -name '*.java')
if [[ "${1:-}" == "compile" ]]; then exit 0; fi
javac --release 21 -d build/test-classes -cp build/classes $(find src/test/java -name '*.java')
java -ea -cp build/classes:build/test-classes quarry.TestRunner
jar --create --file build/quarry.jar --main-class quarry.Cli -C build/classes .
echo "Built build/quarry.jar. Try: java -jar build/quarry.jar"
