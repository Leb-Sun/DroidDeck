#!/usr/bin/bash
# Runs INSIDE an Arch Linux ARM container (menci/archlinuxarm:base-devel) on an arm64 runner.
# Builds Arch Linux ARM's own glibc package of the runtime's version (source.env) with makepkg and
# the pinned PKGBUILD, twice: "rebuild" exactly as Arch builds it, and "patched" with ./patches
# applied after Arch's own prepare(). Each flavor's ELF files are checked against the stock package
# the device runs (taken from the runtime image source.env names): the same file set, the same
# dynamic symbols, SONAME and NEEDED, and abidiff clean. The build's __ASSUME_* values that the
# patches rely on are printed and recorded. Out, in glibc-out/:
#   glibc-rebuild.tzst, glibc-patched.tzst  every ELF shared object and executable the package owns
#   glibc-manifest.txt                      each file's stock, rebuild and patched sha256 (manifest.py)
#   SHA256SUMS, build-record.txt
set -euo pipefail
WORK=/work
cd "$WORK"
. tools/glibc/source.env
set -x
VERSION=$GLIBC_PKGVER-$GLIBC_PKGREL
OUT=$WORK/glibc-out
B=$WORK/glibc-build
rm -rf "$OUT" "$B"
mkdir -p "$OUT" "$B"
record() { printf '%s\n' "$*" | tee -a "$OUT/build-record.txt"; }

