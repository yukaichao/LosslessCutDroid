# LosslessCutDroid / 无损快剪

> v0.6.0：重写 XML 界面，新增三种选区方式、慢放、逐帧模式入口、导出参数页和编码导出能力探测。

Android 本地无重编码视频时间裁剪 App。

## v0.6.0 改动

- 主界面简化为“打开视频 / 关于”，选中视频后进入预览选区页，再进入导出页。
- 选区支持三种方式：手输首尾时间、中心时间 + 前后范围、当前预览位置设为开始/结束。
- 预览继续使用 `TextureView + MediaPlayer`，新增 `0.25x / 0.5x / 1x` 慢放和双指缩放/拖动画面。
- 新增自定义时间轴拖动框。
- 新增逐帧模式：用 FFprobe 建帧索引，并尽量用 FFmpeg 精确抽帧。
- 导出页保留无重编码 `-c copy`，并新增编码导出参数页；编码选项按 APK 内 FFmpeg 能力动态启用。
- CI FFmpeg 构建改为 GPL/libx264 方向，支持软件 H.264 编码，并保留 MediaCodec 硬件编码尝试。

## v0.5.0 改动

- 状态区改为“摘要状态 + 可展开详细日志”，默认不把 FFmpeg 原始输出铺满界面。
- 新增“复制日志”“清空日志”，方便把实机错误直接贴出来。
- 新增“关于 / 免责 / 致谢”页面，包含本地处理说明、无重编码限制、FFmpeg 引用与许可证提示。
- CI 支持固定 APK 签名：配置 GitHub Secrets 后自动构建 signed release APK，避免每次 debug 证书不同导致无法覆盖安装。
- 新增 `docs/signing.md` 和 `docs/NOTICE.md`。

## v0.3.0 / v0.4.0 改动

针对实机反馈修复：预览区域黑框、输出文件看起来和源文件一样、裁剪完成后不能直接预览输出。

- 预览区增加首帧/指定时间帧缩略图覆盖层，避免未播放前只有黑框。
- 新增“预览输入 / 预览输出”按钮。
- 裁剪成功后自动切换到输出文件预览。
- 输出写入改为 `ContentResolver.openOutputStream(uri, "rwt")` 截断写入，避免新裁剪文件比旧文件短时残留旧文件尾部，导致看起来像没裁剪。
- 开始裁剪时严格校验手动输入时间，不再在解析失败时静默回退到完整时长。
- 日志明确显示最终裁剪范围和输出时长，例如 `00:01:11.000 → 00:01:46.000，输出时长 00:00:35.000`。
- 时间输入支持英文/中文冒号与逗号：`00:01:46.00`、`00:01:46,00`、`00：01：46.00`。

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

## 固定签名证书

如果每次 GitHub Actions 编译出来的 APK 证书不同，手机会无法直接覆盖安装。

本项目已经支持固定 release 签名。配置以下 GitHub Secrets 后，CI 会输出 signed release APK：

```text
ANDROID_KEYSTORE_BASE64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

详细步骤见：

```text
docs/signing.md
```

没有配置 Secrets 时，CI 会继续输出 debug APK；debug 证书可能随 runner 变化。

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
LosslessCutDroid-apk-with-ffmpeg
```

里面就是已经内置 FFmpeg / FFprobe 的 APK；配置签名 Secrets 后为 signed release APK，否则为 debug APK。

## 裁剪命令逻辑

App 先用 FFprobe 检测流信息，然后只映射可用的视频轨和正常音频轨：

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

## 无重编码限制

无重编码裁剪不是帧级精确裁剪。H.264 / H.265 / AV1 这类 GOP 编码通常只能从关键帧附近稳定开始，因此实际起点可能略早于用户输入时间。

如果需要帧级精确裁剪，需要做“边界重编码”模式，体积和复杂度都会上升。


## 许可证与致谢

本项目集成并调用 FFmpeg / FFprobe。FFmpeg 许可证和法律说明见：

```text
https://ffmpeg.org/legal.html
```

当前 CI 为支持编码导出启用 GPL/libx264，不启用 nonfree 组件。详细说明见：

```text
docs/NOTICE.md
```
