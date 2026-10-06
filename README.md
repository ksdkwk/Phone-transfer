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

| 尚未实现（按文档排期属于后续版本） | 文档依据 |
| --- | --- |
| 换机主流程的真实通道与数据传输（当前为本地演练，UI 与协议状态机已就位） | 可行性文档 §9.1 V1.0 |
| 通道层（Wi-Fi Direct / 热点 / 局域网）与 TCP 会话 | 协议 §3 L1、PoC P1 |
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
│       │   │   └── core/
│       │   │       ├── protocol/           协议规范 §4~§9：帧、TLV、消息、错误码、数据项
│       │   │       ├── crypto/             协议规范 §10：ECDH / HKDF / AES-GCM / SAS
│       │   │       ├── session/            协议规范 §14：会话状态机
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

---

## 6. 下一步建议

1. 先跑通 PoC P1（通道）与 P2（加密握手），把 `core/crypto` 的密钥派生结果与跨端测试向量比对（协议 §18.5）。
2. 通道就绪后接入 `core/session` 状态机与 `core/protocol` 消息层，实现 §9~§13 的完整会话。
3. 「换机」功能区接入 UI 时，请保持「先选数据项、再按需申请权限」的顺序（协议 §11）。
4. 迁移报告与本地日志必须脱敏：只记录行为与错误码，不含任何内容数据（可行性文档 §7.3）。
