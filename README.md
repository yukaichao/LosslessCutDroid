# Lossless Cutter Android

Android 本地视频时间裁剪 App。核心目标：

- 对 FFmpeg 支持的容器/编码格式进行**时间裁剪**。
- 不改变画面尺寸。
- 默认不重新编码，使用 FFmpeg `-c copy` / stream copy。
- 通过 Android SAF 选择输入和输出文件，兼容 Android 10+ 分区存储。
- GitHub Actions 在线编译 FFmpeg Android arm64-v8a 二进制，并自动打包进 APK。

---

## 当前实现结构

```text
LosslessCutterAndroid/
  settings.gradle
  build.gradle
  app/
    build.gradle
    src/main/
      AndroidManifest.xml
      java/com/example/losslesscutter/MainActivity.java
      jniLibs/arm64-v8a/              # Gradle 构建前自动复制 FFmpeg 到这里
  scripts/
    build_ffmpeg_android_arm64.sh     # 在线/本地交叉编译 FFmpeg
  prebuilt/
    ffmpeg/arm64-v8a/libffmpeg.so     # CI 生成；默认不提交
  .github/workflows/
    android-debug-apk.yml             # 一键编译 FFmpeg + APK
```

---

## 在线编译：FFmpeg + APK 一起编译

本项目已经配置：

```text
.github/workflows/android-debug-apk.yml
```

上传到 GitHub 仓库后：

1. 打开仓库 **Actions**。
2. 选择 **Build Android APK with FFmpeg**。
3. 点击 **Run workflow**。
4. 保持默认参数即可：
   - `ffmpeg_version = 8.0`
   - `android_api = 26`
5. 编译完成后下载 artifact：

```text
LosslessCutter-debug-apk-with-ffmpeg
```

里面就是已经内置 FFmpeg 的 debug APK。

同时还会生成一个 FFmpeg 单独产物：

```text
ffmpeg-android-arm64-v8a
```

里面包含：

```text
libffmpeg.so
ffmpeg-build-manifest.txt
```

---

## 项目如何引用 FFmpeg 产物

CI 先执行：

```bash
bash scripts/build_ffmpeg_android_arm64.sh
```

脚本会生成：

```text
prebuilt/ffmpeg/arm64-v8a/libffmpeg.so
```

然后 Gradle 的 `prepareFfmpegBinary` 任务会自动复制到：

```text
app/src/main/jniLibs/arm64-v8a/libffmpeg.so
```

最后 Android Gradle Plugin 会把它作为 native library 打包进 APK。App 运行时从：

```java
getApplicationInfo().nativeLibraryDir + "/libffmpeg.so"
```

找到并通过 `ProcessBuilder` 执行。

> 注意：文件名是 `libffmpeg.so`，但内容是 Android PIE 可执行文件。这样做是为了让 Android 安装器把它解压到可执行的 native library 目录。

---

## 本地编译 FFmpeg

如果你本地有 Android SDK / NDK：

```bash
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018
export FFMPEG_VERSION=8.0
export ANDROID_API=26
bash scripts/build_ffmpeg_android_arm64.sh
```

然后构建 APK：

```bash
gradle :app:assembleDebug -PrequireFfmpeg=true
```

---

## 裁剪命令

App 内部执行逻辑等价于：

```bash
ffmpeg -hide_banner -y \
  -ss 80.500 \
  -i input.mp4 \
  -t 30.000 \
  -map 0 \
  -c copy \
  -map_metadata 0 \
  -avoid_negative_ts make_zero \
  -movflags +faststart \
  output.mp4
```

其中：

- `-ss`：开始时间。
- `-t`：持续时间，由 `结束时间 - 开始时间` 计算。
- `-map 0`：保留全部流，包括视频、音频、字幕、附件等。
- `-c copy`：不解码、不滤镜、不重新编码，只复制码流。
- `-movflags +faststart`：MP4/MOV/M4V 输出时优化 moov atom 位置，便于播放。

---

## FFmpeg 构建配置

`scripts/build_ffmpeg_android_arm64.sh` 当前是为“无重编码裁剪”定制的小体积配置：

- 开启 FFmpeg CLI。
- 静态链接为单个 Android arm64-v8a 可执行文件。
- 保留 demuxers / muxers / parsers / bitstream filters。
- 禁用 encoders / decoders / filters。
- 禁用 network。
- 禁用 GPL / nonfree 外部组件。

它主要服务于：

```bash
-map 0 -c copy
```

也就是重新封装/流复制，不负责转码。

如果后续要加入“精确边界重编码”模式，就需要重新开启对应解码器、编码器、滤镜，或者接入 Android MediaCodec/JNI 路径。

---

## 无重编码裁剪限制

无重编码裁剪不是帧级编辑。对 H.264/H.265/AV1 这类 GOP 编码：

- 开始点通常会落在目标时间附近的关键帧/seek point。
- 输出可能比你设置的开始时间略早或略晚。
- 如果必须毫秒级/帧级精确，需要“边界重编码”或全段重编码。

本项目首版只做无重编码。后续可以加两个模式：

1. **快速无损模式**：`-c copy`，快，无画质损失，但不保证帧精确。
2. **精确边界模式**：只重编码开头/结尾 GOP，中间 `-c copy`，速度和质量介于二者之间。

---

## 支持格式

理论上支持范围由内置 FFmpeg 构建决定。当前配置主要面向常见容器的 remux / stream copy：

- MP4 / MOV / M4V
- MKV / WebM
- AVI
- TS / M2TS
- 3GP

但“能读取”不等于“能无损写回原容器”。例如某些编码流不能直接放进某个输出容器，此时 FFmpeg 会报错。实用策略：

- 原始 MP4 通常输出 MP4。
- 原始 MKV 通常输出 MKV。
- 不确定兼容性时，优先输出 MKV。

---

## 许可建议

当前 CI 脚本目标是 LGPL-only：

- 不启用 `--enable-gpl`。
- 不启用 `--enable-nonfree`。
- 不编入 libx264/libx265 等 GPL/外部编码器。

如果 App 要公开发布，建议在 About/设置页加入：

- FFmpeg 版本。
- configure 参数。
- FFmpeg 许可证说明。
- 对应源码获取方式。

