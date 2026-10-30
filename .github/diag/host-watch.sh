#!/usr/bin/env bash
# Logs host memory, the emulator process and load every second (diagnostic branch only).
while true; do
  mem=$(free -m | awk '/Mem:/ {print "used=" $3 " free=" $4 " avail=" $7}')
  swap=$(free -m | awk '/Swap:/ {print "swap=" $3}')
  qemu=$(ps -eo pid=,rss=,pcpu=,comm= | awk '/qemu/ {printf "qemu pid=%s rss=%sk cpu=%s;", $1, $2, $3}')
  echo "$(date -u +%T.%N | cut -c1-12) $mem $swap load=$(cut -d' ' -f1 /proc/loadavg) ${qemu:-qemu=none}"
  sleep 1
done
