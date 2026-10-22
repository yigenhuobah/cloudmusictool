# 云音乐工具箱 (CloudMusicTool)

网易云音乐 Android 客户端的 LSPosed 体验优化模块：去广告、精简界面、收敛弹窗、隐私加固。

> 当前版本 **2.1.0**（2026-10-22，versionCode 7）。已在 25102RKBEC 真机验证
> （Android 17 / LSPosed 2.2.1 / 网易云音乐 9.6.08）。
> 2.0.0 把挂载点坐标抽离为 Profile；2.0.1 为实验页补充中文说明；
> 2.1.0 新增「我的」页本地自定义背景（UI-16，默认关）。
> 与网易官方无任何关联，仅供学习研究和个人设备使用。

**仓库**：<https://github.com/yigenhuobah/cloudmusictool> ｜ **下载**：[Releases](https://github.com/yigenhuobah/cloudmusictool/releases)

## 兼容性

| 项 | 值 |
|---|---|
| 目标应用 | `com.netease.cloudmusic` **9.6.08** (versionCode 9006008) |
| 模块包名 | `io.github.yigenhuobah.cloudmusictool` |
| Xposed API | libxposed API 102（`minApiVersion=102` / `targetApiVersion=102`） |
| 系统要求 | Android 10+（minSdk 29） |
| 框架 | 支持 API 102 的 LSPosed（实测环境 LSPosed v2.2.1 + KernelSU/Zygisk） |
| 架构 | 纯 Java 17 + XML + Material 3，无 Kotlin/Compose |

## 安装

1. 安装 `cloudmusictool-<version>.apk`。
2. 在 LSPosed 管理器中启用「云音乐工具箱」，作用域勾选「网易云音乐」。
3. 打开模块 App，确认状态卡显示「已激活」（首次可能需要启动一次网易云）。
4. 按需开启功能，修改后重启网易云音乐生效。

## 功能

### 广告（默认开启）

| ID | 功能 | 说明 |
|---|---|---|
| AD-01 | 开屏广告跳过 | 判定/数据源/落地页三层拦截 + 摇一摇 + 免广实验分支 |
| AD-02 | 运营弹窗屏蔽 | 启动时的运营/营销弹窗，保留一键登录 |
| AD-03 | 更新弹窗屏蔽 | 版本检查、强更弹窗、应用内下载入口 |
| AD-04 | 首页信息流广告 | `Banner.isAdBanner` 闸门 |
| AD-05 | 评论区广告 | 评论条目广告字段、评论推广条、带货标签 |
| AD-06 | 播放页广告 | 黑胶广告层、送礼/活动贴纸、曝光兜底 |
| AD-07 | 音频贴片/播客广告 | 音频贴片、播客视频广告 |
| AD-08 | 广告上报/外跳 | 广告统计上报、静默拉起外部 App、曝光预请求 |
| AD-09 | 搜索广告净化 | 搜索起始页广告位、搜索结果大图/中图广告（已接线，5 个挂载点） |
| AD-10 | 广告预加载裁剪 | 冷启动广告入口、播客激励广告预加载（已接线，冷启动命中 `APreloadRecommendViewTask.init`） |

### 界面（默认开启，UI-08 实验性）

