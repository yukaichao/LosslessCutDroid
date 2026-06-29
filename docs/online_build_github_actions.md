# 在线编译 APK：GitHub Actions 同时编译 FFmpeg

本项目内置：

```text
.github/workflows/android-debug-apk.yml
```

workflow 会顺序执行：

1. 安装 Android SDK / NDK。
2. 下载 FFmpeg 官方 release 源码包。
3. 使用 Android NDK clang 交叉编译 arm64-v8a FFmpeg / FFprobe CLI。
4. 生成 `prebuilt/ffmpeg/arm64-v8a/libffmpeg.so` 和 `libffprobe.so`。
5. Gradle 直接把 `prebuilt/ffmpeg` 作为 `jniLibs` 源目录打包。
6. 如果配置了固定签名 Secrets，则构建 signed release APK；否则构建 debug APK。
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
LosslessCutDroid-apk-with-ffmpeg
```

配置签名 Secrets 后，里面是 signed release APK；未配置时，里面是 debug APK。

---

## 固定签名

如果需要每次 APK 都能覆盖安装升级，请先按 `docs/signing.md` 配置：

```text
ANDROID_KEYSTORE_BASE64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

未配置时，GitHub Actions 使用临时 runner 的 debug key，证书可能变化。

---

## 产物说明

### APK 产物

```text
LosslessCutDroid-apk-with-ffmpeg
```

包含已内置 FFmpeg / FFprobe 的 APK。

### FFmpeg 单独产物

```text
ffmpeg-android-arm64-v8a
```

包含：

```text
libffmpeg.so
libffprobe.so
ffmpeg-build-manifest.txt
```

---

## 为什么 FFmpeg 叫 libffmpeg.so

Android Gradle Plugin 会自动打包 `jniLibs/<abi>/*.so` 到 APK 的 native library 区域。

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

`app/build.gradle` 直接配置：

```gradle
sourceSets {
    main {
        jniLibs.srcDirs = [file("${rootDir}/prebuilt/ffmpeg")]
    }
}
```

CI 先生成：

```text
prebuilt/ffmpeg/arm64-v8a/libffmpeg.so
prebuilt/ffmpeg/arm64-v8a/libffprobe.so
```

Gradle 随后直接打包。这样不会在构建期间写入 `app/src/main/jniLibs`，避免 Gradle 8 / AGP 的隐式任务依赖错误。

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
prebuilt/ffmpeg/arm64-v8a/libffprobe.so
```

并确认 Gradle 命令带了：

```bash
-PrequireFfmpeg=true
```

### 4. 手机上的 FFmpeg 执行 permission denied

检查：

- `app/build.gradle` 里 `packagingOptions.jniLibs.useLegacyPackaging true` 是否存在。
- `libffmpeg.so` 是否位于 `prebuilt/ffmpeg/arm64-v8a/`。
