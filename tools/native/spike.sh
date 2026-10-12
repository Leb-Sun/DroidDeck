#!/usr/bin/env bash
# DroidDeck native-environment Phase 2 feasibility spike (04-spikes.md).
#
# PROTOTYPE.  Not wired into the app or CI.  It builds a patched glibc 2.43 and
# a small relocatable test rootfs on an x86_64 Linux host, then shows that a
# stock-ABI glibc userland runs with NO tracer under a simulation of Android's
# app seccomp policy, with exec and path translation working.  x86_64 is a
# valid testbed because the patched glibc logic is architecture-generic
# (sysdeps/unix/sysv/linux, sysdeps/nptl); aarch64 differences are listed in
# 04-spikes.md and emitted by `seccomp-launch --dump aarch64`.
#
# Everything lives OUTSIDE the repo, under $WORK (default /opt/nspike).  This
# script documents the steps; it is not expected to run unattended in CI.
#
# Prerequisites (host): gcc, make, bison, gawk, python3, patch, git, curl,
# libtalloc-dev + lld (for the proot baseline), and kernel UAPI headers.
# Network: an upstream glibc 2.43 tarball and a Ubuntu userland; the exact
# hosts reachable vary, so the fetch step prints what it could not get.
set -uo pipefail
WORK=${WORK:-/opt/nspike}
HERE=$(cd "$(dirname "$0")" && pwd)
ROOTFS=$WORK/rootfs-patched
say(){ printf '\n== %s ==\n' "$*"; }

cmd=${1:-help}
case "$cmd" in
help)
  cat <<EOF
usage: WORK=/opt/nspike tools/native/spike.sh <step>
steps:
  tools        build seccomp-launch, trap-trace, the pathxlat core test, execshim, pathxlat, micro
  dump         print the seccomp blocklist as x86_64 and aarch64 numbers
  baseline     show stock glibc dying with SIGSYS under the filter (S0)
  s1           run the untraced userland battery across the profiles (S1)
  s7           glibc patch 0007: close_range, closefrom, posix_spawn and Python with close_range trapped (android12)
  s2           run the exec-shim tree: ld.so re-exec, shebangs, /proc/self/exe (S2)
  s3           run ls/bash/python/dlopen with the rootfs relocated, in-process xlat (S3)
  s4           cost table: native vs patched-untraced vs proot (S4)
  all          tools dump baseline s1 s7 s2 s3 s4
Build of glibc + rootfs is described in the comments (long, network-dependent);
this script assumes \$WORK already holds rootfs-patched, rootfs-base and the
hostproot/ build as produced during the spike.
EOF
  ;;
tools)
  say "building spike tools"
  gcc -O2 -o "$WORK/seccomp-launch" "$HERE/launcher/seccomp-launch.c"
  gcc -O2 -o "$WORK/trap-trace"     "$HERE/launcher/trap-trace.c"
  gcc -O2 -o "$WORK/test_pathxlat_core" "$HERE/tests/test_pathxlat_core.c"
  gcc -O2 -shared -fPIC -o "$ROOTFS/opt/nativetest/libexecshim.so" "$HERE/execshim/execshim.c" -ldl
  gcc -O2 -shared -fPIC -o "$ROOTFS/opt/nativetest/libpathxlat.so" "$HERE/pathxlat/libpathxlat.c" -ldl
  gcc -O2 -o "$ROOTFS/opt/nativetest/micro" "$HERE/bench/micro.c"
  "$WORK/test_pathxlat_core"
  ;;
dump)
  "$WORK/seccomp-launch" --dump x86_64; echo; "$WORK/seccomp-launch" --dump aarch64 ;;
baseline)
  say "stock glibc /bin/true under the filter (expect SIGSYS, rc 159)"
  "$WORK/seccomp-launch" --chroot "$WORK/rootfs-base" --profile android16q -- /bin/true
  echo "rc=$?"
  "$WORK/trap-trace" --continue --chroot "$WORK/rootfs-base" --profile android16q -- /bin/true 2>&1 | grep SIGSYS ;;
s1)
  # android12 is android14 plus close_range trapped; unset means every gated call is blocked.
  for prof in android14 android15 android16q android12; do
    case $prof in android16q) M=0;; android15) M=6;; *) M="";; esac
    say "S1 $prof (BL_NATIVE_BLOCK=${M:-unset})"
    "$WORK/seccomp-launch" --quiet --chroot "$ROOTFS" --chdir /opt/nativetest --profile $prof \
      -- /usr/bin/env ${M:+BL_NATIVE_BLOCK=$M} /opt/nativetest/s1_battery
    echo "  battery rc=$?"
  done ;;
s7)
  # tools/glibc patch 0007. With close_range trapped, the gate (bit 0x8, set in the unset default)
  # keeps every glibc path off it; with the bit clear the test dies of SIGSYS (rc 159), which is
  # the proof that the gate, not luck, kept it alive.
  gcc -O2 -o "$ROOTFS/opt/nativetest/close_range_test" "$HERE/tests/close_range_test.c"
  for M in "" f; do
    say "S7 android12 (BL_NATIVE_BLOCK=${M:-unset})"
    "$WORK/seccomp-launch" --quiet --chroot "$ROOTFS" --chdir /opt/nativetest --profile android12 \
      -- /usr/bin/env ${M:+BL_NATIVE_BLOCK=$M} /opt/nativetest/close_range_test
    echo "  rc=$?"
  done
  say "S7 android12, BL_NATIVE_BLOCK=7: close_range not marked (expect rc 159)"
  "$WORK/seccomp-launch" --quiet --chroot "$ROOTFS" --chdir /opt/nativetest --profile android12 \
    -- /usr/bin/env BL_NATIVE_BLOCK=7 /opt/nativetest/close_range_test
  echo "  rc=$?"
  say "S7 android12, python3 subprocess (close_fds through close_range)"
  "$WORK/seccomp-launch" --quiet --chroot "$ROOTFS" --chdir /opt/nativetest --profile android12 \
    -- /usr/bin/python3 -I /opt/nativetest/py_test.py
  echo "  rc=$?" ;;
s2)
  say "S2 exec shim tree, untraced"
  "$WORK/seccomp-launch" --quiet --chroot "$ROOTFS" --chdir /opt/nativetest --profile android16q \
    -- /usr/bin/env BL_NATIVE_BLOCK=0 LD_PRELOAD=/opt/nativetest/libexecshim.so \
       BL_LOADER=/lib64/ld-linux-x86-64.so.2 /bin/bash /opt/nativetest/tree.sh ;;
s3)
  LD=$ROOTFS/lib64/ld-linux-x86-64.so.2
  LP="$ROOTFS/usr/lib/x86_64-linux-gnu:$ROOTFS/lib64:$ROOTFS/usr/lib"
  XL=$ROOTFS/opt/nativetest/libpathxlat.so
  export BL_NATIVE_BLOCK=0 BL_XLAT_ROOT=$ROOTFS BL_XLAT_BINDS='/proc:/proc|/dev:/dev|/sys:/sys'
  say "S3 relocated rootfs, in-process translation (no chroot, no proot)"
  "$WORK/seccomp-launch" --quiet --profile android16q -- "$LD" --library-path "$LP" \
    --inhibit-cache --preload "$XL" --argv0 /usr/bin/ls "$ROOTFS/usr/bin/ls" /usr/lib ;;
s4)
  bash "$HERE/bench/cost-matrix.sh" ;;
all)
  for s in tools dump baseline s1 s7 s2 s3 s4; do "$0" "$s"; done ;;
*) echo "unknown step $cmd"; exit 2 ;;
esac
