# 云音乐工具箱 (CloudMusicTool)

网易云音乐 Android 客户端的 LSPosed 体验优化模块：去广告、精简界面、收敛弹窗、隐私加固。

> 当前版本 **2.4.5**（versionCode 15）。适配网易云音乐 **9.6.08 / 9.6.13 / 9.6.15**（versionCode 9006008 / 9006013 / 9006015）。
> 与网易官方无任何关联，仅供学习研究和个人设备使用。

**仓库**：<https://github.com/yigenhuobah/cloudmusictool> ｜ **下载**：[Releases](https://github.com/yigenhuobah/cloudmusictool/releases)

## 兼容性

| 项 | 值 |
|---|---|
| 目标应用 | `com.netease.cloudmusic` **9.6.08 / 9.6.13 / 9.6.15** (versionCode 9006008 / 9006013 / 9006015) |
| 模块包名 | `io.github.yigenhuobah.cloudmusictool` |
| Xposed API | libxposed API 102（`minApiVersion=102` / `targetApiVersion=102`） |
| 系统要求 | Android 10+（minSdk 29） |
| 框架 | 支持 API 102 的 LSPosed |
| 架构 | 纯 Java 17 + XML + Material 3，无 Kotlin/Compose |

## 安装

1. 安装 `cloudmusictool-<version>.apk`。
2. 在 LSPosed 管理器中启用「云音乐工具箱」，作用域勾选「网易云音乐」。
3. 打开模块 App，确认状态卡显示「已激活」（首次可能需要启动一次网易云）。
4. 按需开启功能，修改后重启网易云音乐生效。

## 界面

底栏 4 tab（设置 / 实验 / 诊断 / 关于）+ 二级页：

- **设置**（默认 tab）：四态状态卡（正在连接框架… / 未激活 / 框架已连接 / 已激活）、异常横幅、功能开关、模块级设置（隐藏桌面图标）、页脚；「首页定制」入口在本 tab。
- **首页定制**（二级页，工具栏带返回箭头）：发现页推荐卡片、顶部频道、底部导航的保留清单；「我的」页自定义背景。
- **实验**：只读展示目标进程命中的 ABTest，支持白名单内单 key 强开。
- **诊断**：逐功能显示 `编号 标题 · registered · hit N · skip 原因`，无数据 `—`；页首刷新时间，页尾刷新与复制。
- **关于**：模块版本、目标应用、源码、四模块互链、免责声明、MIT 许可证。

tab 切换用 hide/show 保留各自滚动位置；非设置 tab 按返回先回设置 tab，再按才退出。状态卡只有四种状态，版本不匹配与多功能失败通过红色异常横幅提示，不新增第五态。

## 功能

默认值：AD/UI 默认开，PV/EN/EX 默认关；blocked 项一律默认关。所有开关均可在对应分组单独关闭。

### AD 广告净化（默认开启，9 项）

| ID | 功能 | 说明 |
|---|---|---|
| AD-01 | 开屏广告跳过 | 判定、数据源、落地页三层拦截 + 摇一摇 + 免广实验分支 |
| AD-02 | 运营弹窗屏蔽 | 启动时的运营/营销弹窗，保留一键登录 |
| AD-03 | 广告上报与外跳拦截 | 广告统计上报、静默拉起外部 App、曝光预请求 |
| AD-04 | 搜索广告净化 | 搜索起始页广告位、搜索结果大图/中图广告 |
| AD-05 | 广告预加载裁剪 | 冷启动广告入口、播客激励广告预加载 |
| AD-06 | 首页信息流广告 | `Banner.isAdBanner` 闸门与推荐流推广卡片 |
| AD-07 | 评论区广告 | 评论条目广告字段、评论推广条、带货标签 |
| AD-08 | 播放页广告 | 黑胶广告层、送礼/活动贴纸、曝光兜底 |
| AD-09 | 音频贴片广告 | 音频贴片、播客视频广告（含 `:play` 进程） |

### UI 界面精简（默认开启，UI-06 除外，15 项）

