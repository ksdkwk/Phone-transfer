# Phone-transfer（换机助手）

全品牌手机换机数据迁移工具 · **Android 客户端工程**

本工程严格按同目录外的三份规范文档实现：

| 文档 | 用途 |
| --- | --- |
| 《Phone-transfer 可行性设计文档》 | 产品范围、数据项可行性矩阵、权限与合规红线、总体架构 |
| 《Phone-transfer 传输协议规范 v1》 | 帧层 / 加密层 / TLV 消息层、会话状态机、错误码、参数常量 |
| 《Phone-transfer PoC 验证方案》 | 通道、加密握手、默认短信应用、iOS 能力四项验证的落地要求 |

---

## 1. 当前版本状态

**V0.1 · 壳工程 + 协议核心**：先按需求把「启动即主界面」的骨架和底部两个功能入口搭好，
同时把文档里对工程质量有硬约束的部分（协议编解码、加密、会话状态机、能力矩阵、权限映射）沉淀为可测试的代码。

| 已完成 | 说明 |
| --- | --- |
| 启动即进入 App | 只有一个 Activity，无闪屏页、无引导页、无登录页，启动期不申请任何权限 |
| 主界面结构 | 上方为大面积功能区（当前空白，功能待定），下方为底部功能选项：**换机**、**我的** |
| 协议核心 | 20B 帧头编解码、TLV 读写（未知 tag 跳过）、CHUNK 固定头、消息类型 / 错误码 / 参数常量 |
| 加密核心 | ECDH P-256、HKDF-SHA256 派生 k_c2s / k_s2c / SAS、AES-256-GCM（Nonce 由 msgSeq 派生、AAD 绑定帧头）、转录哈希 |
| 会话状态机 | 协议 §14 全部状态与合法迁移校验 |
| 能力与权限 | 能力地图数据源（可行性文档 §5）、数据项 → 权限映射（按需申请、L4 单独同意） |
| 单元测试 | 帧 / TLV / CHUNK / 加密 / 状态机的 JVM 测试 |
| **文件备份（SoftAP + TCP）** | 不依赖 Wi-Fi Direct P2P 硬件：旧机开热点、新机接入后经端到端加密 TCP 传输所选文件，逐块 SHA-256 + 回读校验落盘（真实链路，非演练） |
| 真实协议会话 | PtSession 跑通完整握手（HELLO → ECDH → SAS）与流式分块传输，发送端流式读盘、接收端流式落盘 |

| 尚未实现（按文档排期属于后续版本） | 文档依据 |
| --- | --- |
| 换机主流程中 Wi-Fi Direct 以外的通道仍为本地演练（Wi-Fi Direct 与文件备份已接入真实链路） | 可行性文档 §9.1 V1.0 |
| 通道层其余形态（局域网同网段直连）与换机主流程数据适配 | 协议 §3 L1、PoC P1 |
| 数据适配器（联系人 / 媒体 / 日历 / 通话记录的实际读写） | 可行性文档 §6.2 |
| 短信 / 彩信与「临时默认短信应用」接管 | 可行性文档 §9.1 V1.1、PoC P3 |
| 前台服务、断点续传持久化、迁移报告导出 | 协议 §12.4、FR-18 |

> 因此本工程现在**不会**索取联系人、媒体等任何权限——这既是文档要求（未勾选的数据项不得申请权限），
> 也是当前代码的真实行为。

---

## 2. 界面结构（对应需求）

```
┌──────────────────────────────┐
│                              │
│  ┌──────────┐ ┌──────────┐   │
│  │ 同品牌换机 │ │ 跨品牌换机 │   │   ← 点击进入换机主流程
│                              │
├──────────────────────────────┤
│        换机   │   我的        │   ← 底部功能选项，仅两个入口
└──────────────────────────────┘
```

- 底部两个入口由 `BottomNavigationView` + `res/menu/menu_bottom_nav.xml` 提供。
- 功能区左右两半分别进入「同品牌换机」与「跨 Android 品牌换机」，主流程 5 步：
  角色选择 → 配对（6 位配对码）→ SAS 人工比对 → 数据项选择（L4 单独同意）→ 传输与校验 → 迁移报告。
