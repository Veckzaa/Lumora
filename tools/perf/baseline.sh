#!/usr/bin/env bash
# Lumora performance baseline - see docs/perf/OPTIMIZATION_PLAN.md for what each metric means.
#
#   tools/perf/baseline.sh <label> [reps]      e.g. tools/perf/baseline.sh before-c1
#
# Needs exactly one device or emulator connected. Builds and installs the `benchmark` build type,
# then collects:
#   B1-B8  micro benchmarks, each in its own process, [reps] times (default 3)
#   M1-M4  10 cached cold starts: first content, launch time, memory, logged GCs
#   M5     frame stats over a scripted D-pad scroll
#   Z1     APK size
# Results: tools/perf/results/<label>-<timestamp>/summary.md (plus the raw files next to it).
set -euo pipefail

LABEL=${1:?usage: tools/perf/baseline.sh <label> [reps]}
REPS=${2:-3}
STARTS=10
PKG=com.lumora.bench
BENCHES=(
  CatalogueCacheBench#saveThenLoad50k
  ParserBench#m3uParse50k
  ParserBench#xtreamLive30k
  ParserBench#xmltvParse50k
  EpgStoreBench#replace50kRows
  GroupingBench#liveGrouping30k
  GroupingBench#vodGrouping20k
)

cd "$(dirname "$0")/../.."
: "${ANDROID_HOME:=$HOME/Android/Sdk}"
: "${JAVA_HOME:=/usr/lib/jvm/java-17-openjdk-amd64}"
export ANDROID_HOME JAVA_HOME
ADB="$ANDROID_HOME/platform-tools/adb"
OUT="tools/perf/results/$LABEL-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"
RAW="$OUT/bench.tsv"
STARTUP="$OUT/startup.tsv"

echo "== Building benchmark APKs"
./gradlew -q --console=plain :app:assembleBenchmark :app:assembleBenchmarkAndroidTest 2>&1 | grep -vE '^w: |^Note: ' || true
APP_APK=app/build/outputs/apk/benchmark/app-benchmark.apk
TEST_APK=app/build/outputs/apk/androidTest/benchmark/app-benchmark-androidTest.apk
$ADB install -r "$APP_APK" >/dev/null
$ADB install -r -t "$TEST_APK" >/dev/null
RUNNER=$($ADB shell pm list instrumentation | tr -d '\r' | sed -n "s/^instrumentation:\([^ ]*\) (target=$PKG)$/\1/p" | head -1)
[ -n "$RUNNER" ] || { echo "No instrumentation installed for $PKG"; exit 1; }
device="$($ADB shell getprop ro.product.model | tr -d '\r') · API $($ADB shell getprop ro.build.version.sdk | tr -d '\r') · $($ADB shell getprop ro.product.cpu.abi | tr -d '\r')"

# Runs one test in a fresh process; fails the script if the test failed.
instrument() {
  local log="$OUT/instrument-${1//#/-}.log"
  $ADB shell am instrument -w -e class "com.lumora.perf.$1" "$RUNNER" | tr -d '\r' > "$log"
  grep -q '^OK (' "$log" || { echo "!! $1 failed - see $log"; exit 1; }
}

echo "== Micro benchmarks x$REPS on $device"
printf 'kind\tname\tphase\trep\tms\talloc_mb\tgc_count\tgc_ms\n' > "$RAW"
for rep in $(seq "$REPS"); do
  for bench in "${BENCHES[@]}"; do
    echo "   rep $rep: $bench"
    $ADB logcat -c
    instrument "$bench"
    $ADB logcat -d -s LumoraBench:I | tr -d '\r' | awk -v rep="$rep" '
      { i = index($0, "BENCH\t"); if (i) { split(substr($0, i + 6), f, "\t"); printf "bench\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n", f[1], f[2], rep, f[3], f[4], f[5], f[6] } }' >> "$RAW"
  done
done

