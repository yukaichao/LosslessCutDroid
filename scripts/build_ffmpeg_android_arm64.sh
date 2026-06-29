#!/usr/bin/env bash
set -euo pipefail

# Build a GPL-enabled FFmpeg CLI executable for Android arm64-v8a.
# The output is intentionally named libffmpeg.so so Android Gradle packages it as a native library,
# then MainActivity can execute them from nativeLibraryDir via ProcessBuilder.

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
X264_SRC_DIR="${WORK_DIR}/x264"
X264_INSTALL_DIR="${WORK_DIR}/install/x264-arm64-v8a"
PREBUILT_DIR="${ROOT_DIR}/prebuilt/ffmpeg/arm64-v8a"
OUT_BIN="${PREBUILT_DIR}/libffmpeg.so"
OUT_PROBE="${PREBUILT_DIR}/libffprobe.so"
MANIFEST_FILE="${PREBUILT_DIR}/ffmpeg-build-manifest.txt"

mkdir -p "${WORK_DIR}" "${PREBUILT_DIR}"

if [[ -s "${OUT_BIN}" && -s "${OUT_PROBE}" && -f "${MANIFEST_FILE}" ]] && grep -q "GPL enabled, libx264 enabled" "${MANIFEST_FILE}"; then
  echo "FFmpeg already exists: ${OUT_BIN}"
  echo "FFprobe already exists: ${OUT_PROBE}"
  file "${OUT_BIN}" || true
  file "${OUT_PROBE}" || true
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

if [[ ! -d "${X264_SRC_DIR}/.git" ]]; then
  rm -rf "${X264_SRC_DIR}"
  echo "Cloning x264 stable branch..."
  git clone --depth 1 --branch stable https://code.videolan.org/videolan/x264.git "${X264_SRC_DIR}"
fi

echo "Building x264 for Android arm64-v8a..."
cd "${X264_SRC_DIR}"
make distclean >/dev/null 2>&1 || true
CC="${CC}" \
AR="${AR}" \
RANLIB="${RANLIB}" \
STRIP="${STRIP}" \
./configure \
  --prefix="${X264_INSTALL_DIR}" \
  --host=aarch64-linux-android \
  --cross-prefix="${TOOLCHAIN}/bin/llvm-" \
  --sysroot="${TOOLCHAIN}/sysroot" \
  --enable-static \
  --enable-pic \
  --disable-cli \
  --disable-asm
make -j"$(nproc)"
make install-lib-static

cd "${SRC_DIR}"

# This configuration supports both stream-copy cutting and transcoding.
# It enables GPL because libx264 is included. Do not publish this build as LGPL-only.
export PKG_CONFIG_PATH="${X264_INSTALL_DIR}/lib/pkgconfig"
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
  --extra-cflags="-fPIC -I${X264_INSTALL_DIR}/include" \
  --extra-ldflags="-pie -L${X264_INSTALL_DIR}/lib" \
  --extra-libs="-lm" \
  --pkg-config-flags="--static" \
  --disable-shared \
  --enable-static \
  --enable-gpl \
  --enable-libx264 \
  --disable-doc \
  --disable-debug \
  --disable-ffplay \
  --enable-ffprobe \
  --enable-ffmpeg \
  --disable-network \
  --disable-autodetect \
  --disable-symver \
  --disable-iconv \
  --disable-bzlib \
  --disable-lzma \
  --disable-sdl2 \
  --disable-securetransport \
  --disable-vulkan \
  --enable-mediacodec \
  --enable-jni \
  --disable-devices \
  --disable-indevs \
  --disable-outdevs \
  --enable-protocol=file \
  --enable-protocol=pipe

make -j"$(nproc)" ffmpeg ffprobe
"${STRIP}" ffmpeg ffprobe || true
cp -f ffmpeg "${OUT_BIN}"
cp -f ffprobe "${OUT_PROBE}"
chmod 755 "${OUT_BIN}" "${OUT_PROBE}"

# Keep a small manifest for license/debug review.
{
  echo "FFmpeg-Version: ${FFMPEG_VERSION}"
  echo "Android-ABI: arm64-v8a"
  echo "Android-API: ${ANDROID_API}"
  echo "License-Intent: GPL enabled, libx264 enabled, no --enable-nonfree"
  echo "x264-Source: https://code.videolan.org/videolan/x264.git stable"
  echo "Built-At-UTC: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "Outputs:"
  echo "  ${OUT_BIN}"
  echo "  ${OUT_PROBE}"
  echo "Note: Android ARM64 binaries are not executed on the x86_64 GitHub runner."
} > "${MANIFEST_FILE}"

echo "Built FFmpeg binary: ${OUT_BIN}"
echo "Built FFprobe binary: ${OUT_PROBE}"
ls -lh "${OUT_BIN}" "${OUT_PROBE}"
file "${OUT_BIN}" || true
file "${OUT_PROBE}" || true