- **当前为演练模式**：通道层（Wi-Fi Direct / 热点 / 局域网）与数据适配器尚未接入，
  功能区底部与进度页、报告页都有明确提示，不做「已真实传输」的虚假表达；
  配对码与 SAS 走的是 `core/crypto` 的真实 P-256 ECDH + HKDF 实现，「对端」由本机临时密钥对扮演。
- 「我的」页提供本机信息与五个入口：能力地图、迁移报告（空态）、传输设置、权限与隐私、关于。


---

## 2.5 文件备份（SoftAP + TCP · 真实链路）

「换机」首页下方的**文件备份**卡片是本工程第一个**不依赖 Wi-Fi Direct P2P 硬件**的真实传输功能：
任何能开热点的 Android 手机都能互传（包括那些 P2P 被厂商阉割的机型），兼容面更宽。

### 角色与通道

| 角色 | 手机 | 通道职责 | 协议职责 |
| --- | --- | --- | --- |
| **发送端** | 旧机 | 开热点（自动 `startLocalOnlyHotspot`，或手动用系统热点）+ TCP 服务端 | 协议 **SENDER**：流式读所选文件、逐块加密发送 |
| **接收端** | 新机 | 加入旧机热点 + TCP 客户端（自动取默认网关，或手输旧机 IP） | 协议 **RECEIVER**：逐块校验、流式落盘到所选备份目录 |

> 为什么发送端反而做「热点 + 服务端」：热点天然的 AP 侧地址固定（默认网关），
> 接入方只需连到网关即可，免去在客户端侧做服务发现；协议层角色与 TCP 主从无关，因此是成立的。

### 操作步骤（两台真机）

**旧机（要备份的那台）**
1. 打开 换机助手 → 文件备份 → **旧手机（发送端）**。
2. 点「选择要备份的文件」，可多选照片 / 视频 / 音频 / 文档 / APK（走 SAF，不申请整盘权限）。
3. 热点方式默认「自动热点」；不支持或失败时改选「手动热点」（自己在系统设置里开好热点）。
4. 点「开始备份」，授权「附近的设备」/「本地网络」/ 通知权限。
5. 屏幕显示热点名、密码与 **6 位配对码**（手动热点时只显示配对码）。

**新机（要存备份的那台）**
1. 在系统 Wi-Fi 设置里加入旧机屏幕上的热点。
2. 打开 换机助手 → 文件备份 → **新手机（接收端）**。
3. 点「选择备份存放目录」选一个文件夹（SAF，只写这个目录）。
4. 输入旧机屏幕上的 6 位配对码；一般**不用填旧机 IP**（自动取默认网关），
   只有在自动发现失败时才照旧机屏幕上显示的地址填。
5. 点「开始备份」。

**两台手机**
- 握手完成后两台手机会各显示一个 **6 位安全码（SAS）**，人工核对一致才继续（防中间人）；
- 之后开始传输，进度条按已传字节 / 总字节与已传文件数实时刷新；
- 全部文件传输后会在新机上**逐文件回读校验**（长度 + SHA-256），通过才算成功；
- 新机所选目录里会多一个 `PhoneTransfer-<时间戳>/` 子目录，含所有备份文件与
  `backup-report.json`（文件名、字节数、SHA-256、校验位、落盘 Uri）。

### 安全与边界（诚实告知）

- **端到端加密**：ECDH P-256 协商 + HKDF-SHA256 + AES-256-GCM，帧序号派生 Nonce、帧头做 AAD，抓包只见密文。
- **完整性**：每块 SHA-256（`Chunk.sha256Matches`）+ 每文件整体 SHA-256 + 接收端**回读再校验**，三级校验。
- **传输中改源文件会被发现**：发送端流式读完会重算并与清单摘要比对，不一致即报错（`ITEM_SHA_MISMATCH`）。
- **不落半截文件**：失败 / 取消时删除未完成文件；重名自动追加 ` (n)` 后缀，绝不覆盖已有备份。
- **能力边界**：本功能备份的是**你主动选的文件**；它不读取其它应用的私有数据，
  也做不了整机克隆——那是换机主流程 + 各数据适配器的范围（见「尚未实现」表）。
