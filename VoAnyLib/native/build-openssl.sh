#!/usr/bin/env bash
set -euo pipefail

# Run on Linux/WSL with a Linux Android NDK and unpacked OpenSSL 3.5.4 sources.
: "${ANDROID_NDK_ROOT:?Set ANDROID_NDK_ROOT to a Linux Android NDK}"
: "${OPENSSL_SRC:?Set OPENSSL_SRC to the OpenSSL source directory}"
task_lib_root="$(cd "$(dirname "$0")/.." && pwd)"
export OUT="$task_lib_root/src/main/jni/openssl"
export ABIS=arm64-v8a MIN_SDK=31 NO_DOCKER=1
bash "$task_lib_root/.upstream/strongswan/src/frontends/android/openssl/build.sh"