| ID | 功能 | 说明 |
|---|---|---|
| UI-01 | 首页模块净化 | 按 blockCode 隐藏发现页卡片；同步过滤首页 MMKV 缓存 |
| UI-02 | 顶部频道管理 | 按保留清单真实删除顶部 tab 与页面；至少保留 1 个，留空显示全部 |
| UI-03 | 底部导航精简 | 按保留清单过滤真实四项底栏（首页/搜索/关注/我的） |
| UI-04 | 侧栏抽屉精简 | **暂不支持**：9.6.08 未找到可靠的抽屉数据挂载点 |
| UI-05 | 跳过启动延迟 | 去掉红屏人为等待 |
| UI-06 | 引导与气泡屏蔽 | 推送/评分/VIP/直播/底栏气泡、青少年弹窗 |
| UI-07 | 禁止自动播放 | 冷启动、信息流、Banner、蓝牙、4G MLog 自动播放 |
| UI-08 | 强制深色模式 | 实验性，默认关闭 |
| UI-09 | 关闭头像挂件 | 个人主页/评论区头像装饰 |
| UI-10 | 播放页提示治理 | 评论气泡、推荐歌曲提示、卡顿/网络错误弹窗、激励广告浮层（已接线，5 个挂载点，未自然触发） |
| UI-11 | 搜索热词轮播 | 关闭搜索框运营热词（已接线，命中 `SearchGuide.isValid`） |
| UI-12 | 播客与 AI DJ 引导 | FM/听书/AI DJ 引导（已接线，4 个挂载点，未自然触发） |
| UI-13 | 小组件与推送引导 | 添加小组件、推送引导弹窗（已接线，5 个挂载点，未自然触发） |
| UI-14 | 歌词页推广 | 歌词底部 Hint、选项气泡、歌词视频弹窗（已接线，3 个挂载点，未自然触发） |
| UI-15 | 云村与直播推广 | 直播推广位、云村发布/搜索气泡（默认关，已接线，未验证） |
| UI-16 | 我的页自定义背景 | 本地图片全屏背景（默认关）；启用后「我的」页走原生 V4，头部完整全屏，内容卡片保持主题深色；仅本机可见，不改变会员状态 |

### 隐私（默认关闭）

| ID | 功能 | 说明 |
|---|---|---|
| PV-01 | OAID 上报拦截 | OAID 上传与启动任务 |
| PV-02 | 日志写盘任务屏蔽 | `NeedLogToFileTask` / `NBLogTask` |
| PV-03 | 启动任务黑名单 | 按 taskName/类简名跳过非必要启动任务 |
| PV-04 | APM 上报抑制 | 抑制 `logApm` / `forceApmUpload`（不拦初始化） |
| PV-05 | 统计数据上报抑制 | 记录统计 key，并关闭 LogMonitorConfigManager 监控开关 |
| PV-06 | 剪贴板读取拦截 | 全局 ClipboardUtils 入口返回 null（默认关，已接线；开启后注册 `utils.h2#d`，关闭后恢复） |
| PV-07 | 启动任务编排 | 白名单任务优先级/线程/降级编排（默认关，已接线，未验证） |

### 实验（ABTest，默认只读）

- 第 4 个 Tab「实验」：展示目标进程实际命中的实验（key / 分组 / 来源 / 次数）。
- 22 个 curated 体验 key 可单 key 强开分组（`c/t/t1/t2/t3/t4`）；已探测但未收录的 key 可手动「提升为强开」；黑名单 key 显示「禁止强开」。
- 强开默认关闭，配置写入 remote prefs，重启网易云生效；提供「一键恢复默认」。
- 只改分组，不改 `status`/`clientConfig`（合成对象除外）；启动 AB 只读；不碰 VIP/付费/版权/登录/Cookie/风控。

### 1.1.0 新功能验证状态

1.1.0 已注册 **26 个新挂载点**：AD-09 5、AD-10 3、UI-10 5、UI-11 1、UI-12 4、UI-13 5、UI-14 3。

真机已验证命中：AD-10（冷启动 `APreloadRecommendViewTask.init`）、UI-11（`SearchGuide.isValid`）、
UI-02（频道 6→1）、PV-06（开启后 `utils.h2#d` 注册，关闭后恢复）。
其余新功能为“已注册、未自然触发”，未做完整的五步验证。

## 2.0.0 架构版（开发中）

- 挂载点坐标收口到 `app/src/main/java/com/cloudmusictool/app/profile/`：
  `MountSpec` 描述坐标，`TargetProfile` 按版本聚合，`ProfileRegistry` 选择版本，
  `MountResolver` 精确类名优先、DexKit 锚点兜底。
- `ProfileUi` 覆盖 UI-01~UI-15（UI-04 blocked），`ProfileAdPv` 覆盖 AD/PV/ABTEST；
  9.6.08 共 133 个挂载点（UI 63 + AD/PV/ABTEST 70），每个 Feature 至少一个 critical。