- **Android 16/17**：本工程 targetSdk 37，局域网 TCP 需要运行时「本地网络」权限
  （`android.permission.ACCESS_LOCAL_NETWORK`，代码用字面量申请，未强依赖编译期常量）。

### 常见问题

| 现象 | 处理 |
| --- | --- |
| 自动热点创建失败 | 改用「手动热点」：旧机在 设置 → 个人热点 里开热点后回到本页再开始 |
| 新机一直「连接中」 | 确认已加入旧机热点；仍不行就在「旧机 IP」栏填旧机屏幕显示的地址 |
| 提示热点被系统关闭 | 省电策略或双热点冲突，重新点开始即可 |
| 大文件中途断 | 目前的恢复粒度是「整文件」，重传该文件即可（断点续传在排期表内） |
---

## 3. 目录结构

```
Phone-transfer/
├── build.gradle.kts              根构建脚本（插件声明）
├── settings.gradle.kts           仓库与模块声明
├── gradle.properties             AndroidX、compileSdk 抑制提示等
├── gradle/libs.versions.toml     工具链版本集中管理（升级只改这里）
├── gradle/wrapper/               Gradle Wrapper 配置
├── local.properties              本机 Android SDK 路径（D:\Android\Android SDK）
├── docs/规范映射与实施说明.md      规范条款 → 代码落点的映射与扩展指引
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/phonetransfer/app/
│       │   │   ├── PhoneTransferApp.kt
│       │   │   ├── ui/                     界面层（Activity / Fragment / 文案映射）
│       │   │   ├── softap/                 SoftAP + TCP 备份：前台服务、SAF 落盘、会话状态
│       │   │   ├── p2p/                    Wi-Fi Direct 通道（换机主流程真实链路）
│       │   │   └── core/
│       │   │       ├── protocol/           协议规范 §4~§9：帧、TLV、消息、错误码、数据项
│       │   │       ├── crypto/             协议规范 §10：ECDH / HKDF / AES-GCM / SAS
│       │   │       ├── session/            协议规范 §14：会话状态机
│       │   │       ├── transport/          协议规范 §9~§13：PtSession 真实会话（流式收发）
│       │   │       ├── backup/             备份文件名清洗与去重
│       │   │       ├── capability/         可行性文档 §5：能力矩阵
│       │   │       ├── permission/         可行性文档 §6.7 / §7.3：权限映射
│       │   │       └── settings/           冲突策略等本地策略开关
│       │   └── res/                        布局、文案（中/英）、主题、图标
│       └── test/java/com/phonetransfer/app/core/    JVM 单元测试
└── README.md
```

**分层原则**：`core/**` 不引用 Android 资源与 Context（`core/permission` 除外，它必须用
`android.Manifest` 常量），界面文案统一由 `ui/common/Labels.kt` 映射，因此协议与能力矩阵可直接做 JVM 单元测试。

---

## 4. 构建与运行

### 4.1 环境

| 项 | 本机现状 / 建议 |
| --- | --- |
| Android SDK | `D:\Android\Android SDK`（已写入 `local.properties`） |
| SDK Platform | Android SDK Platform 17（**API 37.0**） |
| Build-Tools | 36.0.0 |
| JDK | 21（AGP 要求 17+） |
| IDE | 推荐 Android Studio；IntelliJ IDEA 需 Ultimate 版才有 Android 支持 |

### 4.2 工具链版本（`gradle/libs.versions.toml`）

| 组件 | 本工程取值 |
| --- | --- |
| Android Gradle Plugin | 9.2.0 |
| Gradle | 9.4.1（AGP 9.2.0 要求的下限，见其 VersionCheckPlugin） |
| Kotlin | 由 AGP 9.2.0 内置提供（传递引入 KGP 2.2.10），工程不再单独应用 Kotlin 插件 |
| compileSdk / targetSdk | 37 |
| minSdk | 26（Android 8.0） |

> 若 Gradle 同步报版本不兼容，只需在 `libs.versions.toml` 里整体替换 `agp` / `kotlin`，
> 并同步 `gradle-wrapper.properties` 里的 `distributionUrl`；`android.suppressUnsupportedCompileSdk=37`
> 用于消除 compileSdk 高于 AGP 已测上限时的提示。

