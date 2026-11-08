# APK Push：局域网安装实现设计

## 范围与组件

工程包含一个 Kotlin Android App，手机最低 API 29，compileSdk 与 targetSdk 均为 34。手机直接连接用户选择的设备 adbd，设备无需接收端。

| 组件 | 当前职责 |
| --- | --- |
| MainActivity | 底部导航、推送界面、设备管理、文件选择、分享入口、操作确认 |
| AppSheet / AppPalette | 共用底部面板、信息分组、按钮与色彩 |
| PushController | 单任务执行、工作线程、状态通知、取消和页面生命周期 |
| IdentityStore | Android Keystore 中的 RSA-2048 身份与 ADB 签名 |
| AdbSession | TCP、AUTH、ADB 流、sync v1、shell v2 与旧版 shell |
| ApkImporter / DirectApkSource | 读取授权 URI、只读句柄生命周期、兼容缓存、包信息与 ABI 解析 |
| DeviceStore | 设备列表、选中设备、持久化任务日志 |
| Installer / TvRemote | 兼容性检查、安装、版本查询、结果恢复、清理与打开应用 |
| DeviceDiscovery / LanAddresses / AdbProbe | 当前局域网范围、服务广播、ADB 握手识别、超时与取消 |
| PlayPage / PlayController | 搜索、下载状态、本地库选择、导出与前台生命周期 |
| PlayRepository / VerifiedDownload / DownloadLibrary | 匿名 Play 会话、设备配置、HTTPS 校验下载、持久安装包 |

应用与目标设备均由用户选择。包名、名称、版本、SDK 与 ABI 来自当前 APK；本工具不绑定特定设备品牌或被安装应用。

ADB 使用 Kotlin 标准库和 Android / Java 公开 API，实现范围为传统 TCP 单连接、单流顺序执行，没有 TLS、配对码或隐藏 API 依赖。Google Play 功能使用 gplayapi 4.0.0、OkHttp 和 kotlinx.coroutines。

## 身份与持久化

- IdentityStore 在 Android Keystore 中生成 RSA-2048 密钥与证书，私钥不导出。
- ADB AUTH 载荷为已经计算好的 SHA-1 摘要，使用 NONEwithRSA 对包含 SHA-1 DigestInfo 的载荷签名。
- 公钥按 ADB 的 524 字节小端 RSA 结构编码，授权名称为 APKPush@android。
- 应用关闭与重开复用同一别名。卸载或清除应用数据后需要重新授权。
- Manifest 禁止备份，数据提取规则同时排除云备份与设备迁移。
- 设备与任务使用应用私有 SharedPreferences。任务写入使用同步 commit()；记录持久化成功后才开始对应远端副作用。
- 日志不包含密钥、URI 或 APK 内容。

## 文件导入

系统 Storage Access Framework 和分享入口提供 content:// URI，应用不申请全盘存储权限。导入在工作线程完成，独立 APK 的完整常规文件使用只读文件描述符推送；允许路径访问的来源通过 `/proc/self/fd` 解析包信息，下载库使用已保存的包信息和哈希。描述符在选用期间保持打开，取消选择或更换文件时关闭。管道、偏移文件和不支持直接解析的来源检查空间后复制到私有缓存。

