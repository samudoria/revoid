#!/bin/bash
set -e
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$DIR"
echo "Building test-app sample.jar..."
mkdir -p build/classes
javac -d build/classes $(find src -name "*.java")
cp AndroidManifest.xml build/classes/
cd build/classes
jar cf ../sample.jar .
echo "Successfully built sample.jar at $DIR/build/sample.jar"
