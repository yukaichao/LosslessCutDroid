#!/usr/bin/env bash
set -euo pipefail

# Build a small LGPL-only FFmpeg CLI executable for Android arm64-v8a.
# The output is intentionally named libffmpeg.so so Android Gradle packages it as a native library,
# then MainActivity can execute it from nativeLibraryDir via ProcessBuilder.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FFMPEG_VERSION="${FFMPEG_VERSION:-8.0}"
ANDROID_API="${ANDROID_API:-26}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"

if [[ -z "${ANDROID_NDK_HOME}" ]]; then
  echo "ANDROID_NDK_HOME or ANDROID_NDK_ROOT is required" >&2
  exit 2
fi

HOST_TAG="linux-x86_64"
TOOLCHAIN="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${HOST_TAG}"
if [[ ! -d "${TOOLCHAIN}" ]]; then
  echo "NDK LLVM toolchain not found: ${TOOLCHAIN}" >&2
  exit 2
fi

WORK_DIR="${ROOT_DIR}/build/ffmpeg-android-arm64"
SRC_ARCHIVE="${WORK_DIR}/ffmpeg-${FFMPEG_VERSION}.tar.xz"
SRC_DIR="${WORK_DIR}/ffmpeg-${FFMPEG_VERSION}"
INSTALL_DIR="${WORK_DIR}/install/arm64-v8a"
PREBUILT_DIR="${ROOT_DIR}/prebuilt/ffmpeg/arm64-v8a"
OUT_BIN="${PREBUILT_DIR}/libffmpeg.so"

mkdir -p "${WORK_DIR}" "${PREBUILT_DIR}"

if [[ -s "${OUT_BIN}" ]]; then
  echo "FFmpeg already exists: ${OUT_BIN}"
  file "${OUT_BIN}" || true
  exit 0
fi

if [[ ! -f "${SRC_ARCHIVE}" ]]; then
  echo "Downloading FFmpeg ${FFMPEG_VERSION} source..."
  curl -L --fail --retry 3 \
    "https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz" \
    -o "${SRC_ARCHIVE}"
fi

if [[ ! -d "${SRC_DIR}" ]]; then
  echo "Extracting FFmpeg source..."
  tar -xf "${SRC_ARCHIVE}" -C "${WORK_DIR}"
fi

cd "${SRC_DIR}"

# Clean stale config if the cache restored a half-built source tree.
if [[ -f config.mak ]]; then
  make distclean >/dev/null 2>&1 || true
fi

CC="${TOOLCHAIN}/bin/aarch64-linux-android${ANDROID_API}-clang"
CXX="${TOOLCHAIN}/bin/aarch64-linux-android${ANDROID_API}-clang++"
AR="${TOOLCHAIN}/bin/llvm-ar"
RANLIB="${TOOLCHAIN}/bin/llvm-ranlib"
STRIP="${TOOLCHAIN}/bin/llvm-strip"
NM="${TOOLCHAIN}/bin/llvm-nm"

# This configuration is for remux / stream-copy cutting, not transcoding.
# It keeps demuxers, muxers, parsers, and bitstream filters, but disables encoders/decoders/filters.
# That is usually enough for: -map 0 -c copy, with common MP4/MOV/MKV/WebM/AVI/TS inputs.
./configure \
  --prefix="${INSTALL_DIR}" \
  --target-os=android \
  --arch=aarch64 \
  --cpu=armv8-a \
  --enable-cross-compile \
  --cc="${CC}" \
  --cxx="${CXX}" \
  --ar="${AR}" \
  --ranlib="${RANLIB}" \
  --nm="${NM}" \
  --strip="${STRIP}" \
  --sysroot="${TOOLCHAIN}/sysroot" \
  --extra-cflags="-fPIC" \
  --extra-ldflags="-pie" \
  --disable-shared \
  --enable-static \
  --disable-doc \
  --disable-debug \
  --disable-ffplay \
  --disable-ffprobe \
  --enable-ffmpeg \
  --disable-network \
  --disable-autodetect \
  --disable-symver \
  --disable-iconv \
  --disable-zlib \
  --disable-bzlib \
  --disable-lzma \
  --disable-sdl2 \
  --disable-securetransport \
  --disable-vulkan \
  --disable-mediacodec \
  --disable-jni \
  --disable-hwaccels \
  --disable-devices \
  --disable-indevs \
  --disable-outdevs \
  --disable-filters \
  --disable-decoders \
  --disable-encoders \
  --enable-protocol=file \
  --enable-protocol=pipe

make -j"$(nproc)" ffmpeg
"${STRIP}" ffmpeg || true
cp -f ffmpeg "${OUT_BIN}"
chmod 755 "${OUT_BIN}"

# Keep a small manifest for license/debug review.
{
  echo "FFmpeg-Version: ${FFMPEG_VERSION}"
  echo "Android-ABI: arm64-v8a"
  echo "Android-API: ${ANDROID_API}"
  echo "License-Intent: LGPL-only, no --enable-gpl, no --enable-nonfree"
  echo "Built-At-UTC: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "Configure:"
  ./ffmpeg -hide_banner -version | sed 's/^/  /' || true
} > "${PREBUILT_DIR}/ffmpeg-build-manifest.txt"

echo "Built FFmpeg binary: ${OUT_BIN}"
ls -lh "${OUT_BIN}"
file "${OUT_BIN}" || true
