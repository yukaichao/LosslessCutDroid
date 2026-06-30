# Android FFmpeg 构建注意事项

本项目使用 GitHub Actions 在线交叉编译 FFmpeg CLI，不再要求你手工下载二进制。

核心脚本：

```text
scripts/build_ffmpeg_android_arm64.sh
```

默认参数：

```bash
FFMPEG_VERSION=8.0
ANDROID_API=26
ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018
```

输出：

```text
prebuilt/ffmpeg/arm64-v8a/libffmpeg.so
```

---

## 当前 FFmpeg 配置目标

目标同时支持无重编码裁剪、逐帧预览和编码导出：

```bash
-map 0 -c copy
```

以及类似：

```bash
ffmpeg -ss 10.000 -i input.mp4 -t 5.000 \
  -c:v libx264 -b:v 4000k -preset medium -c:a aac output.mp4
```

当前配置倾向于功能完整：

- 保留 FFmpeg CLI。
- 保留 demuxers / muxers / parsers / bitstream filters / filters。
- 启用 decoder / encoder，用于格式兼容、能力探测和转码。
- 启用 `--enable-gpl` 与 `--enable-libx264`。
- 启用 Android MediaCodec/JNI，用于可用设备上的硬件编码尝试。
- 禁用 network。
- 不启用 nonfree。
- 静态链接为单个 Android arm64-v8a 可执行文件。

---

## 许可证影响

启用 x264 后，构建产物不再是 LGPL-only。分发 APK 时需要按 GPL 要求保留许可证说明、源码获取方式和构建脚本。对应说明见：

```text
docs/NOTICE.md
```

如果需要恢复 LGPL-only 轻量构建，应移除 `--enable-gpl`、`--enable-libx264`，并再次禁用 encoders / decoders / filters。

---

## 推荐扩展方向

### 支持更多 ABI

增加脚本：

```text
scripts/build_ffmpeg_android_armeabi_v7a.sh
scripts/build_ffmpeg_android_x86_64.sh
```

并生成：

```text
prebuilt/ffmpeg/armeabi-v7a/libffmpeg.so
prebuilt/ffmpeg/x86_64/libffmpeg.so
```

同时修改：

```gradle
ndk {
    abiFilters 'arm64-v8a', 'armeabi-v7a', 'x86_64'
}
```

### 加入 ffprobe

当前 App 已启用 FFprobe，用于读取流信息并跳过异常空音轨。CI 会输出：

```text
libffmpeg.so
libffprobe.so
```

Java 侧再用同样方式执行。

### 精确模式

当前逐帧预览优先使用 Android 系统解码器，并在逐帧界面持续持有解码对象、缓存当前位置附近的原始分辨率帧。FFmpeg/FFprobe 仍用于裁剪、编码导出和能力探测。
