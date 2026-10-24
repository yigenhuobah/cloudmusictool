# Changelog

## 2.4.2 - 2026-10-24

深度代码评审第三轮：调度/管线重构与热路径性能（无新功能）。

### 修复

- **诊断回传改领先+尾沿调度并收敛为共享管线**：`TargetProcessPublisher` 统一
  HookState 与 ExperimentState 两套回传（共享执行器/Context 反射缓存/authority），
  窗口内状态变化不再丢失，最后一次变化必随尾沿上报——冷启动后诊断页
  数分钟级延迟的问题根治；实验通道首事件改为立即发送。
- **AbTest 定位并入 HookKit 主管线**（获得 Profile 参数消歧/旧名映射；DexKit
  兜底待锚点补充后自动生效）；**EN-01 全部类定位改走 HookKit**，失败进入
  诊断页（原仅 logcat 可见）。

### 变更

- HookKit 返回类型校验收紧：skipVoid 现要求精确 void 返回（全仓 26 个
  skipVoid 站点已逐一审计确认为 void 后收紧），9 个文件的逐处样板检查
  收敛为 `installVoidByName`/`installConstFalse` 中心助手。
- 「我的」页 hook 的反射样板与 sampleSize 手工副本删除；set 型配置与
  强开映射解析收敛为 `PrefsSetCodec`/`ForceMapCodec` 单一实现；目标包名
  常量收敛到 `Config.TARGET_PACKAGE`。

### 性能

- **MMKV 读取 hook 零分配快路径**：mmapID 按实例缓存（每读 1 次反射→0）、
  相关性判定改首字符预筛+regionMatches（最坏 240B/读分配→0）、黑名单为空
  时连查表都不进。基准前后对照见评审产物。
- **hit 限流键与 detail 解耦**：被限流的命中不再拼字符串/进锁；限流表
  超 512 条自动清空重建，消除长会话无界增长。

### 验证

- `:app:testDebugUnitTest`：162 个用例通过（2.4.1 为 140）；两波七组经
  全站点审计门、补丁回放与基准复测验收。

## 2.4.1 - 2026-10-24

深度代码评审第二轮修复（协议/并发/性能）。

### 修复

- **回传协议边界真强制**：`StatusProvider` 落盘前对两种通道按结构白名单整体校验
  （根键 / Feature 名 / 字段集 / 条数 ≤800 / 字符串 ≤256 / 总量 ≤64KB），不合拒绝；
  退役与真实协议脱节的 reg./hit./skip. 白名单死抽象。
- **「我的」页背景三连修**：external 共享副本写入改 tmp+rename 原子替换；
  `clear()`/`syncExternal()` 与导入共用单线程队列 FIFO 串行，消除「清除被在途
  导入撤销」「导入报成功但文件已清空」等交错终态；目标进程位图缓存按源文件
  指纹失效，换图/清除后即时生效，不再需要强杀网易云。
- **PV-03 启动任务黑名单套用核心任务保护名单**（与 EX-02 共享同一来源），
  用户配错黑名单不会误杀受保护启动任务。
- **AbTest 挂载失败进入诊断页**（原仅 logcat 可见）；删除 EN-01 无消费死坐标。
- **JsonFilter 零删除快路径**：延迟序列化 + 零删除原引用返回 + 嵌套串廉价预门；
  实测 500KB 零命中 18.8ms → 0.19ms（≈98×），过滤语义不变。
- **死代码清理**：`VersionGate.shouldProceed`（恒真）、`DexKitAnchors.of`、
  `ProfileRegistry.clearActive`、`HookState.markNotFound` 旧重载、
  `MineBackgroundStore.calculateInSampleSize` 包装。

### 变更

- `ExperimentFragment` 改用 `StatusProvider.readExperiments` 单一读取入口。

### 验证

- `:app:testDebugUnitTest`：140 个用例通过（2.4.0 为 123）；七组修复经
  变异验证、补丁回放与独立基准复测验收。
- 真机（网易云音乐 9.6.13 / versionCode 9006013，KernelSU + LSPosed，与第三方模块
  共存环境）冷启动验证：136 挂载全部注册、进程稳定无 FATAL、回传协议零拒绝。
  注：x86_64 模拟器上宿主防篡改库（libpoison.so）有启动崩溃复现，与本版改动的
  关联未证实（真机不重现），待模拟器环境恢复后复查。

