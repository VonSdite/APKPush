# 发布 APK

正式包使用 `release` 构建类型，`debuggable=false`，由独立发布密钥签名。开发调试包使用单独的 debug 密钥，两种签名不能直接互相覆盖安装。

1.12.1 发布证书的 SHA-256 指纹为：

```text
699b16465797ca1483ae8cefa122e08568b0daa7969f4dc84244dfbf3c994bad
```

## 本地签名配置

发布密钥和配置放在被 Git 忽略的 `.signing/`。配置文件 `.signing/release.properties` 的格式为：

```properties
storeFile=.signing/release.keystore
keyAlias=apkpush
storePassword=<local-secret>
keyPassword=<local-secret>
```

也可使用环境变量 `APK_PUSH_KEYSTORE`、`APK_PUSH_KEY_ALIAS`、`APK_PUSH_STORE_PASSWORD`、`APK_PUSH_KEY_PASSWORD`；环境变量优先于本地配置。缺少有效配置时，release 构建会报错，不会使用 debug 密钥代替。

密钥及密码需要单独备份，后续升级使用同一发布身份。它们不属于 Git 仓库或 GitHub Release 附件。

## 构建与校验

Linux / WSL：

```sh
sh scripts/build.sh testDebugUnitTest lintRelease packageReleaseApk
```

PowerShell 7：

```powershell
./scripts/build.ps1 -Tasks @('testDebugUnitTest', 'lintRelease', 'packageReleaseApk')
```

当前产物为 `artifacts/APK-Push-1.12.1-release.apk`。使用 Android SDK 的 `apksigner verify --verbose --print-certs` 验证签名，使用 `apkanalyzer manifest debuggable` 检查调试标记，并计算 SHA-256。

## GitHub Release

1. 提交对应源码和验证说明，为该提交创建版本标签。
2. 创建 Release，附上签名 APK 和同名 `.sha256` 校验文件。
3. 说明版本、主要功能、兼容性范围与签名变化；源码由对应标签提供。
4. 核对公开附件的文件名、大小与 SHA-256，确认草稿发布状态。

Git 仓库仅保存源码、必要文档和展示图。APK 和校验文件作为 Release 附件发布。

## 从 debug 版切换

Android 会拒绝签名不同的覆盖安装。卸载 debug 版会删除应用私有数据，包括设备记录、任务和下载库索引；公共 `Download/APK Push/` 文件仍保留。当前没有跨签名的自动数据迁移，应先自行备份需要的数据。
