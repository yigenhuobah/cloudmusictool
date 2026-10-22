# Changelog

## 2.1.0 - 2026-10-22

「我的」页自定义背景（versionCode 7）。
真机环境：25102RKBEC / Android 17 / LSPosed 2.2.1 / 网易云音乐 9.6.08。

### 新增

- **UI-16 我的页自定义背景**（默认关）：在模块 App「首页定制」选择本地图片，
  压缩后存入模块私有目录，并同步一份到
  `Android/media/<模块包名>/mine_background.jpg`。
- 启用后「我的」页强制走原生 `MyMusicFragmentV4`，复用官方全屏背景链路：
  把 `UserBackgroundInfo.validType` 注入为 4（FullScreen），`gh2/b` 渲染全屏图，
  本模块再把本地图片追加到 `headerBgContainer`。
- 注入点：`Profile#setUserBackgroundInfo` / `setMineUserBackground`、
  `pc2.e$a#B`、`pc2.f#g/s`。只改渲染决策输入，不读写 `vip` / `showEntrance`。
- MIUI/HyperOS 上 ContentProvider 唤醒会被 `WakePathChecker` 拦截，因此目标进程
  优先直接读取共享文件，Provider 仅作兜底。

### 限制

- 原生 V4 内容区（创作者中心、歌单列表卡片）仍保持主题深色背景，不会完全透出图片；
  头部与顶部区域是完整全屏效果。
- 仅本机可见；不上传、不影响他人、不改变会员状态。
- 仅适配 9.6.08。

---

## 2.0.1 - 2026-10-20

实验页可读性补丁（versionCode 6）。

- 「可强开实验」为 22 个 key 增加中文名与一句话说明（如 `tabMerge` → “Tab 合并”）。
- 新增分组说明：`c` 通常为对照组，`t/t1~t4` 为不同实验组，具体行为由服务端配置决定。
- 未改变强开/白名单逻辑与默认值。

---

## 2.0.0 - 2026-10-20

多版本架构版（versionCode 5）。
真机环境：25102RKBEC / Android 17 / LSPosed 2.2.1 / 网易云音乐 9.6.08。

### 架构

- **Profile / MountSpec**：把 9.6.08 的类名、方法名、参数/返回描述符、DexKit 锚点收口到
  `app/src/main/java/com/cloudmusictool/app/profile/`。`TargetProfile` 按版本聚合，
  `ProfileRegistry` 按 versionCode/versionName 选择，`MountResolver` 精确类名优先、
  DexKit 锚点兜底，`TypeResolver` 负责 JVM 描述符解析。
- **分片维护**：`ProfileAdPv` 覆盖 AD-01~AD-10、PV-01~PV-07 与 ABTEST 观测点；
  `ProfileUi` 覆盖 UI-01~UI-15（UI-04 blocked，无挂载点）。
- **critical 降级**：`HookState` 的 `expected = installed + failed`（attempted），
  critical 挂载点失败时置 `degraded`，`installed == 0` 时置 `unavailable`；
  设置页状态卡与诊断卡显示「降级」「不可用」，旧版回写没有字段时保持原文案。
- **共用清单**：`ProfileExportTest` 导出 `app/build/profile9608.json`；
  `tools/check-mounts.sh` v2 直接读取该文件，先断言 `versionCode=9006008`，
  再按 `classes` / `methods` 表校验类 descriptor、方法参数与返回类型，
  输出 `OK` / `GONE` / `ERROR`，支持 `--apk` / `--dexsql` / `--profile` / `--allow-version`
  与同名环境变量。

### 行为等价

- 只搬坐标，不改 hook 体、返回值、日志与开关语义；9.6.08 上功能与 1.2.0 一致。
- 仍只适配 9.6.08；未适配版本继续由 `VersionGate` 拦截。
- 不新增 VIP/付费/版权/地区/青少年/登录/Cookie/风控相关逻辑。

### 验证

