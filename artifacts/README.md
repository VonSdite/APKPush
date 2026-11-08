# APK 交付目录

`APK-Push-1.12.1-release.apk` 是正式签名包，`debuggable=false`，安装目标为 Android 10 及以上手机。包名为 `dev.tvapkpush`，版本号为 `1.12.1` / `26`。同名 `.sha256` 文件记录 APK 的 SHA-256 摘要。

项目根目录的 `scripts/build.ps1` 或 `scripts/build.sh` 负责检查与打包。`packageReleaseApk` 和 `packageDebugApk` 分别将正式包和调试包复制到本目录。签名配置见[发布指南](../docs/releasing.md)。

APK、校验文件、原始截图与签名私钥不纳入 Git。正式 APK 和校验文件通过 [GitHub Releases](https://github.com/VonSdite/APKPush/releases/latest) 分发；公开展示图位于 `docs/images/`。后续正式版本使用同一发布密钥，调试包使用独立签名。

功能范围、使用步骤见项目 [README](../README.md)，测试结果及模拟器的授权存储限制见 [验证说明](../docs/validation.md)。

APK Push 使用 GPL-3.0-or-later。对外分发 APK 时须随附对应源码或提供符合 GPLv3 的源码获取方式，并保留依赖许可。根目录 LICENSE 和应用“开源许可”入口提供许可证信息。
