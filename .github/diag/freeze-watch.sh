#!/usr/bin/env bash
# Once adb loses the emulator for 3 s in a row, snapshot every thread of the emulator process (diagnostic branch only).
out=${1:-freeze.txt}
misses=0
while true; do
  if [ "$(timeout 3 adb -s emulator-5554 get-state 2>/dev/null)" = device ]; then misses=0; else misses=$((misses + 1)); fi
  if [ $misses -ge 3 ]; then
    pid=$(pgrep -f 'qemu-system' | head -1)
    {
      echo "== $(date -u +%T.%N) adb lost the emulator; qemu pid=${pid:-none}"
      [ -n "$pid" ] || exit 0
      echo "== threads: tid comm state wchan utime stime"
      for t in /proc/$pid/task/*; do
        tid=${t##*/}
        read -r -a st < $t/stat
        echo "$tid $(cat $t/comm) state=$(awk '{print $3}' $t/stat) wchan=$(cat $t/wchan 2>/dev/null) utime=${st[13]} stime=${st[14]}"
      done
      echo "== kernel stacks of threads not sleeping in a plain poll"
      for t in /proc/$pid/task/*; do
        w=$(cat $t/wchan 2>/dev/null)
        case "$w" in do_sys_poll|do_epoll_wait|ep_poll) ;; *) echo "-- ${t##*/} $(cat $t/comm) $w"; sudo cat $t/stack 2>/dev/null ;; esac
      done
      echo "== gdb backtraces"
      sudo timeout 60 gdb -p "$pid" -batch -nx -ex 'set pagination off' -ex 'thread apply all bt 25' 2>&1
      echo "== second snapshot 10 s later (utime/stime)"
      sleep 10
      for t in /proc/$pid/task/*; do read -r -a st < $t/stat; echo "${t##*/} $(cat $t/comm) state=${st[2]} utime=${st[13]} stime=${st[14]}"; done
    } > "$out" 2>&1
    exit 0
  fi
  sleep 1
done
