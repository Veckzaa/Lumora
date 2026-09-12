#!/usr/bin/env bash
# Regenerates app/src/main/baseline-prof.txt (perf plan S1): the classes and methods ART compiles
# ahead of time once a device's background dexopt has run, instead of interpreting them at start.
#
#   tools/perf/baseline-profile.sh
#
# Needs one emulator on API 28+ that allows `adb root` (an image without Play Store, e.g. the
# FireTV_API28 AVD): below API 33 the profile ART recorded can only be read as root, and profman
# can't dump it at all before API 28. The profile names classes, not a package, so it is recorded
# on the benchmark build with the seeded 50k catalogue baseline.sh uses and applies to every build
# type that isn't minified. Debuggable builds ignore it - that's what the `stick` build type is for.
set -euo pipefail

cd "$(dirname "$0")/../.."
: "${ANDROID_HOME:=$HOME/Android/Sdk}"
: "${JAVA_HOME:=/usr/lib/jvm/java-17-openjdk-amd64}"
export ANDROID_HOME JAVA_HOME
ADB="$ANDROID_HOME/platform-tools/adb"
PKG=com.lumora.bench
RUNS=3
OUT=app/src/main/baseline-prof.txt

[ "$($ADB shell getprop ro.build.version.sdk | tr -d '\r')" -ge 28 ] || { echo "Needs an API 28+ device"; exit 1; }
$ADB root >/dev/null; $ADB wait-for-device
[ "$($ADB shell id -u | tr -d '\r')" = 0 ] || { echo "adb root was refused - use an emulator image without Play Store"; exit 1; }

echo "== Building and installing the benchmark build"
./gradlew -q --console=plain :app:assembleBenchmark :app:assembleBenchmarkAndroidTest 2>&1 | grep -vE '^w: |^Note: ' || true
$ADB install -r "app/build/outputs/apk/benchmark/app-$($ADB shell getprop ro.product.cpu.abi | tr -d '\r')-benchmark.apk" >/dev/null
$ADB install -r -t app/build/outputs/apk/androidTest/benchmark/app-benchmark-androidTest.apk >/dev/null
RUNNER=$($ADB shell pm list instrumentation | tr -d '\r' | sed -n "s/^instrumentation:\([^ ]*\) (target=$PKG)$/\1/p" | head -1)
$ADB shell am instrument -w -e class com.lumora.perf.SeedStartupCatalogue "$RUNNER" | tr -d '\r' | grep -q '^OK (' ||
  { echo "Seeding the catalogue failed"; exit 1; }

# Start from nothing recorded, and keep profileinstaller from adding the profile already in the
# APK: a regenerated profile would otherwise carry every stale entry of the previous one forward.
$ADB shell am force-stop $PKG
$ADB shell cmd package compile --reset $PKG >/dev/null
$ADB shell am broadcast -a androidx.profileinstaller.action.SKIP_FILE --es EXTRA_SKIP_FILE_OPERATION WRITE_SKIP_FILE \
  $PKG/androidx.profileinstaller.ProfileInstallReceiver >/dev/null

# D-pad presses as baseline.sh sends them: straight to the input device, 50 ms apart.
INPUT_DEV=$($ADB shell getevent -pl | tr -d '\r' | awk '/add device/ {d=$4} /KEY_DOWN/ && !p {print d; p=1}')
press() { # $1 = Linux key code, $2 = count
  $ADB shell "for i in \$(seq $2); do sendevent $INPUT_DEV 1 $1 1; sendevent $INPUT_DEV 0 0 0; sendevent $INPUT_DEV 1 $1 0; sendevent $INPUT_DEV 0 0 0; sleep 0.05; done"
}

echo "== Recording $RUNS runs: cold start, guide scroll, Series/Movies/Home shelves"
for run in $(seq $RUNS); do
  echo "   run $run"
  $ADB shell am force-stop $PKG
  $ADB logcat -c
  $ADB shell am start -W -n "$PKG/com.lumora.MainActivity" >/dev/null
  # The films/series half lands after first content; its shelves are part of what a start runs.
  timeout 90 "$ADB" logcat -s LumoraPerf:I -e 'deriveFilmsSeriesHalf' -m 1 >/dev/null || true
  press 106 1; press 108 40; press 103 40   # into the guide, 40 rows down and back
  for x in 540 750 960; do                  # Series, Movies, Home tabs (1080p layout)
    $ADB shell input tap $x 66
    $ADB shell sleep 2
    press 108 3; press 106 10; press 105 10
  done
  # SIGUSR1 makes ART write what it recorded now; force-stop would drop the unsaved part.
  $ADB shell killall -s SIGUSR1 $PKG
  $ADB shell sleep 2
done

# The compile merges the recorded profile into the reference one, which profman then prints.
$ADB shell cmd package compile -f -m speed-profile $PKG >/dev/null
APK=$($ADB shell pm path $PKG | tr -d '\r' | sed -n 's/^package://p' | head -1)
$ADB shell profman --dump-classes-and-methods --profile-file=/data/misc/profiles/ref/$PKG/primary.prof --apk="$APK" |
  tr -d '\r' | grep -E '^(H?S?P?)L' | sort -u > "$OUT"
echo "$(wc -l < "$OUT") entries -> $OUT"