### 4.2.1 本机环境已修复的问题（非代码问题，换机复现时参考）

| 现象 | 根因 | 处理 |
| --- | --- | --- |
| 同步报 Could not resolve gradle:gradle:9.x / PKIX path building failed | Gradle Kotlin DSL 内置的 SourceDistributionResolver 固定访问 services.gradle.org（备用 github.com）；该域名证书链的信任锚不在 JDK 自带 cacerts 中，而 Windows 证书库可以验证（浏览器能打开该站点即证明），Java 又不会自动补全中间证书 | 在 gradle.properties 加入 systemProp.javax.net.ssl.trustStoreType=WINDOWS-ROOT |
| 同步报 Minimum supported Gradle version is 9.4.1 | AGP 9.2.0 的 Gradle 版本检查 | 把 gradle-wrapper.properties 的 Gradle 升到 9.4.1 |
| 同步报 The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0 | AGP 9 内置了 Kotlin 支持，不允许再叠加 KGP 插件 | 从 app 与根 build.gradle.kts 中移除该插件声明，Kotlin 编译由 AGP 提供 |
| 分发 / 源码包下载不稳定 | 本机到 services.gradle.org 的连接不稳定 | distributionUrl 使用国内镜像（腾讯云、华为云均已实测可下载） |
### 4.3 构建步骤

1. 用 Android Studio 打开 **`Phone-transfer`** 目录（不要打开上层 `Phone transfer` 目录）。
2. 首次同步会联网下载 Gradle 发行包与 AGP/AndroidX 依赖。
3. 本工程未附带二进制文件 `gradle/wrapper/gradle-wrapper.jar`。
   若 IDE 提示缺少 wrapper jar，任选其一：
   - 让 IDE 使用本机 Gradle 分发；
   - 或在本目录执行 `gradle wrapper` 生成（需本机已安装 Gradle 9.x）。
4. 命令行构建：

```bash
gradle :app:assembleDebug        # 产出 app/build/outputs/apk/debug/app-debug.apk
gradle :app:testDebugUnitTest    # 运行协议层与加密层单元测试
```

5. 安装到设备：`adb install -r app/build/outputs/apk/debug/app-debug.apk`

安装后**点开图标即进入主界面**，没有闪屏、没有引导、不索取权限。

---

## 5. 合规红线（已写入代码约束）

1. **权限最小必要**：权限计划由 `PermissionCatalog` 按数据项生成，未勾选的数据项不申请权限；
   L4（短信 / 彩信 / 通话记录 / 应用数据）标记为需要**单独同意**。
2. **不申请所有文件访问**：文档类走 SAF 用户授权目录（`requiresUserSelectedDirectory = true`），
   不使用 `MANAGE_EXTERNAL_STORAGE`（契合待决策项 D3 的建议）。
3. **应用清单**：只用 `<queries>` 查询启动器可见应用，不使用 `QUERY_ALL_PACKAGES`。
4. **数据不出端**：`android:usesCleartextTraffic="false"`、`android:allowBackup="false"`，
   密钥只驻留内存、会话结束即销毁（协议 §10）。
5. **不逆向私有协议、不使用 Root / 越狱 / 系统签名**：可行性文档 §8.2 R7 的红线，本工程不引入任何此类依赖。
6. **备份只落在用户授权目录、不覆盖已有文件**：文件备份只写 SAF 选择的目录；
   对端提供的文件名经 `BackupNames.safe` 清洗，重名自动追加序号，绝不静默覆盖。

---

## 6. 下一步建议

1. 把「文件备份」里跑通的 PtSession 会话复用到换机主流程（目前主流程 Wi-Fi Direct 已通，其余通道仍为演练）；
   两端的清单 / 选片 / 校验语义一致，主要差在数据适配器。
2. 数据适配器：联系人 / 媒体 / 日历 / 通话记录按 §6.2 落地，复用 SAF 与按需申请权限的既有约束。
3. 断点续传：当前恢复粒度是「整文件」，可在 ITEM_BEGIN 里协商已收字节偏移实现续传（协议 §12.4）。
4. 迁移报告导出：把 backup-report.json 汇成可分享的 HTML / PDF（FR-18）。