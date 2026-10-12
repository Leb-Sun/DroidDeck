#!/usr/bin/env bash
# abidiff of every shared object of each flavor against the stock package's, from what
# build-in-arch.sh leaves in its build directory: stock/, <flavor>-files/ and stock.sha256. Fails on
# the first change abidiff reports. Arch Linux ARM ships no debug info, so abidiff compares ELF
# symbol tables and versions. build-in-arch.sh runs it in the container when Arch Linux ARM has
# libabigail; build-glibc.yml runs it again on the runner with Ubuntu's.
#   tools/glibc/abidiff.sh glibc-build [flavor...]   (default: rebuild patched)
set -euo pipefail
B=${1:?the build directory}
shift
flavors=("$@")
[ ${#flavors[@]} -gt 0 ] || flavors=(rebuild patched)
abidiff --version
for flavor in "${flavors[@]}"; do
  n=0
  for rel in $(awk '{print $2}' "$B/stock.sha256" | grep '\.so'); do
    if ! abidiff --no-show-locs "$B/stock/$rel" "$B/$flavor-files/$rel" > "$B/abidiff.out" 2>&1; then
      echo "ERROR: abidiff reports a change in $rel ($flavor):"
      cat "$B/abidiff.out"
      exit 5
    fi
    n=$((n + 1))
  done
  echo "$flavor: abidiff clean against stock for $n shared objects"
done
