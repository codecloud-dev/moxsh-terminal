#!/bin/bash
set +e
cd /workspace/termux-src
P="https://ghproxy.net/https://github.com/termux"
repos=(termux-app termux-packages termux-api termux-boot termux-float termux-styling termux-tasker termux-widget termux-x11 termux-tools termux-am libtermux terminal-view terminal-emulator)
for r in "${repos[@]}"; do
  if [ ! -d "$r" ]; then
    git clone --depth 1 --single-branch "$P/$r.git" >/dev/null 2>&1 && echo "OK   $r" || echo "FAIL $r"
  else
    echo "EXIST $r"
  fi
done
echo "===CLONE_DONE==="
du -sh /workspace/termux-src/* 2>/dev/null