- 9.6.08 导出 **134 个挂载点**（UI 63 + AD/PV/ABTEST 71），每个 Feature 至少一个 critical。
- `bash tools/check-mounts.sh`：`OK=134 GONE=0 ERROR=0`。
- `:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleRelease` 通过。
- 真机冷启动：`profile mounts=134`；110 条 registered 与 1.2.0 逐行一致，关键 hit 一致；
  状态卡 `installed=104 / expected=104`、`degradedFeatures=0`；无 FATAL/ANR。
- 全程未 reboot，只 `am force-stop` 目标进程。

### 已知限制

- UI-04 侧栏抽屉仍无可靠挂载点，保持 blocked。
- 部分 UI 挂载点没有 DexKit 锚点，只能精确类名匹配；宿主改名时对应子项报告缺失。
- check-mounts 需要 `aapt2`、`dexsql`，以及 `python3/python` 或 `jq` 之一。

---

## 1.2.0 - 2026-10-20

ABTest 实验控制版（versionCode 4）。真机环境：25102RKBEC / Android 17 / LSPosed 2.2.1 / 网易云音乐 9.6.08。

### 新增

- **实验页（第 4 个 Tab）**：只读展示实际命中的 ABTest（key / 分组 / 来源 / 次数），
  数据由目标进程经 `StatusProvider` 回写；冷启动实测记录 200 个 key，source 覆盖 libra / old / startup。
- **白名单强开**：22 个 curated 体验 key，单 key 选择 `c/t/t1/t2/t3/t4`；
  已探测但未收录的 key 可手动「提升为强开」；黑名单 key 显示「禁止强开」。
- **一键恢复默认**：清空所有强开配置；配置写入 remote prefs，重启网易云生效。

### 安全

- `AbTestPolicy` 是白/黑名单单一来源，只做精确匹配；黑名单覆盖
  VIP/付费/版权/音质/试听/订单/地区/青少年/登录/Cookie/鉴权/风控/免流/免广告，
  以及 `boot-`、`kv-`/`fh-kv-`、`wait-task-`、`cold-start-` 等前缀。
- 强开只改 `abtestgroup`，不改 `status`/`clientConfig`（合成对象除外）；启动 AB 只读。
- `:play` 进程不 init/flush 实验观测，避免空快照覆盖主进程数据。
- 写入失败（框架未连接）会明确提示「配置未保存」，不再静默写本地。

### 验证

- 真机：观测 200 个实验；`minibarMarquee=t4`、`aosApiHttps=t4` 强开日志确认；
  一键恢复后 `forceKeys=0`，无 force 日志、无崩溃。
- 32 个单测通过（新增 `AbTestPolicyTest` 9 个）；lint 0 error；release 构建成功。

### 已知限制

- `tabMerge` 在 9.6.08 未被查询，无法用它做 UI 分组变化回归；强开链路改用 `aosApiHttps`/`minibarMarquee` 验证。
- 合成 config 的 `clientConfig` 为空 Map，`checkExperimentWithParam` 走默认值；本期不做参数强开。
- `StartupABManager#getGroup` 只观测，不强开。
- 黑名单 UI 展示已真机确认，但未逐项点选全部黑名单 key。

---

## 1.1.0 - 2026-10-20

可信度收尾版（versionCode 3）。真机环境：25102RKBEC / Android 17 / LSPosed 2.2.1 / 网易云音乐 9.6.08。

### 修复与收尾

- **DexKit 兜底真正可用**：release 启用 `jniLibs.useLegacyPackaging`，加载顺序改为
  「模块 native 目录 → `System.loadLibrary("dexkit")` → 从模块 APK 解压到目标 cacheDir」；
  修正 9 个高风险混淆类的 anchor（旧 `show ad delayed` 不存在，`hit_noad_abtest` 实际属于 `r60.l`）。
  真机强制自检通过：`DexKit selftest located=... elapsed=182ms`，AD-01 仍命中。