## 2.4.0 - 2026-10-24

底栏导航回归 + 适配网易云音乐 9.6.13（fail-open 版本门禁，versionCode 10）。

### 新增

- **底栏 4 tab 导航**：设置 / 实验 / 诊断 / 关于四个顶层 tab 常驻底栏，切换用
  hide/show 保留各 tab 滚动位置；「首页定制」保留为二级页（从设置页进入，
  带返回箭头，进入时隐藏底栏，返回后恢复）。首页定制页移除冗余的
  「仅本机可见」说明（功能表已有同一描述）。
- **适配网易云音乐 9.6.13**（versionCode 9006013）：新增 `Profile9613` 挂载点总表，
  与 `Profile9608` 双档案并存，`ProfileRegistry` 按目标 versionCode 自动选择；
  相对 9.6.08 的混淆漂移共 34 个挂载点重定位（按 mount id 的 overrides 表 +
  hint 第三参等分片级差异），并新增 Profile 感知的方法名映射（hook 文件写死的
  9.6.08 混淆名按活动 Profile 换算成实际方法名）。9.6.08 仍受支持。
- **fail-open 版本门禁**：未适配/未知版本不再因版本不匹配整体跳过 hook：
  active Profile 置空后回退硬编码挂载点 best-effort 安装（与 QuietBox 语义一致），
  状态卡/异常横幅提示未适配；移除 `allow_unsupported_version` 开关。

### 变更

- **工程整备**：新增 `tools/release.sh`（一键发版：预检 → 构建 → 校验 → 归档 →
  可选 tag / Release / 官方仓同步）、`tools/verify-apk.sh`（Xposed 元数据 / scope /
  R8 keep / Manifest / 版本号 / 签名证书校验）、`tools/preflight.sh`（JDK 21 + SDK +
  单测 + lint 预检）、CI（test + lint）；接入 JaCoCo 覆盖率报告；新增 prefs/Feature
  一致性与 Hook 注册表等单测；appcompat 1.8.0 / material 1.14.0；构建基线 JDK 21
  （Java 字节码仍 17）。
- 移除未使用的 `compileOnly io.github.libxposed:annotation:1.0.0` 依赖：
  全部源码零引用，compileOnly 不进入 APK，删除不影响功能与产物。
- `tools/check-mounts.sh` 支持双档案：期望 versionCode 取自所选 profile JSON
  （9608 / 9613 各自携带），不再硬编码单一版本号。

### 修复

- `StatusProvider` 上报 type 只认 mounts/experiments，未知 type 一律拒绝，
  防止绕过 mounts 通道的主进程门禁覆盖主进程快照。
- EX-02 启动任务编排改用 installOne 传入的会话级 config 快照，不二次 `Config.load`。
- `ConfigStore` 读取 Set 时空串视为未设置回退默认值，与 `Config.loadSet` 一致。
- 主界面 tab 切换的刷新挂在事务 `runOnCommit` 之后，落在真正切到的新 tab 上。

### 验证

- `:app:testDebugUnitTest`：123 个用例通过；`:app:lintDebug`、
  `:app:assembleRelease`、`bash tools/preflight.sh`、`bash tools/verify-apk.sh` 通过。
- 模拟器（网易云 9.6.13 / versionCode 9006013）运行时：115 registered / 0 skip /
  10 mapped，无 FATAL。

APK: cloudmusictool-2.4.0.apk
versionCode: 10
大小: 4,231,517 字节
SHA-256: a378b3eeb5658f12d2d39efc3a1017ea926d345f33ddf865366bcdd27209e7de

---

## 2.3.0 - 2026-10-23

隐藏桌面图标（versionCode 9）。
适配网易云音乐 9.6.08。

### 新增

- **隐藏桌面图标**：主设置页「其他」分组新增开关。桌面入口改由 `activity-alias`
  `com.cloudmusictool.app.LauncherEntry` 承载，隐藏只禁用 alias，MainActivity 保留
  `de.robv.android.xposed.category.MODULE_SETTINGS` 入口，可从 LSPosed 管理器重新打开
  设置页恢复，不会自锁；状态以系统组件状态为准，外部改动（adb / 清数据）回到本页自动同步。
  感谢 @Z2549 的 PR #3。

