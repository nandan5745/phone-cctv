#!/bin/sh
# Runs the JVM unit tests for the Android-free `core` package, on the Mac, in seconds.
# Needs kotlinc (brew install kotlin) and Java. CI runs the same tests through Gradle.
# Usage: scripts/jvmtest.sh            (exit code 0 = all passed)
set -e
cd "$(dirname "$0")/.."

CACHE="$HOME/.cache/cctv-test"
MAVEN="https://repo1.maven.org/maven2"
mkdir -p "$CACHE"

# Test-only jars, downloaded once and checked against Maven Central's SHA-1 files.
fetch() { # group-path artifact version
  jar="$CACHE/$2-$3.jar"
  [ -f "$jar" ] && return 0
  url="$MAVEN/$1/$2/$3/$2-$3.jar"
  curl -fsSL "$url" -o "$jar.tmp"
  want=$(curl -fsSL "$url.sha1" | cut -c1-40)
  got=$(shasum -a 1 "$jar.tmp" | cut -c1-40)
  [ "$want" = "$got" ] || { rm -f "$jar.tmp"; echo "checksum mismatch for $2"; exit 1; }
  mv "$jar.tmp" "$jar"
}
fetch junit junit 4.13.2
fetch org/hamcrest hamcrest-core 1.3
fetch org/json json 20231013
CP="$CACHE/junit-4.13.2.jar:$CACHE/hamcrest-core-1.3.jar:$CACHE/json-20231013.jar"

SRC=android/app/src/main/java/com/nandan/cctv/core
TEST=android/app/src/test/java
OUT="$CACHE/build"
rm -rf "$OUT" && mkdir -p "$OUT"

# Compiling core without the Android SDK is also the check that core has no android.* imports.
kotlinc $SRC/*.kt $(find "$TEST" -name '*Test.kt') -cp "$CP" -d "$OUT/tests.jar" -include-runtime -nowarn 2>&1 \
  | grep -v '^warning' || true
[ -f "$OUT/tests.jar" ] || { echo "COMPILE FAILED"; exit 1; }

CLASSES=$(find "$TEST" -name '*Test.kt' | sed "s#^$TEST/##; s#\.kt\$##; s#/#.#g")
java -cp "$OUT/tests.jar:$CP" org.junit.runner.JUnitCore $CLASSES
