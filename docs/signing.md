# 固定 APK 签名证书

GitHub Actions 默认构建 debug APK 时，会在临时 runner 上生成 debug keystore。不同 runner、不同清理状态下证书可能不同，手机安装时会被系统视为不同应用签名，导致无法覆盖升级。

解决方式：生成一次自己的 release keystore，把 keystore 以 GitHub Secrets 形式保存。后续 CI 每次都用同一个 keystore 签名 release APK。

## 1. 本地生成 keystore

Windows PowerShell：

```powershell
keytool -genkeypair `
  -v `
  -keystore losslesscutdroid-release.jks `
  -storetype JKS `
  -keyalg RSA `
  -keysize 4096 `
  -validity 10000 `
  -alias losslesscutdroid `
  -dname "CN=LosslessCutDroid,OU=Android,O=yukaichao,L=Hangzhou,ST=Zhejiang,C=CN"
```

请记住输入的 keystore 密码和 key 密码。不要把 `.jks` 文件提交到公开仓库。

## 2. 转成 base64

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes(".\losslesscutdroid-release.jks")) | Set-Clipboard
```

剪贴板内容就是 `ANDROID_KEYSTORE_BASE64`。

## 3. 在 GitHub 仓库添加 Secrets

进入：

```text
GitHub 仓库 -> Settings -> Secrets and variables -> Actions -> New repository secret
```

添加 4 个 secret：

```text
ANDROID_KEYSTORE_BASE64      上一步复制的 base64 字符串
ANDROID_KEYSTORE_PASSWORD    生成 keystore 时输入的 store password
ANDROID_KEY_ALIAS            losslesscutdroid
ANDROID_KEY_PASSWORD         生成 key 时输入的 key password
```

## 4. 重新运行 Actions

重新运行 workflow 后，如果 Secrets 配置完整，CI 会构建：

```text
app-release.apk
```

这个 APK 后续每次构建签名证书一致，可以覆盖安装升级。

如果没有配置 Secrets，CI 会退回 debug APK，debug 证书仍可能变化。

## 5. 查看证书指纹

```powershell
keytool -list -v -keystore losslesscutdroid-release.jks -alias losslesscutdroid
```

记录 SHA-256 指纹。以后每次 release APK 的签名指纹应保持一致。
