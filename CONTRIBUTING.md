# 参与 APK Push / Contributing

感谢你帮助完善 APK Push。欢迎问题反馈、设备兼容性报告、文档改进和代码贡献。

## 报告问题

请在 [Issues](https://github.com/VonSdite/APKPush/issues) 中提供：

- APK Push 版本，以及手机和目标设备的型号、Android 版本。
- 操作步骤、预期结果、实际结果和界面错误信息。
- 涉及推送时说明使用传统网络 ADB 还是无线调试配对；涉及下载时说明应用包名和下载机型。
- 必要的截图或日志；提交前移除账号、访问令牌、设备序列号和其他私人信息。

功能建议请说明使用场景，以及当前操作在哪一步不方便。较大的功能或架构调整，建议先开 Issue 讨论范围。

## 提交代码

1. Fork 仓库并创建一个聚焦单项问题的分支。
2. 按 [README](README.md#从源码构建) 配置 JDK 和 Android SDK。
3. 遵循项目 [AGENTS.md](AGENTS.md) 中的约定：UTF-8 无 BOM、LF 换行；运行日志使用英文，新注释默认使用中文。
4. 为行为变化补充相关测试，使用 `scripts/build.sh` 或 `scripts/build.ps1` 完成测试、Lint 和构建检查。
5. 在 Pull Request 中说明问题、用户可见的行为、验证结果及已知限制。界面变化附上截图。

构建缓存、调试输出和临时脚本放入根目录 `tmp/`。提交不包含 APK、密钥、访问令牌或本机环境配置。仅涉及文档的修改可检查链接、示例和 `git diff --check`，无需重跑设备测试。

贡献将按项目的 GPL-3.0-or-later 许可证分发，第三方代码需保留相应许可和归属信息。

## English

Bug reports should include the APK Push version, device models and Android versions, reproduction steps, expected behavior, and the actual error. For downloads, include the package name and selected device profile. For device installation, specify whether the target uses traditional TCP ADB or pairing-based wireless debugging. Remove credentials and private identifiers from logs and screenshots.

Keep each pull request focused. Follow [AGENTS.md](AGENTS.md), use UTF-8 without BOM and LF line endings, write runtime logs in English, and run the existing build scripts for code changes. Explain the resulting behavior and verification; include screenshots for UI changes. Documentation-only changes need link and whitespace checks rather than device tests.

Discuss larger changes in an issue first. Contributions are distributed under GPL-3.0-or-later; retain notices for third-party code.