### 变更

- 主设置页在 EX 分组之后新增「其他」分组，用于不属于 AD/UI/PV/EN/EX 的模块级设置；
  `DESIGN.md` 同步补充该可选分组规则。

### 验证

- `:app:testDebugUnitTest`：64 个用例通过（新增 `LauncherIconTest` 7 个）。
- `:app:lintDebug`、`:app:assembleRelease` 通过。
- 模拟器 emulator-5554（Android 15 / API 35）：2.2.0 → 2.3.0 覆盖升级、隐藏/恢复、
  DONT_KILL_APP 不杀进程、隐藏态冷启动、外部组件状态同步、无 FATAL，全部通过；
  MIUI 桌面缓存与 LSPosed 管理器入口未在模拟器覆盖。
- 发布签名证书 SHA-256 与 2.2.0 一致。

APK: cloudmusictool-2.3.0.apk
versionCode: 9
大小: 3,873,662 字节
SHA-256: 9ae6276f2639a6c9935dad0349c9b3d8083d282b807ccff08805e6506c9a1e6a

---

## 2.2.0 - 2026-10-23

单页 + 二级页重构与功能统一编号（versionCode 8）。
适配网易云音乐 9.6.08。

### 新增

- **诊断页**：逐功能显示 `编号 标题 · registered · hit N · skip 原因`，无数据 `—`；
  页首显示回写时间，页尾提供刷新与复制诊断信息。
- **关于页**：模块版本、目标应用、源码、四模块互链（当前模块标注「（当前）」）、
  免责声明与 MIT 许可证；链接使用 `ClickableSpan` + `ACTION_VIEW`。
- **异常横幅**：目标版本不匹配，或已启用且明确失败功能数 ≥ 2 时显示；
  不阻塞操作，不新增第五种状态卡状态。

### 变更

- **导航重构**：移除 4 Tab 底栏，改为单 Activity + MaterialToolbar + FrameLayout；
  主页面为设置页，首页定制、实验、诊断、关于为二级页，返回保留主页面滚动位置。
- **状态卡**：只保留「正在连接框架… / 未激活 / 框架已连接 / 已激活」四态；
  删除「目标版本未适配」第五态；副文案包含框架 API、目标版本与 remote prefs 可用性。
- **主页面去诊断**：移除行内诊断摘要，诊断细节只出现在诊断页。
- **功能重编号**：33 项统一为 AD-01~AD-09、UI-01~UI-15、PV-01~PV-06、EN-01、EX-01~EX-02；
  remote prefs key 保持不变，老用户已保存配置不受影响。
- **默认值**：AD/UI 默认开，PV/EN/EX 默认关；UI-15 云村与直播推广改为默认开。
- **构建基线**：AGP 8.12.2 → 8.13.2，compileSdk 36 → 37，移除 `check*AarMetadata` 豁免；
  minSdk 29 / targetSdk 36 / JDK 17 / Gradle 8.13 不变。
- **文案**：用户可见文案全部迁入 `strings.xml`，统一前缀，去除人称与默认值重复描述。

### 修复

- 状态卡查询改到后台单线程，结果回主线程前校验 Fragment、查询代次与 service 实例。
- Manifest 增加目标应用 `<queries>`，避免 Android 11+ 包可见性导致状态误判。
- 诊断回传增加 `process` 字段，失败功能计数只统计主进程；`:play` 进程的挂载点快照不再覆盖主进程数据。

### 验证

- `:app:testDebugUnitTest`：57 个用例通过（新增 `FeatureTest`、`ReportProtocolTest`）。
- `:app:assembleDebug`、`:app:assembleRelease` 通过。
- 真机 25102RKBEC（Android 17 / LSPosed 2.2.1 / 网易云 9.6.08）：模块 App 启动正常，状态卡「已激活」，诊断页显示新编号（AD-01/AD-02/AD-03…），关于页四模块互链正常，首页定制 UI-16 设置正常；无 FATAL。
- 2.2.0 之前的条目保留发布时的旧编号。

APK: cloudmusictool-2.2.0.apk
versionCode: 8
大小: 3,868,902 字节
SHA-256: a37e2fdcd78f01ea8005482f0f6ed74b9da9f7653100181eb4b11a920602b163

---

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
