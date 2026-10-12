#!/bin/bash
echo "tree.sh argv0=$0 pid=$$"
echo "  uname via child:"; /usr/bin/env bash -c 'echo "    nested bash $BASH_VERSION"'
echo "  python child:"; /usr/bin/python3 -c 'import subprocess,sys; print("    py", sys.version.split()[0]); print("   ", subprocess.run(["/bin/echo","subproc-ok"],capture_output=True,text=True).stdout.strip())'
echo "  exe check:"; /opt/nativetest/selfexe
