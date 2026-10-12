#!/bin/bash
x=$(echo a b c | tr ' ' '\n' | sort -R | head -1)
echo "subshell+pipe picked: $x"
s=0; for i in 1 2 3 4 5; do s=$((s+i)); done; echo "loop sum=$s"
( echo "in subshell pid=$BASHPID" )
echo "bash $BASH_VERSION ok"
