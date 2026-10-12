#!/usr/bin/env bash
# S4 cost matrix: native glibc at / vs patched glibc untraced under the filter
# vs proot, on this host.  HOST NUMBERS ARE RELATIVE ONLY (x86_64 desktop, not
# a phone; no app seccomp policy on the host except the one seccomp-launch adds).
# Prototype.  Needs $WORK/{seccomp-launch,hostproot/proot,rootfs-patched,micro-host}.
set -uo pipefail
WORK=${WORK:-/opt/nspike}
ROOT=$WORK/rootfs-patched
L=$WORK/seccomp-launch
P=$WORK/hostproot/proot
export PROOT_LOADER=$WORK/hostproot/loader
M=/opt/nativetest/micro
LIBC=/usr/lib/x86_64-linux-gnu/libc.so.6

num(){ sed 's/.*: //'; }
timeit(){ local best=999999 i s e ms; for i in 1 2 3; do s=$(date +%s%N); "$@" >/dev/null 2>&1; e=$(date +%s%N); ms=$(((e-s)/1000000)); [ $ms -lt $best ] && best=$ms; done; echo "${best} ms"; }

echo "## per-call (ns/op)        native        patched-untraced   proot"
printf 'stat   %18s %18s %12s\n' \
  "$("$WORK/micro-host" stat $LIBC 200000 | num)" \
  "$($L --quiet --chroot $ROOT --profile android16q -- $M stat $LIBC 200000 2>/dev/null | num)" \
  "$($P -r $ROOT -b /proc -b /dev -w / $M stat $LIBC 30000 2>/dev/null | num)"
printf 'open   %18s %18s %12s\n' \
  "$("$WORK/micro-host" open $LIBC 200000 | num)" \
  "$($L --quiet --chroot $ROOT --profile android16q -- $M open $LIBC 200000 2>/dev/null | num)" \
  "$($P -r $ROOT -b /proc -b /dev -w / $M open $LIBC 30000 2>/dev/null | num)"

echo "## forkexec /bin/true x200 (us/op)"
printf 'forkexec %16s %18s %12s\n' \
  "$("$WORK/micro-host" forkexec /bin/true 200 | num)" \
  "$($L --quiet --chroot $ROOT --profile android16q -- $M forkexec /bin/true 200 2>/dev/null | num)" \
  "$($P -r $ROOT -b /proc -b /dev -w / $M forkexec /bin/true 200 2>/dev/null | num)"

echo "## workloads (wall, best of 3)"
printf 'py import %15s %18s %12s\n' \
  "$(timeit /usr/bin/python3 -c 'import json,ssl,os,subprocess')" \
  "$(timeit $L --quiet --chroot $ROOT --profile android16q -- /usr/bin/python3 -I -c 'import json,ssl,os,subprocess')" \
  "$(timeit $P -r $ROOT -b /proc -b /dev -w / /usr/bin/python3 -I -c 'import json,ssl,os,subprocess')"
if [ -d "$ROOT/opt/nativetest/tree3000" ]; then
printf 'tar 3000 %16s %18s %12s\n' \
  "$(timeit tar -cf /dev/null -C $ROOT/opt/nativetest tree3000)" \
  "$(timeit $L --quiet --chroot $ROOT --profile android16q -- /usr/bin/tar -cf /dev/null -C /opt/nativetest tree3000)" \
  "$(timeit $P -r $ROOT -b /proc -b /dev -w / /usr/bin/tar -cf /dev/null -C /opt/nativetest tree3000)"
fi