| ID | 功能 | 说明 |
|---|---|---|
| UI-01 | 更新弹窗屏蔽 | 版本检查、强更弹窗、应用内下载入口 |
| UI-02 | 跳过启动延迟 | 去掉红屏人为等待 |
| UI-03 | 首页模块净化 | 按 blockCode 隐藏发现页卡片；同步过滤首页 MMKV 缓存 |
| UI-04 | 顶部频道管理 | 按保留清单真实删除顶部 tab 与页面；至少保留 1 个 |
| UI-05 | 底部导航精简 | 按保留清单过滤真实四项底栏 |
| UI-06 | 侧栏抽屉精简 | **暂不支持**：9.6.08 未找到可靠的抽屉数据挂载点 |
| UI-07 | 引导与气泡屏蔽 | 推送/评分/VIP/直播/底栏气泡、青少年弹窗 |
| UI-08 | 禁止自动播放 | 冷启动、信息流、Banner、蓝牙、4G MLog 自动播放 |
| UI-09 | 关闭头像挂件 | 个人主页/评论区头像装饰 |
| UI-10 | 播放页提示治理 | 评论气泡、推荐歌曲提示、卡顿/网络错误弹窗、激励广告浮层 |
| UI-11 | 搜索热词轮播 | 关闭搜索框运营热词 |
| UI-12 | 播客与 AI DJ 引导 | FM/听书/AI DJ 引导 |
| UI-13 | 小组件与推送引导 | 添加小组件、推送引导弹窗 |
| UI-14 | 歌词页推广 | 歌词底部 Hint、选项气泡、歌词视频弹窗 |
| UI-15 | 云村与直播推广 | 直播推广位、云村发布/搜索气泡 |

### PV 隐私保护（默认关闭，6 项）

| ID | 功能 | 说明 |
|---|---|---|
| PV-01 | OAID 与设备追踪拦截 | OAID 上传与启动任务 |
| PV-02 | 日志写盘任务屏蔽 | `NeedLogToFileTask` / `NBLogTask` |
| PV-03 | 启动任务黑名单 | 按 taskName/类简名跳过非必要启动任务 |
| PV-04 | APM 上报抑制 | 抑制 `logApm` / `forceApmUpload`（不拦初始化） |
| PV-05 | 统计数据上报抑制 | 记录统计 key，并关闭 LogMonitorConfigManager 监控开关 |
| PV-06 | 剪贴板读取拦截 | 全局 ClipboardUtils 入口返回 null |

### EN 功能增强（默认关闭，1 项）

| ID | 功能 | 说明 |
|---|---|---|
| EN-01 | 我的页自定义背景 | 本地图片全屏背景；仅本机可见，不改变会员状态 |

### EX 实验功能（默认关闭，2 项）

| ID | 功能 | 说明 |
|---|---|---|
| EX-01 | 强制深色模式 | 强制开启夜间深色主题，可能与宿主主题资源冲突 |
| EX-02 | 启动任务编排 | 白名单任务优先级/线程/降级编排 |

### 其他（1 项）

| 项 | 说明 |
|---|---|
| 隐藏桌面图标 | 隐藏后桌面与应用抽屉不再显示图标；桌面入口由 `activity-alias` 承载，隐藏只禁用 alias，MainActivity 保留 `MODULE_SETTINGS` 入口，可从 LSPosed 管理器重新打开本页恢复 |

## 诊断与数据通道

```
目标进程 HookState -> StatusProvider.call("report")（校验 UID）
  -> 模块 App hook_status -> 诊断页
```

- `StatusProvider` 每次 `call` 校验 `Binder.getCallingUid()` 属于目标应用，其他 UID 拒绝。
- 回传内容仅计数、挂载状态与 skip 原因；payload 在 Provider 边界按结构白名单整体校验（根键 / Feature 名 / 字段集 / 条数 ≤800 / 字符串 ≤256 / 总量 ≤64KB），不合拒绝。
- 设置页不显示逐功能诊断；切到诊断 tab 或 `onResume` 时刷新，不自动轮询。
- 异常横幅条件：目标版本不匹配，或已启用且明确失败功能数 ≥ 2。