# pacman's download sandbox (Landlock + the alpm user) cannot be set up inside the runner's
# container; the packages still come signed from Arch Linux ARM's mirrors.
grep -q '^DisableSandbox' /etc/pacman.conf || sed -i 's/^\[options\]/[options]\nDisableSandbox/' /etc/pacman.conf
# The geo-redirecting mirror answered 500s and connection resets mid-transaction; name a few
# concrete mirrors ahead of it so pacman has somewhere to fall over to.
{ for m in https://ca.us.mirror.archlinuxarm.org https://fl.us.mirror.archlinuxarm.org https://de3.mirror.archlinuxarm.org https://nl.mirror.archlinuxarm.org; do echo "Server = $m/\$arch/\$repo"; done; cat /etc/pacman.d/mirrorlist; } > /etc/pacman.d/mirrorlist.new
mv /etc/pacman.d/mirrorlist.new /etc/pacman.d/mirrorlist
pacman -Syu --noconfirm --needed git sudo zstd binutils python
# abidiff, where Arch Linux ARM has it; build-glibc.yml runs the same check on the runner either way.
pacman -S --noconfirm --needed libabigail || echo "no libabigail in Arch Linux ARM's repositories; abidiff runs on the runner"
# makepkg refuses root; a builder user with passwordless sudo installs the dependencies.
id builder >/dev/null 2>&1 || useradd -m builder
echo 'builder ALL=(ALL) NOPASSWD: ALL' > /etc/sudoers.d/builder
# Every core, and no split-out debug package: the stock package carries no .gnu_debuglink, so the
# rebuild must not either. Neither changes what the compiler is asked to do.
cat > /home/builder/.makepkg.conf <<CONF
MAKEFLAGS="-j$(nproc)"
_opts=(); for _o in "\${OPTIONS[@]}"; do [[ \$_o == debug ]] && _o='!debug'; _opts+=("\$_o"); done; OPTIONS=("\${_opts[@]}")
CONF
chown builder /home/builder/.makepkg.conf

record "# glibc overlay build, $(date -u +%Y-%m-%dT%H:%M:%SZ)"
record "version $VERSION, commit $GLIBC_COMMIT, PKGBUILD $ALARM_PKGBUILDS@$ALARM_PKGBUILDS_COMMIT"
record "toolchain: $(gcc --version | head -1); $(ld --version | head -1); $(pacman -Q glibc linux-api-headers | tr '\n' ' ')"

# 1. The stock package, as devices have it: its files from the runtime image, each checked against
#    the package's own mtree, and the list against source.env's.
curl -fL --retry 3 -o "$B/linuxfs.tar.zst" "$STOCK_ROOTFS_URL"
echo "$STOCK_ROOTFS_SHA256  $B/linuxfs.tar.zst" | sha256sum -c -
DB=var/lib/pacman/local/glibc-$VERSION
mkdir -p "$B/stock"
tar -I zstd -xf "$B/linuxfs.tar.zst" -C "$B/stock" "./$DB/"
sed -n '/^%FILES%$/,/^$/p' "$B/stock/$DB/files" | sed '1d;/^$/d;/\/$/d;s|^|./|' > "$B/stock-files.txt"
tar -I zstd -xf "$B/linuxfs.tar.zst" -C "$B/stock" -T "$B/stock-files.txt"
rm "$B/linuxfs.tar.zst"
python3 tools/glibc/manifest.py stock-list "$B/stock" "$B/stock/$DB" > "$B/stock.sha256"
if ! printf '%s\n' "$GLIBC_STOCK_ELF" | sed '/^$/d' | diff -u - "$B/stock.sha256"; then
  set +x
  echo "ERROR: the stock package's ELF files differ from GLIBC_STOCK_ELF in tools/glibc/source.env."
  echo "If the runtime image or the version changed on purpose, this is the new list:"
  cat "$B/stock.sha256"
  exit 3
fi
record "stock: $(wc -l < "$B/stock.sha256") ELF files from $STOCK_ROOTFS_URL, each the package's own (mtree)"

# 2. Arch Linux ARM's packaging for exactly this pkgrel.
git init -q "$B/pkgbuilds"
git -C "$B/pkgbuilds" fetch -q --depth 1 "$ALARM_PKGBUILDS" "$ALARM_PKGBUILDS_COMMIT"
git -C "$B/pkgbuilds" checkout -q FETCH_HEAD
test "$(git -C "$B/pkgbuilds" rev-parse HEAD)" = "$ALARM_PKGBUILDS_COMMIT"
P=$B/pkgbuilds/core/glibc
grep -qx "pkgver=$GLIBC_PKGVER" "$P/PKGBUILD"
grep -qx "pkgrel=$GLIBC_PKGREL" "$P/PKGBUILD"
grep -qx "_commit=$GLIBC_COMMIT" "$P/PKGBUILD"
URL=$(sed -n 's/^  "git+\([^#]*\)#commit=.*/\1/p' "$P/PKGBUILD")
test -n "$URL"

# 3. The glibc repository makepkg clones from (SRCDEST), seeded here so a slow or unreachable
#    sourceware falls back to a mirror. makepkg only fetches into it, and a failed fetch is a warning;
#    the commit id is what pins the tree.
mkdir -p "$B/sources" "$B/packages"
chown -R builder "$B"
for url in "$GLIBC_GIT" $GLIBC_GIT_MIRRORS; do
  if sudo -u builder timeout 1800 git clone -q --mirror "$url" "$B/sources/glibc"; then break; fi
  rm -rf "$B/sources/glibc"
done
sudo -u builder git -C "$B/sources/glibc" cat-file -e "$GLIBC_COMMIT^{commit}"
sudo -u builder git -C "$B/sources/glibc" config remote.origin.url "$URL"
record "glibc source: $GLIBC_COMMIT from $url"

# The __ASSUME_* values the patches rely on, read from the build's own configuration: the
# kernel-features.h of its sysdeps directories under its config.h (04-spikes.md, device check 2).
assume() {
  local src=$1 build=$2 inc=() d
  for d in $(sed -n 's/^config-sysdirs = //p' "$build/config.make"); do
    [ -d "$src/$d/include" ] && inc+=("-I$src/$d/include")
    inc+=("-I$src/$d")
  done
  printf '%s\n' '#include <kernel-features.h>' \
    '__ASSUME_FACCESSAT2=__ASSUME_FACCESSAT2' '__ASSUME_FCHMODAT2=__ASSUME_FCHMODAT2' \
    '__ASSUME_SET_ROBUST_LIST=__ASSUME_SET_ROBUST_LIST' 'kernel=__LINUX_KERNEL_VERSION_STR' \
    | sed 's/^\(__ASSUME_[A-Z_0-9]*\)=/\1_NAME=/' \
    | gcc -E -P -include "$build/config.h" "${inc[@]}" -x c - \
    | sed -n 's/^__ASSUME_\([A-Z_0-9]*\)_NAME=/__ASSUME_\1=/p; s/^kernel="\(.*\)"$/kernel=\1/p' | tr '\n' ' ' | sed 's/ $//'
}

PATCHES=$(cd tools/glibc/patches && ls *.patch | sort)
for flavor in rebuild patched; do
  # Both flavors build at the same path, so a file the patches do not touch comes out the same.
  F=$B/makepkg
  rm -rf "$F"
  cp -r "$P" "$F"
  OURS=""
  if [ "$flavor" = patched ]; then
    OURS=$PATCHES
    cp tools/glibc/patches/*.patch "$F/"
    { echo; echo 'source+=('; for p in $OURS; do echo "  $p"; done; echo ')'
      for p in $OURS; do echo "b2sums+=('SKIP')"; done; } >> "$F/PKGBUILD"
  fi
  # After Arch's own prepare(): the checkout must be the pinned commit, then ours go on top
  # (none for the rebuild).
  cat >> "$F/PKGBUILD" <<PREP

_droiddeck_prepare() {
  cd "\$srcdir/glibc"
  test "\$(git rev-parse HEAD)" = "$GLIBC_COMMIT"
  for p in $(echo $OURS); do
    echo "applying \$p"
    patch -Np1 --no-backup-if-mismatch < "\$srcdir/\$p"
  done
}
eval "\$(declare -f prepare | sed 's/^prepare ()/_arch_prepare ()/')"
prepare() { _arch_prepare; _droiddeck_prepare; }
PREP
  chown -R builder "$F"
  # -A: the PKGBUILD lists x86_64 only; Arch Linux ARM builds the same file. --nocheck: the test
  # suite changes nothing that is packaged.
  (cd "$F" && sudo -u builder env HOME=/home/builder SRCDEST="$B/sources" PKGDEST="$B/packages/$flavor" \
      makepkg -A -s --noconfirm --nocheck --skipchecksums --skippgpcheck)
  # The name makepkg gives it says pkgver() agreed: git describe of the checkout is source.env's.
  PKG=$(ls "$B/packages/$flavor"/glibc-"$VERSION"-*.pkg.tar.*)
  mkdir -p "$B/$flavor-pkg"
  tar -xf "$PKG" -C "$B/$flavor-pkg"
  python3 tools/glibc/manifest.py collect "$B/stock.sha256" "$B/$flavor-pkg" "$B/$flavor-files"
  A=$(assume "$F/src/glibc" "$F/src/glibc-build")
  record "$flavor: $A"
  echo "$A" > "$B/$flavor-assume"
  if [ "$flavor" = patched ]; then
    # 0002 edits the unconditional set_robust_list sites and 0004 relies on the fallback being
    # compiled in: with any other value the patched build would not do what it says.
    for want in __ASSUME_FACCESSAT2=0 __ASSUME_FCHMODAT2=0 __ASSUME_SET_ROBUST_LIST=1; do
      case " $A " in *" $want "*) ;; *) echo "ERROR: the patched build needs $want, it has: $A"; exit 4 ;; esac
    done
  fi
  # The ABI against the stock package: the same exported symbols, SONAME and NEEDED for every
  # file, and abidiff clean for every shared object. Arch Linux ARM ships no debug info, so both
  # compare ELF symbol tables and versions, not types.
  python3 tools/glibc/manifest.py symbols "$B/stock.sha256" "$B/stock" "$B/$flavor-files"
  record "$flavor: dynamic symbols, SONAME and NEEDED identical to stock for $(wc -l < "$B/stock.sha256") files"
  if command -v abidiff >/dev/null; then
    bash tools/glibc/abidiff.sh "$B" "$flavor" | tee -a "$OUT/build-record.txt"
  fi
  (cd "$B/$flavor-files" && tar --sort=name --owner=0 --group=0 --numeric-owner --mtime=@0 -cf - usr) \
    | zstd -19 -T0 -q -o "$OUT/glibc-$flavor.tzst"
  # The build tree is several GB; only the package is needed from here on.
  rm -rf "$F"
done

python3 tools/glibc/manifest.py write --version "$VERSION" --commit "$GLIBC_COMMIT" \
  --assume "$(cat "$B/patched-assume")" --patches "$(echo $PATCHES)" \
  "$B/stock.sha256" "$B/stock" "$B/rebuild-files" "$B/patched-files" > "$OUT/glibc-manifest.txt"
# The same check the apk build runs on what it downloads.
mkdir -p "$B/check"
cp "$OUT/glibc-manifest.txt" "$B/check/manifest"
for flavor in rebuild patched; do
  mkdir -p "$B/check/$flavor"
  tar -I zstd -xf "$OUT/glibc-$flavor.tzst" -C "$B/check/$flavor"
done
python3 tools/glibc/manifest.py check "$B/check" | tee -a "$OUT/build-record.txt"
same=$(awk '$1 == "file" && $4 == $5 { n++ } END { print n + 0 }' "$OUT/glibc-manifest.txt")
diff=$(awk '$1 == "file" && $5 != $6 { n++ } END { print n + 0 }' "$OUT/glibc-manifest.txt")
record "files identical to stock in the rebuild: $same; files the patches change: $diff"
(cd "$OUT" && sha256sum glibc-manifest.txt glibc-rebuild.tzst glibc-patched.tzst | tee SHA256SUMS)
cat "$OUT/SHA256SUMS" >> "$OUT/build-record.txt"
ls -l "$OUT"