- **Hook 健康度可见**：`HookState` 记录 expected/installed/failed/hit；普通变化 2 秒节流、
  安装结束 `flush()` 一次，工作线程回写 `StatusProvider`。真机状态卡：
  「本次尝试 104 个挂载点，14 项未命中」（未命中项随服务端下发变化），诊断卡逐项展示。
- **UI-02 顶部频道真实删除**：在 `dr.h.T0` 数据源上返回过滤后的新列表，适配器 `getItemCount`
  与 `createFragment` 索引天然重映射；`hc/Tc/Lc/getTabDataList` 同步过滤，`MusicTabLayout.addTab`
  兜底移除预创建 TabView。真机验证「只保留推荐 → 1 个 tab、左右滑不进隐藏频道、旋转/深色切换不崩」。
- **UI-04 下架**：删除 `DrawerHooks`、`drawer_items_filter` 读取与配置入口；Feature 保留占位，文案统一「暂不支持」。
- **隐藏自检**：关于页连点版本号 7 次显示 `dexkit_selftest` 开关，默认关。

### 新增

- `Hooks.java` 已接线 10 个新功能：AD-09 搜索广告、AD-10 广告预加载、UI-10 播放页提示、
  UI-11 搜索热词、UI-12 播客/AI DJ 引导、UI-13 小组件/推送引导、UI-14 歌词推广、UI-15 云村/直播、
  PV-06 剪贴板、PV-07 启动任务编排。
- 新注册 **26 个挂载点**：AD-09 5、AD-10 3、UI-10 5、UI-11 1、UI-12 4、UI-13 5、UI-14 3。
  默认值保持既定决策：AD/UI 默认开，UI-15/PV-06/PV-07 默认关。
- 真机已验证命中：AD-10（冷启动 `APreloadRecommendViewTask.init`）、UI-11（`SearchGuide.isValid`）、
  UI-02（频道 6→1）、PV-06（开启后 `utils.h2#d` 注册，关闭后恢复）。
- 其余新功能为“已注册、未自然触发”；完整五步验证状态见
  `docs/phase2/1.1.0-VERIFY-A.md`。

### 工程

- 新增 JUnit 4.13.2 单测：`filterCodeListString`、`filterValue`、`Config.loadSet`、`VersionGate`、`ProcessFilter`。
- `HookKit` 安装前校验返回类型；`hit` 按挂载点 label 5 秒限流（每个 label 首次必打）；反射样板收口。
- `ProcessFilter` 在进程名为 null 时回退 `Application.getProcessName()`。
- R8 keep 收窄到入口/Preference/Provider/DexKit。

### 已知限制

- UI-04 暂不支持；UI-02 的「配置为空回退全量」「多频道保留」已真机补验通过，频道管理页拖拽/删除未回归。
- AD-02/04/06/07、UI-06、UI-09 仍属「已注册、未自然触发」，未做目视确认。
- 1.1.0 新增功能中多数未做完整五步验证（开启→触发→hit→截图→关闭恢复）；
  AD-09/UI-10/UI-12/UI-13/UI-14 未自然触发，UI-15/PV-07 未验证。
- AD-10 按 AD 组策略默认开启，冷启动已命中 `APreloadRecommendViewTask.init` 与
  `PodcastRewardAdTask.init`；`AdColdBootEntranceTask.init` 未命中，属已知例外，后续补全五步验证。
- MIUI 等系统可能拦截目标进程冷启动 `StatusProvider`，此时只写 logcat。

---

## 1.0.0 - 2026-10-19

正式版。已完成 25102RKBEC 真机验证（Android 17 / LSPosed 2.2.1 / 网易云音乐 9.6.08）。

### 变更

- **真机验证通过**：82+ 挂载点全部 registered（仅注册，不代表 hit）；AD-01/03/05/08、UI-01/03/05/07、PV-03/05 有命中实测；
  UI-01 首页过滤与 AD-03 开关做过开启/关闭双向回归。