- `ProfileExportTest` 导出 `app/build/profile9608.json`；`tools/check-mounts.sh` 直接读取该文件，
  断言 versionCode、类 descriptor、方法参数/返回类型，输出 OK/GONE/ERROR。
- 行为与 1.2.0 等价：只搬坐标，不改 hook 体；仍只适配 9.6.08。

## 已知限制
- **只适配 9.6.08**。其他版本默认跳过；高级配置 `allow_unsupported_version` 可尝试 best-effort，但不保证。
- 广告由服务端下发，模块只能阻止展示/上报，不能阻止接口请求。
- 宿主存在 Tinker 风格热补丁，补丁可能替换被 hook 的实现。
- UI-02 顶部频道：已改为在 `dr.h.T0` 数据源上真实删除，9.6.08 真机验证「只保留推荐 → tab 栏 1 项、
  左右滑不进入隐藏频道、旋转/深色切换不崩」。留空配置回退全量，至少保留 1 个。
- UI-04 抽屉精简：无可靠挂载点，**暂不支持**（配置入口已移除）。
- 1.1.0 新增功能中，只有 AD-10/UI-11/UI-02/PV-06 有真机命中或开关恢复证据；其余为“已注册、未自然触发”，
  发布说明不得写成“全部生效”。
- DexKit 兜底已修复：release 使用 legacy JNI 打包，按「模块 native 目录 → loadLibrary → 从 APK 解压」加载；
  9.6.08 真机强制自检通过（`DexKit selftest located`，182ms）。旧版本/改版仍可能定位失败。
- Hook 健康度通过 `StatusProvider` 回写；MIUI 等系统可能拦截目标进程冷启动该 provider，
  此时仅在 logcat 输出，设置页诊断卡显示「暂无目标进程回写」。
- PV-05 会记录统计上报并关闭 `LogMonitorConfigManager` 的监控开关，不是纯只读。
- 不触碰安全/风控/设备 ID，不绕过付费、VIP、DRM、版权区域限制。

## 构建

环境：JDK 17、Android SDK Platform 36、Gradle wrapper 8.13、AGP 8.12.2。

```bash
# 单元测试（含 ProfileExportTest，导出 app/build/profile9608.json）
gradlew.bat :app:testDebugUnitTest

# 挂载点体检（默认 APK/dexsql 见脚本头部；输出 OK/GONE/ERROR）
bash tools/check-mounts.sh

# debug
gradlew.bat :app:assembleDebug

# release（需要根目录 keystore.properties 与 .jks）
gradlew.bat :app:assembleRelease
```

产物：
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/cloudmusictool-<version>.apk`

依赖：
- `compileOnly io.github.libxposed:api:102.0.0` / `annotation:1.0.0`
- `implementation io.github.libxposed:service:102.0.0`
- `implementation org.luckypray:dexkit:2.3.0`（硬编码失败时兜底，已启用 legacy JNI 打包）
- AppCompat / Material / androidx.preference
- 单测：JUnit 4.13.2 + `org.json:json`

## 签名

`keystore.properties` 与 `*.jks` 已在 `.gitignore` 中，不入库。
缺失时 release 会回退 debug 签名（仅用于本地测试，不可用于正式发布）。

## 隐私

模块不联网、不收集、不上传任何账号或使用数据。
设置界面只通过 LSPosed 的 remote preferences 与本机目标进程通信。
统计观测（PV-05）只写 logcat，不落盘、不外传。

## 免责声明

本项目为开源学习项目，所有代码仅供学习研究、逆向工程实践和个人设备体验改善。
严禁用于商业用途、付费破解或绕过版权保护。使用风险由使用者自行承担。
与网易云音乐官方无任何隶属或背书关系。

## 参考

- libxposed API 102 规范（Maven Central `io.github.libxposed:api:102.0.0`）
- DexKit（`org.luckypray:dexkit`）
- UI 结构参考同作者的 QuietBox 模块