## 构建

环境：JDK 21（构建用；Java source/target 仍为 17）、Android SDK Platform 37、Gradle wrapper 8.13、AGP 8.13.2。

```bash
# 单元测试（含 ProfileExportTest，导出 app/build/profile9608.json 与 profile9613.json）
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

- `compileOnly io.github.libxposed:api:102.0.0`
- `implementation io.github.libxposed:service:102.0.0`
- `implementation org.luckypray:dexkit:2.3.0`（硬编码失败时兜底，已启用 legacy JNI 打包）
- AppCompat / Material / androidx.preference
- 单测：JUnit 4.13.2 + `org.json:json`；当前 **252 个用例**（`:app:testDebugUnitTest`）

## 已知限制

- **适配版本：9.6.08 / 9.6.13 / 9.6.15**。其他版本默认 best-effort 继续挂载（与 QuietBox 语义一致），功能可能失效，状态卡/横幅会提示未适配。
- 广告由服务端下发，模块只能阻止展示/上报，不能阻止接口请求。
- 宿主存在 Tinker 风格热补丁，补丁可能替换被 hook 的实现。
- **9.6.13 / 9.6.15 适配状态**：9.6.13 已在 2.4.4 真机验证；9.6.15 复用
  9.6.13 Profile，2026-10-29 对 9.6.15 base.apk 完成 145/145 挂载点静态体检，
  未做 9.6.15 单独真机验收。UI-03 按 `Block.positionCode` 过滤
  `MixContainerData.getResources()`（含 `NativeBlock` / `RnBlock` 兜底）；
  UI-05 过滤 `tf0.o$a#y` 入参（Profile 门控）；EN-01 hook
  `MyMusicProxyFragment#kc()Z` 强制原生页（9.6.08 回退 `oc()Z`）。三项均挂
  运行时 ClassLoader bootstrap（Tinker 兼容），并校验运行时 loader 可加载宿主类。
- **底栏无法去掉「首页」Tab**：9.6.13/9.6.15 主页面初始化依赖首页，强行删除
  会导致启动卡死；保留清单未包含首页时结果为「首页 + 保留清单」。
- **UI-03 新 code 需手动勾选**：9.6.13/9.6.15 首页新增的
  `PAGE_RECOMMEND_SEARCH_TOP_BANNER` / `SEARCH_CHANNEL_ENTRY` / `MORE_RCMD`
  等 block code 不在旧清单中，需要在首页定制里勾选对应项才会隐藏。
- **清除「我的页背景」后 `validType=4` 不回滚**：宿主侧注入不会随清除撤销，
  清除后如仍显示背景可重启网易云（与 2.4.2 一致）。
- 诊断数据 TTL 为 10 分钟且不发送心跳；目标进程空闲超过 10 分钟时诊断页显示
  「数据已过期」，这是设计内的「未知」状态。异常横幅只统计 critical 挂载失败。
- UI-06 侧栏抽屉无可靠挂载点，保持 blocked，设置页置灰。
- 部分 UI 挂载点没有 DexKit 锚点，只能精确类名匹配；宿主改名时对应子项报告缺失。
- DexKit 兜底依赖 release 的 legacy JNI 打包；旧版本/改版仍可能定位失败。
- Hook 健康度通过 `StatusProvider` 回写；MIUI 等系统可能拦截目标进程冷启动该 provider，
  此时仅在 logcat 输出，诊断页显示「暂无目标进程回写」。
- PV-05 会记录统计上报并关闭 `LogMonitorConfigManager` 的监控开关，不是纯只读。
- 不触碰安全/风控/设备 ID，不绕过付费、VIP、DRM、版权区域限制。

## 同系列模块

- 云音乐工具箱 (CloudMusicTool)：<https://github.com/yigenhuobah/cloudmusictool>
- 静盒 (QuietBox)：<https://github.com/yigenhuobah/quietbox>
- 静途 (QuietMap)：<https://github.com/yigenhuobah/quietmap>
- 贴吧静净 (TiebaQuiet)：<https://github.com/yigenhuobah/tiebaquiet>

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