echo "== Cold starts x$STARTS (seeded 50k catalogue, no network)"
instrument SeedStartupCatalogue
start_once() {
  $ADB shell am force-stop "$PKG"
  $ADB logcat -c
  local total first pid gcs pss java native
  total=$($ADB shell am start -W -n "$PKG/com.lumora.MainActivity" | tr -d '\r' | sed -n 's/^TotalTime: *//p')
  first=$(timeout 90 "$ADB" logcat -s LumoraPerf:I -e 'FIRST CONTENT' -m 1 | tr -d '\r' | sed -n 's/.*FIRST CONTENT: \([0-9]*\)ms.*/\1/p' || true)
  # The films/series half (and its derived-cache write) lands after first content.
  timeout 90 "$ADB" logcat -s LumoraPerf:I -e 'deriveFilmsSeriesHalf' -m 1 >/dev/null || true
  $ADB shell sleep 5
  pid=$($ADB shell pidof "$PKG" | tr -d '\r')
  gcs=$($ADB logcat -d | grep -c " $pid .*GC freed" || true)
  read -r pss java native <<<"$($ADB shell dumpsys meminfo "$PKG" | tr -d '\r' |
    awk '/Java Heap:/ {j=$3} /Native Heap:/ {n=$3} /^ *TOTAL:/ {t=$2} END {print t, j, n}')"
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$first" "$total" "$pss" "$java" "$native" "$gcs"
}
start_once >/dev/null   # warm-up: builds the derived caches a normal second launch has
printf 'run\tfirst_content_ms\tlaunch_total_ms\tpss_kb\tjava_heap_kb\tnative_heap_kb\tlogged_gcs\n' > "$STARTUP"
for run in $(seq "$STARTS"); do
  line=$(start_once)
  echo "   start $run: $line"
  printf '%s\t%s\n' "$run" "$line" >> "$STARTUP"
done

echo "== Scroll jank"
$ADB shell dumpsys gfxinfo "$PKG" reset >/dev/null
for key in $(for _ in $(seq 30); do echo KEYCODE_DPAD_DOWN; done) $(for _ in $(seq 30); do echo KEYCODE_DPAD_UP; done); do
  $ADB shell input keyevent "$key"
done
$ADB shell dumpsys gfxinfo "$PKG" | tr -d '\r' | grep -E 'Total frames rendered|Janky frames|(50|90|95|99)th percentile' | sed 's/^ *//' > "$OUT/jank.txt"

# ── Summary ───────────────────────────────────────────
median() { sort -g | awk '{v[NR]=$1} END {print NR ? v[int((NR+1)/2)] : "n/a"}'; }
# The median-time row for one bench/phase, so its alloc/GC columns come from that same run.
median_row() {
  awk -F'\t' -v n="$1" -v p="$2" '$2==n && $3==p' "$RAW" | sort -t$'\t' -k5,5g |
    awk '{r[NR]=$0} END {if (NR) print r[int((NR+1)/2)]}'
}
col() { tail -n +2 "$STARTUP" | cut -f"$1" | grep -v '^$' | median; }

apk_bytes=$(stat -c %s "$APP_APK")
v7a_bytes=$(unzip -l "$APP_APK" 'lib/armeabi-v7a/*' | tail -1 | awk '{print $1}')
dirty=$(git diff --quiet HEAD -- app || echo " + uncommitted changes in app/")

{
  echo "# Perf baseline: $LABEL"
  echo
  echo "- Date: $(date '+%Y-%m-%d %H:%M')"
  echo "- Commit: $(git rev-parse --short HEAD)$dirty"
  echo "- Device: $device"
  echo "- Micro benchmark reps: $REPS · cold starts: $STARTS"
  echo
  echo "## Micro benchmarks (median across reps)"
  echo
  echo "| Bench | Cold ms | Warm ms | Warm alloc MB | Warm GCs | Warm GC ms |"
  echo "|---|---|---|---|---|---|"
  tail -n +2 "$RAW" | cut -f2 | sort -u | while IFS= read -r name; do
    cold=$(median_row "$name" cold | cut -f5)
    IFS=$'\t' read -r _ _ _ _ wms walloc wgc wgcms <<<"$(median_row "$name" warm)"
    echo "| $name | $cold | $wms | $walloc | $wgc | $wgcms |"
  done
  echo
  echo "## Cold start (median of $STARTS)"
  echo
  echo "| M1 first content ms | M2 launch ms | M3 PSS KB | M3 Java heap KB | M3 native heap KB | M4 logged GCs |"
  echo "|---|---|---|---|---|---|"
  echo "| $(col 2) | $(col 3) | $(col 4) | $(col 5) | $(col 6) | $(col 7) |"
  echo
  echo "## M5 scroll (60 D-pad presses)"
  echo
  sed 's/^/- /' "$OUT/jank.txt"
  echo
  echo "## Z1 APK"
  echo
  echo "- benchmark APK: $((apk_bytes / 1024)) KB (release contents without R8)"
  echo "- armeabi-v7a native libs: $((v7a_bytes / 1024)) KB"
} > "$OUT/summary.md"

echo
cat "$OUT/summary.md"
