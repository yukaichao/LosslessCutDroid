# 在线编译 APK：GitHub Actions 同时编译 FFmpeg

本项目内置：

```text
.github/workflows/android-debug-apk.yml
```

这个 workflow 会顺序执行：

1. 安装 Android SDK / NDK。
2. 下载 FFmpeg 官方 release 源码包。
3. 使用 Android NDK clang 交叉编译 arm64-v8a FFmpeg CLI。
4. 生成 `prebuilt/ffmpeg/arm64-v8a/libffmpeg.so`。
5. Gradle 自动把它复制到 `app/src/main/jniLibs/arm64-v8a/libffmpeg.so`。
6. 构建 debug APK。
7. 上传 APK artifact 和 FFmpeg binary artifact。

---

## 使用步骤

1. 新建 GitHub 仓库。
2. 把本项目所有文件上传到仓库根目录。
3. 打开仓库的 **Actions** 页面。
4. 选择 **Build Android APK with FFmpeg**。
5. 点击 **Run workflow**。
6. 使用默认参数：

```text
ffmpeg_version = 8.0
android_api = 26
```

7. 编译完成后，在本次 workflow 页面底部下载：

```text
LosslessCutter-debug-apk-with-ffmpeg
```

解压后就是已经内置 FFmpeg 的 `app-debug.apk`。

---

## 产物说明

### APK 产物

```text
LosslessCutter-debug-apk-with-ffmpeg
```

包含 Android debug APK，可直接安装测试。

### FFmpeg 单独产物

```text
ffmpeg-android-arm64-v8a
```

包含：

```text
libffmpeg.so
ffmpeg-build-manifest.txt
```

这个 `libffmpeg.so` 可以复用到其它 Android 项目里。

---

## 为什么 FFmpeg 叫 libffmpeg.so

Android Gradle Plugin 会自动打包 `app/src/main/jniLibs/<abi>/*.so` 到 APK 的 native library 区域。

本项目把 FFmpeg CLI 可执行文件重命名为：

```text
libffmpeg.so
```

App 安装后，系统会把它解压到：

```text
getApplicationInfo().nativeLibraryDir
```

Java 代码再用 `ProcessBuilder` 执行它。

这是为了避开 Android 10+ 对“从 App 可写目录执行二进制”的限制。直接把可执行文件复制到普通 files/cache 目录再执行，在新系统上更容易遇到 permission denied。

---

## Gradle 如何引用 FFmpeg

`app/build.gradle` 增加了任务：

```text
prepareFfmpegBinary
```

它会把：

```text
prebuilt/ffmpeg/arm64-v8a/libffmpeg.so
```

复制到：

```text
app/src/main/jniLibs/arm64-v8a/libffmpeg.so
```

并挂到 Android 的 `merge*NativeLibs` 任务前面。

CI 构建 APK 时使用：

```bash
gradle :app:assembleDebug -PrequireFfmpeg=true
```

如果 FFmpeg 没生成，构建会直接失败，避免得到一个“能安装但不能裁剪”的 APK。

---

## 修改 FFmpeg 版本

在 Actions 手动运行时改：

```text
ffmpeg_version
```

例如：

```text
8.0
7.1.1
```

前提是 `https://ffmpeg.org/releases/ffmpeg-<version>.tar.xz` 存在。

---

## 修改 Android API / ABI

当前脚本只做：

```text
arm64-v8a
Android API 26
```

如果要支持更多 ABI，需要复制并扩展：

```text
scripts/build_ffmpeg_android_arm64.sh
app/build.gradle 的 abiFilters
.github/workflows/android-debug-apk.yml
```

建议顺序：

1. arm64-v8a：现代手机主力。
2. armeabi-v7a：老 32 位手机。
3. x86_64：模拟器/少量设备。

---

## 常见失败原因

### 1. FFmpeg configure 失败

通常是 NDK 路径、API level 或某个 configure 参数不兼容。先看日志中 `Unknown option` 或 `C compiler test failed`。

### 2. make 失败

可能是 FFmpeg 新版本改了依赖关系。优先固定到一个已验证版本，例如 `8.0`，不要直接追 master。

### 3. APK 能装但裁剪提示找不到 FFmpeg

说明 `libffmpeg.so` 没被打包。检查：

```text
prebuilt/ffmpeg/arm64-v8a/libffmpeg.so
app/src/main/jniLibs/arm64-v8a/libffmpeg.so
```

并确认 Gradle 命令带了：

```bash
-PrequireFfmpeg=true
```

### 4. 手机上的 FFmpeg 执行 permission denied

检查：

- `AndroidManifest.xml` 里 `android:extractNativeLibs="true"` 是否存在。
- `app/build.gradle` 里 `useLegacyPackaging true` 是否存在。
- `libffmpeg.so` 是否位于 `jniLibs/arm64-v8a/`。

