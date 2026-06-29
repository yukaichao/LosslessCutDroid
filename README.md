# LosslessCutDroid / 无损快剪

Android 本地无重编码视频时间裁剪 App。

## v0.2.0 改动

- 新增视频预览窗口。
- 新增开始/结束时间拖动条。
- 新增“播放选区”“当前设为开始”“当前设为结束”。
- 新增手动输入精确时间后应用。
- 新增 FFprobe 在线编译和打包，用于检测视频流/音频流。
- 修复部分手机视频存在异常空音轨时，MP4 输出报错的问题：
  - 自动跳过 `codec=none/unknown`、`0 channels`、无 sample rate 的异常音轨。
  - 首次失败后自动兜底为只输出主视频轨。

## 核心目标

- 按时间裁剪视频。
- 不改变画面尺寸。
- 默认不重新编码，使用 FFmpeg `-c copy` / stream copy。
- 使用 Android SAF 选择输入和输出文件。
- GitHub Actions 在线编译 FFmpeg + FFprobe Android arm64-v8a 二进制，并自动打包 APK。

## 在线编译

上传到 GitHub 仓库后：

1. 打开仓库 **Actions**。
2. 选择 **Build Android APK with FFmpeg**。
3. 点击 **Run workflow**。
4. 默认参数即可：
   - `ffmpeg_version = 8.0`
   - `android_api = 26`
5. 编译完成后下载 artifact：

```text
LosslessCutter-debug-apk-with-ffmpeg
```

里面就是已经内置 FFmpeg / FFprobe 的 debug APK。

## 项目结构

```text
LosslessCutDroid/
  settings.gradle
  build.gradle
  app/
    build.gradle
    src/main/
      AndroidManifest.xml
      java/com/example/losslesscutter/MainActivity.java
  scripts/
    build_ffmpeg_android_arm64.sh
  prebuilt/
    ffmpeg/arm64-v8a/libffmpeg.so
    ffmpeg/arm64-v8a/libffprobe.so
  .github/workflows/
    android-debug-apk.yml
```

`prebuilt/ffmpeg/arm64-v8a/` 里的二进制由 CI 生成，默认不需要手动提交。

## 裁剪命令逻辑

App 不再直接 `-map 0` 保留所有流，而是先用 FFprobe 检测流信息，然后只映射可用的视频轨和正常音频轨：

```bash
ffprobe -v error -show_entries stream=index,codec_type,codec_name,sample_rate,channels -of json input.mp4
```

然后执行类似：

```bash
ffmpeg -hide_banner -y \
  -ss 71.000 \
  -i input.mp4 \
  -t 35.000 \
  -ignore_unknown \
  -map 0:0 \
  -c copy \
  -map_metadata 0 \
  -avoid_negative_ts make_zero \
  -movflags +faststart \
  output.mp4
```

如果检测到正常音频轨，会额外加入对应 `-map 0:<audio_index>`。

## 为什么修复了异常空音轨问题

你遇到的失败日志里有：

```text
Stream #0:1: Audio: none, 0 channels
sample rate not set
Could not write header (incorrect codec parameters ?)
```

这是源文件里存在一个“音频类型但没有真实编码参数”的异常空音轨。旧版使用 `-map 0` 把它也复制进 MP4，导致写文件头失败。

新版改为：

- 正常视频轨：保留。
- 正常音频轨：保留。
- `Audio: none`、`unknown codec`、`0 channels`、无采样率：跳过。
- 仍失败时：自动重试主视频轨。

## 无重编码限制

无重编码裁剪不是帧级精确裁剪。H.264 / H.265 / AV1 这类 GOP 编码通常只能从关键帧附近稳定开始，因此实际起点可能略早于用户输入时间。

如果需要帧级精确裁剪，需要做“边界重编码”模式，体积和复杂度都会上升。
