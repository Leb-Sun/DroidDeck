#!/bin/bash
set -e
ls -la /usr/lib >/dev/null && echo "ls ok"
printf '%s\n' one two three | wc -l
echo "abc" | md5sum | cut -c1-8
date -u +%Y >/dev/null && echo "date ok"
echo "payload-$$" > /tmp/hn.$$ && cp /tmp/hn.$$ /tmp/hn2.$$ && rm -f /tmp/hn.$$ /tmp/hn2.$$ && echo "cp+unlink ok"
head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n' && echo " urandom ok"
echo "coreutils all ok"
