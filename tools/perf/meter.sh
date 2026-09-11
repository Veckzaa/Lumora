#!/usr/bin/env bash
# Live CPU / memory meter for a Lumora process on the connected device or emulator, run from the PC.
#
#   tools/perf/meter.sh [package] [interval_s] [csv_file]
#   tools/perf/meter.sh                                   # com.lumora.debug, every 1 s
#   tools/perf/meter.sh com.lumora.bench 1 run.csv        # also log every sample to a CSV
#
# One adb shell session reads /proc on the device each interval, so the meter itself costs almost
# nothing. The exception is `dumpsys meminfo` (PSS / Java heap / native heap), which is heavy and
# only runs every 5th sample. Don't leave it running during tools/perf/baseline.sh - even this
# would skew a 2-core emulator's numbers.
#
# Columns:
#   app CPU  - this process, % of all cores (100 = every core busy with it)
#   sys CPU  - whole device, % of all cores
#   RSS      - resident memory, every sample
#   PSS/Java/Native - from dumpsys meminfo, refreshed every 5th sample
#   dev free - device memory available (MemAvailable, or free+cached on old kernels)
# The app restarting (new pid) is picked up on the next sample.
set -euo pipefail

PKG=${1:-com.lumora.debug}
INTERVAL=${2:-1}
CSV=${3:-}
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"

[ -n "$CSV" ] && echo "elapsed_s,pid,app_cpu_pct,sys_cpu_pct,rss_mb,pss_mb,java_heap_mb,native_heap_mb,dev_avail_mb,dev_total_mb" > "$CSV"
echo "Metering $PKG every ${INTERVAL}s - Ctrl+C to stop"

# Everything between S and E is one sample; the awk below turns consecutive samples into deltas.
"$ADB" shell "i=0; while true; do
  p=\$(pidof $PKG)
  echo \"S \$(date +%s) \${p:-0}\"
  head -1 /proc/stat
  if [ -n \"\$p\" ]; then
    echo \"P \$(cut -d' ' -f14,15 /proc/\$p/stat 2>/dev/null)\"
    grep VmRSS /proc/\$p/status 2>/dev/null
    [ \$((i % 5)) -eq 0 ] && dumpsys meminfo $PKG | grep -E 'Java Heap:|Native Heap:|^ *TOTAL:'
  fi
  grep -E '^(MemTotal|MemFree|MemAvailable|Buffers|Cached):' /proc/meminfo
  echo E
  i=\$((i + 1)); sleep $INTERVAL
done" | awk -v csv="$CSV" '
function bar(pct,   n, s) { n = int(pct / 10 + 0.5); if (n > 10) n = 10; s = ""; for (k = 0; k < 10; k++) s = s (k < n ? "#" : "."); return s }
function mb(kb) { return kb / 1024 }
# CRs stripped here rather than with tr, which block-buffers into a pipe and held samples back for seconds.
{ sub(/\r$/, "") }
$1 == "S"          { t = $2; pid = $3; proc = ""; rss = 0; avail = -1; free = buffers = cached = 0; if (t0 == "") t0 = t }
$1 == "cpu"        { total = 0; for (f = 2; f <= NF; f++) total += $f; idle = $5 + $6 }
$1 == "P"          { proc = $2 + $3 }
$1 == "VmRSS:"     { rss = $2 }
/Java Heap:/       { java = $3 }
/Native Heap:/     { native = $3 }
/^ *TOTAL:/        { pss = $2 }
$1 == "MemTotal:"  { memtotal = $2 }
$1 == "MemFree:"   { free = $2 }
$1 == "Buffers:"   { buffers = $2 }
$1 == "Cached:"    { cached = $2 }
$1 == "MemAvailable:" { avail = $2 }
$1 == "E" {
  if (avail < 0) avail = free + buffers + cached
  # A new pid (app restarted) invalidates the meminfo figures - but not on the very first sample.
  if (lastpid != "" && pid != lastpid) { java = native = pss = ""; lastproc = "" }
  dt = total - lasttotal
  if (lasttotal != "" && dt > 0) {
    sys = 100 * (dt - (idle - lastidle)) / dt
    app = (proc != "" && lastproc != "") ? 100 * (proc - lastproc) / dt : 0
    if (n++ % 20 == 0) printf "\n%6s %6s  %-17s %-17s %8s %8s %8s %8s %13s\n", "t+s", "pid", "app CPU", "sys CPU", "RSS MB", "PSS MB", "Java MB", "Nat MB", "dev free MB"
    printf "%6d %6s  %s %5.1f%% %s %5.1f%% %8.1f %8s %8s %8s %6.0f/%-6.0f\n", t - t0, (pid ? pid : "-"), bar(app), app, bar(sys), sys,
      mb(rss), (pss == "" ? "-" : sprintf("%.1f", mb(pss))), (java == "" ? "-" : sprintf("%.1f", mb(java))), (native == "" ? "-" : sprintf("%.1f", mb(native))),
      mb(avail), mb(memtotal)
    if (csv != "") printf "%d,%s,%.1f,%.1f,%.1f,%s,%s,%s,%.0f,%.0f\n", t - t0, pid, app, sys, mb(rss), (pss == "" ? "" : sprintf("%.1f", mb(pss))), (java == "" ? "" : sprintf("%.1f", mb(java))), (native == "" ? "" : sprintf("%.1f", mb(native))), mb(avail), mb(memtotal) >> csv
    fflush()
  }
  lasttotal = total; lastidle = idle; lastproc = proc; lastpid = pid
}'
