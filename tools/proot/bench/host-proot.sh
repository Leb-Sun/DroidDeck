#!/usr/bin/env bash
# Builds this repo's proot (source.env plus patches/, applied in build.sh's order) natively on a
# Linux host, for the host tests (tools/tests/test_proot_stats.py). Needs a C compiler, ld.lld for
# the loader's link flags, and talloc's headers and library (Debian/Ubuntu: libtalloc-dev).
#
#   tools/proot/bench/host-proot.sh OUTDIR                       # fetches termux/proot
#   PROOT_SRC=<pristine checkout at PROOT_COMMIT> tools/proot/bench/host-proot.sh OUTDIR
#   DROIDDECK_HOST_PROOT=OUTDIR python3 -m unittest discover -s tools/tests -p 'test_proot_stats.py'
set -euo pipefail
OUTDIR=$(mkdir -p "$1" && cd "$1" && pwd)
HERE=$(cd "$(dirname "$0")/.." && pwd)
. "$HERE/source.env"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
if [[ -n "${PROOT_SRC:-}" ]]; then
  cp -R "$PROOT_SRC" "$WORK/proot"
else
  curl -fsSL --retry 3 -o "$WORK/proot.tar.gz" "https://github.com/termux/proot/archive/${PROOT_COMMIT}.tar.gz"
  echo "$PROOT_SHA256  $WORK/proot.tar.gz" | sha256sum -c -
  mkdir -p "$WORK/proot"
  tar -xzf "$WORK/proot.tar.gz" -C "$WORK/proot" --strip-components=1
fi
for patch in "$HERE"/patches/*.patch; do
  patch -d "$WORK/proot" -p1 --forward --quiet < "$patch"
done
# Termux's ashmem extension includes the Android uapi header; these ioctls are all it uses.
mkdir -p "$WORK/include/linux"
cat > "$WORK/include/linux/ashmem.h" <<'EOF'
#include <linux/ioctl.h>
#include <linux/types.h>
#define ASHMEM_NAME_LEN 256
#define __ASHMEMIOC 0x77
struct ashmem_pin { __u32 offset; __u32 len; };
#define ASHMEM_SET_NAME _IOW(__ASHMEMIOC, 1, char[ASHMEM_NAME_LEN])
#define ASHMEM_GET_NAME _IOR(__ASHMEMIOC, 2, char[ASHMEM_NAME_LEN])
#define ASHMEM_SET_SIZE _IOW(__ASHMEMIOC, 3, size_t)
#define ASHMEM_GET_SIZE _IO(__ASHMEMIOC, 4)
#define ASHMEM_SET_PROT_MASK _IOW(__ASHMEMIOC, 5, unsigned long)
#define ASHMEM_GET_PROT_MASK _IO(__ASHMEMIOC, 6)
#define ASHMEM_PIN _IOW(__ASHMEMIOC, 7, struct ashmem_pin)
#define ASHMEM_UNPIN _IOW(__ASHMEMIOC, 8, struct ashmem_pin)
#define ASHMEM_GET_PIN_STATUS _IO(__ASHMEMIOC, 9)
#define ASHMEM_PURGE_ALL_CACHES _IO(__ASHMEMIOC, 10)
EOF
# The loader is linked with lld's --rosegment; no 32-bit loader is needed here.
LOADER_LDFLAGS="-fuse-ld=lld" make -C "$WORK/proot/src" -j"$(getconf _NPROCESSORS_ONLN)" \
    HAS_LOADER_32BIT= PROOT_UNBUNDLE_LOADER=/nonexistent \
    CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -I$WORK/include -DARG_MAX=131072 -Wno-error=implicit-function-declaration" \
    proot loader
install -m755 "$WORK/proot/src/proot" "$OUTDIR/proot"
install -m755 "$WORK/proot/src/loader/loader" "$OUTDIR/loader"
ls -l "$OUTDIR/proot" "$OUTDIR/loader"
