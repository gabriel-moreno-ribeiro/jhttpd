#!/usr/bin/env bash
# Compiles the server and the tests into ./out (no build tool needed).
set -e
cd "$(dirname "$0")"
rm -rf out && mkdir -p out
javac -Xlint:all -Werror -d out $(find src test -name '*.java')
echo "compiled to out/"
