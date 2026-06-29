# Notice / 致谢与许可证说明

## LosslessCutDroid / 无损快剪

本项目是 Android 本地视频时间裁剪 App，负责文件选择、预览、时间范围选择、调用 FFmpeg/FFprobe、输出保存等 Android 端封装逻辑。

## FFmpeg / FFprobe

本 App 集成并调用 FFmpeg / FFprobe。

- 官网：https://ffmpeg.org/
- 许可证说明：https://ffmpeg.org/legal.html

当前 CI 构建为了支持编码导出，启用 `--enable-gpl` 和 `--enable-libx264`，但不启用 `--enable-nonfree`。实际发布时应：

1. 保留 FFmpeg 版权与许可证说明。
2. 保留本 Notice 文件或等价说明。
3. 提供对应 FFmpeg 源码获取方式、构建脚本和修改说明。
4. 按 GPL 要求提供本项目和所集成 GPL 组件的对应源码获取方式。

## x264

编码导出中的 FFmpeg 软件 H.264 编码可使用 x264。

- 官网/源码：https://code.videolan.org/videolan/x264
- 许可证：GPL

如果分发包含 x264 的 APK，应按 GPL 要求保留许可证说明、源码获取方式和构建脚本。

## 免责声明

本 App 仅用于本地媒体文件时间裁剪。无重编码裁剪通常按关键帧附近对齐，不保证帧级精确。请只处理你有权使用的媒体文件。开发者不对素材版权、输出兼容性、数据损失或由使用本工具造成的间接损失承担责任。
