#!/bin/sh
set -eu
PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$PROJECT_ROOT"
export TMPDIR="$PROJECT_ROOT/tmp/work"
export TMP="$TMPDIR" TEMP="$TMPDIR"
export GRADLE_USER_HOME="$PROJECT_ROOT/tmp/gradle-home"
export ANDROID_USER_HOME="$PROJECT_ROOT/tmp/android-user"
export XDG_CACHE_HOME="$PROJECT_ROOT/tmp/cache"
export XDG_CONFIG_HOME="$PROJECT_ROOT/tmp/config"
mkdir -p "$TMPDIR" "$GRADLE_USER_HOME" "$ANDROID_USER_HOME" "$PROJECT_ROOT/tmp/home" "$PROJECT_ROOT/tmp/keys"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17 or 21}"
: "${ANDROID_HOME:?Set ANDROID_HOME to an Android SDK with platform 34 and build-tools 34.0.0}"
if [ "$#" -eq 0 ]; then
    set -- testDebugUnitTest lintDebug packageDebugApk
fi
sh ./gradlew --no-daemon --project-cache-dir "$PROJECT_ROOT/tmp/gradle-project" \
    "-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=$TMPDIR -Duser.home=$PROJECT_ROOT/tmp/home" \
    "-Pkotlin.project.persistent.dir=$PROJECT_ROOT/tmp/kotlin" "$@"