- **UI-01 修复**：推荐页改走 DSL/MixContainer 链路（`MixContainerData.getResources/getBlockCodeOrderList`），
  修复「勾选隐藏卡片后无效」的问题；新增快捷入口/雷达歌单/排行榜三条实测别名映射。
- **首页定制清单重排**：按 9.6.08 真实下发 blockCode 重排为 30 项，删除 7 个不存在的 code。
- **UI-03 底栏**：选项修正为真实 tab（首页/搜索/关注/我的），实测 4→2 过滤生效。
- **AD-07**：`:play` 进程按需放行（实测 9.6.08 播放在主进程，属前瞻）。
- **PV-01~05 开启路径**：真机验证无闪退、登录与播放正常，默认仍关闭。

### 已知限制

- **UI-02 顶部频道保留暂不启用**：9.6.08 频道数据链路已定位（`BaseArkV3Fragment.getTabDataList`），
  但渲染层 tab 容器无法从外部可靠删除，空频道仍会留下可点击槽位。功能已置灰。
- **UI-04 抽屉精简暂不启用**：无可靠挂载点，功能已置灰。
- 依赖服务端下发的场景（运营弹窗、播放页贴片、引导气泡）未做自然触发目视验证。
- 网易云版本升级可能导致挂载点失效，届时需用 `tools/check-mounts.sh` 重新体检。

### 默认值

广告与界面类默认开启；隐私类（PV-01~05）默认关闭，需手动开启。

---

## 0.1.0 - 2026-10-19

首个预发布版本（代码完成，待真机验证）。

### 新增

- **广告拦截**
  - 开屏广告三层拦截（判定 / 数据源 / 落地页）+ 摇一摇 + 免广实验分支
  - 运营弹窗屏蔽（保留一键登录）
  - 更新弹窗与强制更新拦截
  - 首页信息流广告、评论区广告、播放页广告
  - 音频贴片与播客广告
  - 广告统计上报、外部 App 静默跳转、曝光预请求阻断
- **界面净化**
  - 首页 blockCode 逐项隐藏（含 MMKV 缓存过滤）
  - 顶部频道删除/排序解锁与保留清单
  - 底部导航精简
  - 启动延迟跳过
  - 引导气泡/评分/VIP/直播弹窗、青少年弹窗屏蔽
  - 自动播放控制（冷启动/信息流/Banner/蓝牙/4G MLog）
  - 实验性强制深色模式
  - 头像挂件关闭
- **隐私**
  - OAID 上报拦截
  - 日志写盘任务屏蔽
  - 启动任务黑名单
  - APM 上报抑制
  - 统计数据观测（只读）

### 工程

- libxposed API 102（单入口、`META-INF/xposed` 元数据、钩子 id、PROTECTIVE 异常模式）
- DexKit 2.3.0 兜底定位（硬编码失败时按 anchors 查询）
- 9.6.08 版本门禁
- Material 3 设置界面：功能 / 首页定制 / 关于
- R8 保留入口、Preference、Provider、DexKit 与 libxposed service

### 审查修复

- 修正 `AdAudioClipManager.d/c` 的返回值（null / Boolean）
- 取消对 `APMTrackerImpl.init`、`AegisSdkInitTask` 的拦截，避免破坏 ServiceFacade 与网络加密
- 修正底栏 `BottomTabInfoVO` getter（`getTabTitle` / `getTabCode`）
- 移除 `n40.b.c`、`CommentAdapter.o1` 等无效或高风险 Hook
- 移除隐私协议/首启/权限引导绕过
- 修复 8 处 after-hook 二次 `proceed()`
- 版本门禁改为轻量 `getPackageInfo`，版本未知时 best-effort 放行
- R8 增加 app 包与 libxposed service keep 规则

### 已知限制

- 仅适配网易云音乐 9.6.08
- UI-04 侧栏抽屉精简暂未启用
- 服务端下发广告只能阻止展示/上报，不能阻止请求
- 统计观测不拦截上报