单 APK 上限为 2 GiB，缓存保留至少 16 MiB 空闲空间。已知长度与实际复制长度必须一致。ZIP 必须存在 AndroidManifest.xml；系统 PackageManager 负责读取应用元数据。需要拆分包的元数据会提前拒绝，包管理器最终决定独立 APK 是否有效。ABI 来自 lib/<abi>/*.so。

直接读取文件在上传前后检查文件身份、长度与修改时间；下载库中的独立 APK 在 ADB sync 传输时同步计算 SHA-256，校验通过后才完成上传，源文件变化或校验失败时不提交安装。选择新文件后关闭旧句柄或清除旧副本；进程重新启动时清除前次进程留下的导入缓存。设备和中断任务记录保留在私有持久存储。

## TCP ADB 与超时

设备选择面板从可用的 Wi-Fi 与以太网提取扫描地址，VPN 与移动数据链路不提供扫描网段。探测优先绑定对应局域网；绑定返回 EPERM 或 EACCES 时使用系统默认路由，遵循系统与 VPN 的路由策略。至少监听服务广播 15 秒，探测仍在进行时最长 45 秒。传统 ADB 服务通过 `_adb._tcp` 解析实际端口；`_adb-tls-connect._tcp` 与 `_adb-tls-pairing._tcp` 结果标记为需配对，用户无法将其作为传统连接添加。Android 13 及以上按网络启动服务发现。

主动探测先检查本机所在 `/24`，再扩展到实际子网内最多 `/22` 范围；较小子网使用实际范围。默认端口为 5555，最多 32 个探测线程。已保存地址的实际端口与广播公布的传统 ADB 服务使用另外 4 个线程。本机 `/24`、保存与广播目标在超时后补扫一次，其他相邻地址探测一次；连接和单次读取首轮超时为 800 毫秒，补扫为 1600 毫秒。服务解析单项等待 2500 毫秒，失败或超时后重试一次，迟到的回调不覆盖当前解析；Android 14 及以上停止超时解析。扫描总时限与取消会关闭活动 socket。

探测只发送 CNXN，验证 AUTH、CNXN 或 STLS 响应，不签名、不发送公钥、不打开远端服务。服务广播地址须匹配当前局域网的直连路由，普通开放端口不直接视为 ADB。关闭面板后停止广播发现、线程与 socket，已排队的回调不再更新界面。重新搜索创建独立会话。

连接地址使用 IPv4 / IPv6 字面值，端口范围为 1–65535。用户从发现列表选择目标，或手动填写；发现结果不会自动连接或安装。

| 阶段 | 限制 |
| --- | --- |
| TCP 建连 | 8 秒 |
| 连接及授权总时限 | 70 秒；读取空闲上限 60 秒 |
| 上传 | 15 秒无进展超时，无固定总时限 |
| 属性、版本查询、清理、启动 | 每条命令 15 秒 |
| 包管理器安装 | 120 秒 |

socket 读取超时与定时器共同控制等待。定时器关闭超时 socket，取消操作也关闭 socket，阻断读写等待。协议校验命令魔数、长度、旧版校验和与流 ID；最大单包为 1 MiB，接收缓冲与命令输出上限为 2 MiB。

服务端广播 shell_v2 时使用 shell,v2,raw:，解析 stdout、stderr 和退出帧。旧版 shell 使用每次随机生成的输出尾标记与退出码，尾标记缺失时结果不可信。TLS 握手请求返回明确的当前范围说明。

## 安装闭环

1. 读取目标型号、SDK、ABI、序列号、当前用户和可用空间。基础兼容性检查只拒绝明确不匹配的任务。
2. 为任务生成 UUID，使用 /data/local/tmp/tvapk-<uuid>.apk；远端文件名不包含用户输入。
3. 先持久化“待上传 / 清理”记录，打开 sync:，发送 SEND、最大 64 KiB 的 DATA 块与 DONE。
4. ADB 数据写入收到应答后累计传输字节；sync 的最终 OKAY 才表示上传成功。
5. 持久化“安装已提交 / 待核对”记录，执行 pm install --user <当前用户> -r <路径>。
6. 同时要求命令退出码为 0 且包管理器有独立的 Success 行。
7. 使用 pm list packages --user <id> <包名> 精确匹配包名，并从 dumpsys package 读取长版本号；与所选 APK 一致时显示成功。
8. 明确失败或成功的任务删除远端文件；清理失败时保留清理记录，下次连接同一设备重试。

Play 拆分包使用同一任务 UUID，主包沿用上述路径，其余路径为 `tvapk-<uuid>-<序号>.apk`。文件数量为 1–128。全部上传后创建安装会话，记录会话 ID，逐个执行 `pm install-write`，最后持久化“已提交”并执行 `pm install-commit`。提交前失败会放弃会话并清理文件；提交后失联保留待核对记录。设备包管理器校验拆分包的包名、版本、签名及依赖完整性。

任务记录的 `fileCount` 和 `sessionId` 支持清理整套文件及未提交会话；缺省 `fileCount=1`、`sessionId=null` 的已有记录仍有效。

命令中的包名与组件名经过格式限制，远端路径由 UUID 构成。覆盖安装不自动卸载原包，不添加降级或默认授权标志。

## 中断、核对与重试

- 上传中取消：停止传输，不提交安装；遗留路径记录用于下次清理。
- 安装已提交后取消、断线、超时或版本查询失败：保留记录并显示“结果待确认”，不直接宣告安装失败。
- 存在待确认记录时，同一设备上的新安装不可提交。
- 重连先查询原任务包名、用户和版本。即使预期版本已经存在，也不能证明中断任务已经结束，因为该版本可能在提交前就存在。
- 用户确认设备任务结束后，应用清理临时文件与记录，再允许用户选择是否重新安装。
- 记录有非空序列号时，重连设备的序列号必须相同；不符时保留任务且不清理远端文件。
- 无法读取设备序列号的设备依靠用户保存和选择的地址识别，用户需确认该地址仍属于原设备。

ADB 安装任务在进程内由 Application 持有。MainActivity 的底部导航切换两个页面视图，切换标签与旋转屏幕保留当前任务。应用进入后台时取消 ADB 等待。进程被系统终止后，安装持久化记录支持下次手动核对。

Google Play 下载由 `PlayDownloadService` 的 `dataSync` 前台服务承载，返回 `START_NOT_STICKY`。队列最多运行 3 个下载工作线程，按加入顺序分配空闲名额；暂停和取消中的线程实际退出后才释放名额。暂停、失败的任务保留文件并等待用户点击“继续”或“重试”，手动恢复加入队尾。断网时等待任务进入待重试状态，网络恢复和应用启动不自动重试。各任务的标识、包名、机型快照、阶段和进度作为有序列表通过 `AtomicFile` 保存，读取兼容单任务记录；安装包半成品保存在对应 `.partial-<id>` 目录。取消只清理对应任务的未完成文件。服务通知按任务分组，每个动作绑定任务标识，旧任务动作不会影响其他任务。实际传输期间持有带超时的局部唤醒锁，全部任务停止后释放并停止服务。搜索和下载库操作使用独立工作线程。

进程启动时，未完成的运行或排队任务显示中断状态，暂停中的任务显示已暂停。保留字节数从当前半成品文件读取；持久记录中的 `WAITING` 状态显示为下载中断，等待手动重试。

下载重试重新获取 Play 文件列表，只有版本、机型指纹、文件名、长度与哈希一致时复用半成品。HTTP 206 必须声明与本地偏移及完整长度一致的范围；HTTP 200 从头覆盖该文件。完整文件通过长度、哈希及 APK 身份校验后入库。入库与任务记录清理之间退出时，下次恢复复用同一下载标识的完整文件。

底部选中标签、搜索输入、应用页分类、导出目标与滚动位置通过实例状态保存。下载项按直接读取或解包缓存路径准备，完整性分别在传输或解包阶段校验；选中后切换推送标签。推送期间禁用下载项的选用、导出和删除操作。

底部面板注册所属 Activity 的生命周期回调，在 Activity 停止时关闭，在面板关闭时注销回调。调用方的关闭监听负责停止设备发现等关联操作。

## 错误与启动

签名冲突、版本降级、CPU 架构、低 SDK、空间不足、拆分包缺失和用户策略限制分别显示中文说明，并保留原始安装错误码。普通连接失败和超时提示检查 IP、端口、网络与设备授权。

“在设备上打开”先复查任务版本；电视优先解析 LEANBACK_LAUNCHER，其他设备优先解析普通 LAUNCHER，再尝试另一入口。没有启动入口时说明应用已安装但无法由此启动。

## 后续功能边界

配对码无线调试需要独立维护配对与连接端口，并实现 TLS 身份与配对协议。当前发现 _adb._tcp、_adb-tls-connect._tcp 与 _adb-tls-pairing._tcp；TLS 设备显示连接限制，配对协议与 TLS 安装尚未实现。

提升 targetSdk 或加入新网络能力时，需要核对目标 Android 版本的本地网络权限及前台服务时限。当前 targetSdk 为 34，Play 下载声明前台服务、dataSync、通知与唤醒锁权限；Android 13 及以上在首次下载时请求通知权限，拒绝通知仍可运行前台下载。Play 下载范围与存储规则见 README 的 Google Play 下载说明。

## 参考

- [ADB 官方文档](https://developer.android.com/tools/adb)
- [ADB 协议](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/OVERVIEW.TXT)
- [ADB sync 协议](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/SYNC.TXT)
- [ADB 无线调试设计](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/dev/adb_wifi.md)
- [libadb-android](https://github.com/MuntashirAkon/libadb-android)
