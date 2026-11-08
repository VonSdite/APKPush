# 验证说明

## 1.12.1 验证范围

应用功能验证使用 API 31 实体手机和 API 37 手机模拟器；协议和异常路径使用 JVM 模拟服务。结果适用于列出的测试范围，不代表所有品牌设备均兼容。

| 项目 | 结果 |
| --- | --- |
| JVM 测试 | 89 项通过，0 失败、0 错误。覆盖 ADB 协议、安装流程、下载完整性、队列、更新判断及机型过滤。 |
| Debug 构建与静态检查 | 构建通过，Android Lint 为 0 错误、2 项已有警告。 |
| Release 构建与签名 | release 构建通过，Lint 为 0 错误、2 项已有警告；APK v2 签名通过，独立发布证书，`debuggable=false`。 |
| Release 安装检查 | 在全新 API 37 模拟器中安装、启动、切换页面和搜索机型通过；`run-as` 返回 package not debuggable。 |
| 下载机型搜索 | 多关键词、大小写、API 紧凑写法、架构、本机、无匹配提示、清除、固定搜索框和选择恢复通过。 |
| 文件准备 | 公共独立 APK 直接读取、传输流内容、重复选择、关闭句柄、源文件变化和拆分包准备检查通过。 |
| 设备发现 | 传统 ADB 与 TLS 广播分类、已保存自定义端口、超时补扫和取消回调检查通过。 |
| 网络绑定兼容 | 实体手机网络绑定返回 EPERM 时，系统路由路径通过发现检查；实际局域网搜索发现设备。 |
| 下载队列 | 3 项并发、等待补位、单项暂停和取消、失败隔离、手动恢复及任务持久化通过受控检查。 |
| 本机拆分安装 | 会话写入、内容校验、缓存隔离、兼容性检查和失败清理独立复测通过；组合测试曾出现会话清理时序断言失败。 |
| 多选与删除 | 长按进入、逐项选择、全选、取消、确认、失败项保留及未选文件保留通过。 |
| 安装闭环 | 模拟器之间的安装、同签名升级、版本核对、降级拒绝、签名冲突及上传临时文件清理通过。 |

## 复现检查

准备 JDK 和 Android SDK 后，在项目根目录运行：

```sh
sh scripts/build.sh testDebugUnitTest lintDebug packageDebugApk
```

Windows 使用 `scripts/build.ps1`。依赖完整时可以追加 `--offline`。

仪器检查需在专用测试设备上执行：

```sh
sh scripts/build.sh assembleDebug assembleDebugAndroidTest
adb -s <device> install -r tmp/build/app/outputs/apk/debug/app-debug.apk
adb -s <device> install -r tmp/build/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <device> shell am instrument -w \
  -e discovery true -e publicDownloads true \
  dev.tvapkpush.test/dev.tvapkpush.DeviceSmokeRunner
```

`DeviceSmokeRunner` 提供 `navigation`、`recovery`、`queue`、`browsing`、`localInstall`、`batchDelete` 等可选检查。测试参数和样本清理行为见对应 `app/src/androidTest/` 实现。`play` 等在线检查需要能访问 Google 服务；后台断网检查会操作测试设备网络，应在专用环境运行。

## 已知限制与未覆盖项

- 真实手机到真实电视的完整安装及不同厂商策略仍待验收。
- TV 模拟器曾出现授权公钥持久化的 EACCES 错误，免确认重连未通过该环境验收。
- Android 10 真机、IPv6-only 网络、多网卡和不同路由器的完整发现验收未完成。
- 当前版本的机型搜索未进行完整横屏、大字号和 TalkBack 验收。
- Google 服务的连通性、账号与地区限制会影响在线测试；未对服务进行高频或配额测试。
- TLS 配对安装、外部拆分包导入与后台持续推送属于未实现能力。

发布包的构建、签名和验证方式见[发布指南](releasing.md)。
