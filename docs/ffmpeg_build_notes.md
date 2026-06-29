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

目标不是转码，而是无重编码裁剪：

```bash
-map 0 -c copy
```

所以配置倾向于小体积：

- 保留 FFmpeg CLI。
- 保留 demuxers / muxers / parsers / bitstream filters。
- 禁用 encoders / decoders / filters。
- 禁用 network。
- 不启用 GPL / nonfree。
- 静态链接为单个 Android arm64-v8a 可执行文件。

---

## 为什么禁用 encoder/decoder

纯 stream copy 不需要解码和编码，FFmpeg 只需要：

- 读取容器：demuxer
- 解析码流：parser
- 必要时调整封装位流：bitstream filter
- 写入容器：muxer

因此当前配置能显著减小体积和构建时间。

如果你要加入“帧精确裁剪”或“边界重编码”，就需要重新开启相关组件。

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

如果后续要在 App 里读取时长、码率、流信息，可以把 `--disable-ffprobe` 改成 `--enable-ffprobe`，并输出：

```text
libffprobe.so
```

Java 侧再用同样方式执行。

### 加入精确模式

需要至少开启：

- 对应视频/音频 decoder。
- 对应 encoder，或接 Android MediaCodec。
- trim/concat/filter 相关能力。

首版不建议直接做全量转码，体积和稳定性会明显变差。

