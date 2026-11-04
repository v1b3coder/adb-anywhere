#!/bin/sh
# Applies the focused patches to a build copy and builds adbd.
#
#   ./build.sh                 # arm64-v8a, armeabi-v7a, x86_64
#   ABIS="arm64-v8a" ./build.sh
#   ADBD_HOST=1 ./build.sh      # host build, for quick compile checks
#
# The vendor/ tree is never modified: the patches are applied to build/src.
set -eu

here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
build=${BUILD_DIR:-$here/build}
src=$build/src

rm -rf "$src"
mkdir -p "$build"
cp -a "$here/vendor/adb" "$src"
for p in "$here"/patches/*.patch; do
    patch -p1 -s -d "$src" < "$p"
done

if [ "${ADBD_HOST:-0}" = 1 ]; then
    cmake -S "$here" -B "$build/host" -DADBD_SRC="$src" -DCMAKE_BUILD_TYPE=Release
    cmake --build "$build/host" --target adbd
    cp "$build/host/adbd" "$build/adbd-host"
    echo "built $build/adbd-host"
    exit 0
fi

ndk=${ANDROID_NDK_HOME:-}
if [ -z "$ndk" ]; then
    sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
    ndk=$(ls -d "$sdk"/ndk/* 2>/dev/null | sort -V | tail -1 || true)
fi
if [ -z "$ndk" ] || [ ! -d "$ndk" ]; then
    echo "Android NDK not found; set ANDROID_NDK_HOME" >&2
    exit 1
fi

toolchain=$ndk/build/cmake/android.toolchain.cmake
api=${ANDROID_API:-31}
abis=${ABIS:-arm64-v8a armeabi-v7a x86_64}

for abi in $abis; do
    cmake -S "$here" -B "$build/$abi" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$toolchain" \
        -DANDROID_ABI="$abi" \
        -DANDROID_PLATFORM="android-$api" \
        -DADBD_SRC="$src" \
        -DADBD_BUILD_TESTS="${ADBD_TESTS:-0}" \
        -DCMAKE_BUILD_TYPE=Release
    cmake --build "$build/$abi" --target adbd
    if [ "${ADBD_TESTS:-0}" = 1 ]; then
        cmake --build "$build/$abi" --target adbd_tests
        mkdir -p "$build/out/$abi"
        cp "$build/$abi/tests/adbd_tests" "$build/out/$abi/adbd_tests"
        echo "built $build/out/$abi/adbd_tests"
    fi
    mkdir -p "$build/out/$abi"
    cp "$build/$abi/adbd" "$build/out/$abi/adbd"
    # Keep symbols, drop debug info: the AOSP build uses strip keep_symbols.
    strip=$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip
    "$strip" --strip-debug "$build/out/$abi/adbd"
    echo "built $build/out/$abi/adbd"
done
