#!/usr/bin/env bash
# Caps the running emulator's CPU so its timings mean something for a Fire TV stick.
#
#   tools/perf/throttle.sh <percent>   e.g. 40  = the emulator process gets 0.4 of one host core in total
#   tools/perf/throttle.sh off           full speed again
#   tools/perf/throttle.sh               show the current cap
#
# The FireTV_API25 AVD runs on the host CPU (a desktop core is many times a stick's A53), so
# absolute emulator numbers say nothing about a stick and the M5 frame stats read 0% jank no
# matter what. This moves the qemu process into a user cgroup (cgroup v2, systemd user session)
# and sets its CPU quota, which can be changed live - the emulator keeps running. The quota
# covers every emulator thread: the two guest cores, rendering and I/O - together, which is
# roughly a stick's situation too.
#
# Calibrate rather than guess: a stick does a 50k-catalogue cold start in about 3.4 s (M1);
# pick the percent that puts the emulator's M1 near that, then measure M5 at the same percent.
# CPU only - RAM bandwidth and flash speed stay the host's, so storage-bound numbers are still
# not comparable.
set -euo pipefail

UNIT=lumora-throttle
CG=/sys/fs/cgroup/user.slice/user-$(id -u).slice/user@$(id -u).service/app.slice/$UNIT.service

pid=$(pgrep -f "qemu-system.*-avd " | head -1 || true)
[ -n "$pid" ] || { echo "no emulator running"; exit 1; }

if [ $# -eq 0 ]; then
  if [ -f "$CG/cpu.max" ]; then read -r quota period < "$CG/cpu.max"; { [ "$quota" = max ] || [ "$((quota * 100 / period))" -ge "$(( $(nproc) * 100 ))" ]; } && echo "off" || echo "$((quota * 100 / period))%"; else echo "off"; fi
  exit 0
fi

if ! systemctl --user is-active -q "$UNIT.service"; then
  # A do-nothing service is the simplest thing that owns a cgroup a user can move their own
  # processes into. A transient scope would need the emulator started inside it instead.
  systemd-run --user --unit="$UNIT" -q sleep infinity
  sleep 0.5
fi
if ! grep -q "$UNIT" "/proc/$pid/cgroup"; then
  echo "$pid" > "$CG/cgroup.procs"
fi

case "$1" in
  # An empty CPUQuota= doesn't reset a running unit, and the cgroup file isn't user-writable:
  # every core is as good as no cap.
  off) systemctl --user set-property "$UNIT.service" "CPUQuota=$(( $(nproc) * 100 ))%"; echo "emulator CPU: unlimited" ;;
  # A 10 ms accounting period: with the default 100 ms the emulator burns its share early in
  # each period and then sits frozen for the rest, which shows up as uniform ~100 ms frame
  # stalls that have nothing to do with the app.
  *)   systemctl --user set-property "$UNIT.service" "CPUQuota=$1%" CPUQuotaPeriodSec=10ms; echo "emulator CPU: $1% of one host core" ;;
esac
