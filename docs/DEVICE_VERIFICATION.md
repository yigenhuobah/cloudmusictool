# 真机验证记录

> 2.1.0 UI-16 的验收见下文第一节；1.1.0 的详细验收见
> [`phase2/1.1.0-VERIFY-A.md`](phase2/1.1.0-VERIFY-A.md)。
> 历史记录中的「零失效」只代表 registered，不代表 hit。

---

# 2.1.0 UI-16 验证（2026-10-22）

设备：`25102RKBEC`（Android 17 / LSPosed 2.2.1 / 网易云 9.6.08）
APK：`cloudmusictool-2.1.0.apk`（versionCode 7）

| 项 | 结果 | 说明 |
|---|---|---|
| UI-16 挂载 | 通过 | manager `e`/`onResume`、`MyMusicProxyFragment#oc`、Profile 模型、`pc2` 解析/SP |
| 强制原生 V4 | 是 | 日志 `forced native Mine page` |
| validType=4 注入 | 是 | `profile model` / `mine model` / `sp write` 均命中 |
| 头部全屏背景 | 是 | 头像/昵称/统计/快捷入口透出本地图片 |
| 内容卡片 | 保持主题深色 | 原生 V4 卡片自绘，未完全透出；已接受 |
| 关闭 UI-16 | 未做 | 后续回归 RN 分支恢复 |

---

# 1.1.0 验证矩阵（2026-10-20）

设备：`25102RKBEC`（Android 17 / LSPosed 2.2.1 / 网易云 9.6.08）
APK：`cloudmusictool-1.1.0.apk`（versionCode 3，release 覆盖安装）

| 功能 | registered | hit | 未验证 / 说明 |
|---|---|---|---|
| AD-01 开屏 | 7/7 | 是（E/G/o、q60.w0.g、DexKit 自检） | — |
| AD-02 运营弹窗 | 6/6 | 否 | 服务端未下发 |
| AD-03 更新弹窗 | 6/6 | 是（UpdateInfo） | 下载入口未触发 |
| AD-04 首页 Banner | 1/1 | 随服务端下发（本轮否） | Banner 未下发时无 hit |
| AD-05 评论广告 | 8/8 | 是（Comment 标签） | 4 个 getter 未自然命中 |
| AD-06 播放页广告 | 4/4 | 否 | 服务端未下发贴片 |
| AD-07 音频/播客 | 4/4 | 否 | 未触发播客广告 |
| AD-08 上报/外跳 | 4/4 | 是（Deeplink task） | 外跳拦截未触发 |
| AD-09 搜索广告 | 5/5 | 否 | 已接线，未自然触发 |
| AD-10 广告预加载 | 3/3 | 是（冷启动 `APreloadRecommendViewTask.init`） | 关闭恢复未做 |
| UI-01 首页模块 | 6/6 | 是（历史版本） | 1.1.0 未逐项回归 |
| UI-02 顶部频道 | 12/12 | 是（Tc/Lc/T0/hc/getTabDataList 均 filtered 6→1） | 真机：1 个 tab、滑动/旋转/深色通过 |
| UI-03 底栏 | 4/4 | 是（历史版本） | 1.1.0 未逐项回归 |
| UI-04 抽屉 | — | — | 暂不支持，配置入口已移除 |
| UI-05 启动延迟 | 3/3 | 是 | — |
| UI-06 引导气泡 | 6/6 | 否 | 服务端未下发 |
| UI-07 自动播放 | 6/6 | 是（历史版本） | 1.1.0 未逐项回归 |
| UI-08 深色 | 默认关 | — | 未做完整回归 |
| UI-09 挂件 | 1/1 | 否 | 未自然触发 |
| UI-10 播放页提示 | 5/5 | 否 | 已接线，未自然触发 |
| UI-11 搜索热词 | 1/1 | 是（`SearchGuide.isValid`） | — |
| UI-12 播客/AI DJ | 4/4 | 否 | 已接线，未自然触发 |
| UI-13 小组件/推送 | 5/5 | 否 | 已接线，未自然触发；onCreateView 返回 null 方案 |
| UI-14 歌词推广 | 3/3 | 否 | 已接线，未自然触发 |
| UI-15 云村/直播 | 默认关 | 否 | 未开启验证 |
| PV-01~05 | 5 项 | PV-03/05 是 | 其余仅开启无崩溃 |
| PV-06 剪贴板 | 默认关 | 否 | 开启后 `utils.h2#d` 注册、关闭后恢复；hit 未自然触发 |
| PV-07 启动编排 | 默认关 | 否 | 未开启验证 |

1.1.0 真机新增结论：

- DexKit 强制自检：`DexKit selftest located=com.netease.cloudmusic.module.ad.LoadingAdManager elapsed=182ms`，
  AD-01 仍命中；关闭自检后无 selftest 日志，AD-01 走硬编码路径命中。
- Hook 健康度：状态卡 `本次尝试 104 个挂载点，14 项未命中`；诊断卡逐项可见。
  （未命中项随服务端下发变化；接线初测为 108/108，本轮把 UI-02 的 5 个 `addTab` 重载收敛为 1 个底层重载，
  UI-02 由 16 降为 12。）
- StatusProvider 在模块 App 进程存活时回写成功；MIUI 冷启动会 `security reject start`。

---

# 0.1.0 首轮验证（历史）

设备：`25102RKBEC`（Android 17 / SDK 37 / arm64-v8a / KernelSU / LSPosed 2.2.1）
目标：网易云音乐 `com.netease.cloudmusic` 9.6.08
日期：2026-10-19
APK：`app-debug.apk`（含 UI-01 DSL 链路修复后的构建）

## 环境与接入

- 无线 adb（mDNS `adb-e4287ae2`，192.168.3.217:41137）连接正常。
- LSPosed 无独立管理器 App，配置在 `/data/adb/lspd/config/modules_config.db`；
  模块安装后已自动注册：`enabled=1`、scope `com.netease.cloudmusic`（`staticScope=true` 生效）。
- 模块 App 状态卡显示「已激活 · 框架 API 102 · 已注入网易云音乐」。

## 启动安装核对

logcat tag `CloudMusicTool` / `LSPosedFramework` 全量 `registered` 记录：

| 功能 | 结果 |
|---|---|
| AD-01 开屏 | 7 个挂载点全部 registered（LoadingAdManager.E/G/o、LoadingAdActivity、LoadingAdFragment、LoadingAdShakeHelper、q60.w0.g） |
| AD-02 运营弹窗 | 6 个 LaunchDialog show* 全部 registered |
| AD-03 更新弹窗 | 6 个全部 registered |
| AD-04 信息流广告 | Banner#isAdBanner registered |
| AD-05 评论广告 | 8 个全部 registered |
| AD-06 播放页广告 | 4 个全部 registered |
| AD-07 音频/播客 | 4 个全部 registered |
| AD-08 上报/外跳 | 4 个全部 registered |
| UI-01 首页模块 | DiscoveryBlockData#getBlockList + MMKV×3 + MixContainerData×2 registered |
| UI-02 顶部频道 | 6 个全部 registered |
| UI-03 底栏 | 4 个全部 registered |
| UI-04 抽屉 | 按设计 blocked（无可靠挂载点） |
| UI-05 启动延迟 | 3 个全部 registered |
| UI-06 引导治理 | 6 个全部 registered |
| UI-07 自动播放 | 6 个全部 registered |
| UI-08 深色 | 默认关，未安装 |
| UI-09 挂件 | jn#L registered |
| PV-01~05 | 默认关，日志确认 `disabled` |

无 `skip: class/method not found`，无安装异常。

## 命中验证（hit）

- AD-01：`LoadingAdManager.G`、`LoadingAdManager.o -> empty ArrayList`、`q60.w0.g -> false`（命中冷启动）。
- AD-03：`UpdateInfo.getForceUpdate/getNeedPopUp -> FALSE`、`appupdate.d.B -> false`。
- AD-05：评论区 `Comment.isTagValid -> false`、`Comment.getBottomTags -> null`（打开歌曲评论页实测）。
- AD-08：`AdExposureDeeplinkPreRequestTask.init`、`AdExposureDeeplinkTask.init`。
- UI-05：`StartTimeHandler.isNeedDelayStart -> false`、`checkDelayDoAction execute immediately`。
- UI-07：`tt0.d.a/d -> false`、`lv0.j.e -> AutoPlayConfig(false,false)`。
- UI-01：见下节专项。

视觉确认：冷启动无开屏广告；首页无信息流广告卡片；播放页无贴片广告；评论区无广告横幅。

## UI-01 专项（首轮发现并修复的问题）

现象：勾选「快捷入口」「雷达歌单」重启后首页卡片未消失。

根因：9.6.08 推荐页走 DSL/MixContainer 链路（`com.netease.luna.cm.container.DslBlock`），
模块身份在 `DslWrapperModel` 的 originData（`PAGE_*` 代码 / `home_*_module_*` key）里，
原 `DiscoveryBlockData.getBlockList` 只覆盖经典发现页；且部分模块（雷达歌单等）没有 `PAGE_` 代码。

修复：
1. 新增 `MixContainerData.getBlockCodeOrderList/getResources` 过滤；
2. `collectCodes` 从 originData/extData/dslContent 收集全部 `PAGE_*` 代码；
3. 模块 key 兜底 + 别名表（快捷入口↔`PAGE_DAILY_REC`、雷达歌单↔`home_radar_playlist_module`、排行榜↔`PAGE_BLOCK_TOPLIST`），均为真机逐块核对过的映射；
4. MMKV 缓存过滤补 `getString` 与 mmapID 精确匹配；后处理异常不再二次 proceed。

验证：
- 开启「快捷入口+雷达歌单」→ 重启 → `UI-01 hit resources filtered 2/9`，截图确认两行消失；
- 取消勾选 → 重启 → 无 hit、两行恢复（反向回归通过）。

## 配置写回验证

- 功能开关、多选清单均落到 LSPosed remote prefs（`module_configs.group_name='config'`），
  Java 序列化 Boolean / HashSet 正确；
- 重启目标进程后新配置生效（AD-03 关闭→`disabled`，再开→`enabled`，实测两轮）。

## 遗留 / 未测

- UI-02 顶部频道管理：已注册，默认透传；未做「长按频道→管理」页面的完整交互测试。
- UI-03 底栏：已注册，默认保留全部；自定义保留项未做视觉验证（挂载点为 getTabCode/getTabTitle，
  与 9.6.08 实际 tab code 的对应关系需配置后实测）。
- UI-06/AD-02：命中依赖服务端下发，本次冷启动未出现运营弹窗/引导气泡（注册成功，等待自然触发）。
- PV-01~05：默认关，本轮只验证了默认关闭路径；开启路径留待下一轮（风险项，需逐项观察稳定性）。
- UI-04 抽屉精简：仍为 blocked，待更可靠挂载点。

---

# 二轮补验（2026-10-19 晚）

## 1. 功能补验

### UI-03 底栏自定义（通过）

探针实测 9.6.08 底栏 4 项：`首页/main`、`搜索/search`、`关注/social`、`我的/mine`
（`BottomTabInfoVO.getTabTitle/getTabCode`）。原 UI 选项（发现/播客/我的/漫游/社区）是规划期猜测值，
与真实 tab 对不上，已修正为真实四项。

实测：保留「首页+我的」→ 重启 → `UI-03 hit th0.h#h filtered from 4 to 2 tabs`（16 次），
截图确认底栏只剩两项。**通过**。

### UI-02 顶部频道（挂载点失效，需重新定位）

- 探针显示 `TabExtra.getNowChannelItems` / `UserSettingDTO.getCurrentRes` 在冷启动+日常使用
  **从未被调用**——9.6.08 的顶部 tab 栏不走这两条数据路径。
- 实测配置保留「推荐」→ 重启 → tab 栏纹丝不动、无 hit 输出。
- 频道管理页入口自动化打不开：长按 tab 被 MIUI「文本识别」(com.miui.contentextension) 抢走；
  临时禁用该系统组件后 500/900ms 长按仍无响应（入口可能已变或阈值更高）。
- 结论：**UI-02 的过滤链路在 9.6.08 上不生效**，getCanDelete/getCanDrag 解锁未目视验证。
  需重新定位 tab 栏真实数据源后修复。已在功能描述中如实标注。

### PV-01~05 开启路径（通过）

全部开启 → 重启 → 5 项 registered、0 FATAL/ANR、进程存活、登录正常（个人化雷达歌单正常渲染）：
- PV-03 实测命中：`StartupTask.needExecute blocked AdOaidUploadTask / NBLogTask / NeedLogToFileTask`；
- PV-05 实测命中：`LogMonitorConfigManager.b/c -> false`；
- 播放链路正常（media_session `state=PLAYING`，无卡顿无崩溃）。
验证后已将 PV 全部关回默认。**通过**。

### AD-02/06/07、UI-06 自然触发（未出现场景）

服务端未下发运营弹窗/贴片/引导气泡，无法目视确认；保持「已注册（仅 registered）」状态。

## 2. 首页定制映射覆盖

- 新增别名「排行榜↔PAGE_BLOCK_TOPLIST」实测通过（`UI-01 hit resources filtered 1/9`）。
- 「快捷入口↔PAGE_DAILY_REC」「雷达歌单↔home_radar_playlist_module」一轮已验证。
- 其余选项（同城热门/VIP/问候卡片/播客电台/Mlog/今日运势/音乐日历/听书剧场/运营 Banner）
  对应卡片当前推荐流未渲染，无法实测；走「PAGE_ 代码直配」通用路径，待卡片自然出现时验证。
- `tools/check-mounts.sh` 已补 `MixContainerData#getBlockCodeOrderList/getResources`、
  `DslWrapperModel#getOriginData`，85 项全 OK。

## 3. 配置与环境

- 三个自定义清单、PV 开关验证后已全部恢复默认（空集合/关闭），AD 开关保持默认全开。
- 为绕过长按干扰临时禁用过 `com.miui.contentextension`（MIUI 文本识别），**已恢复 enabled**。
- 底栏/频道 UI 选项清单已按实测 tab 修正（HomeCustomizeFragment）。
- 探针诊断代码已从 BottomTabHooks/TopTabHooks 移除，release 重打包签名一致。

---

# 短期收尾（2026-10-19 夜）

1. **首页清单重排**：HomeCustomizeFragment 的 block 清单按 9.6.08 真实下发表重写为 30 项
   （猜你喜欢/排行榜/快捷入口/雷达歌单/每日推荐/新歌新碟/云村精选/红心相似/心动歌单/场景/风格/
   调研问卷/VIP×2/Banner 1/6/播客×4/艺人动态/艺人 FM/音乐 FM/粉丝团/实时兴趣/组合/品牌/
   音乐播客图文/声音视频歌单）。删除了 6 个真实不存在的 code：PAGE_RECOMMEND_BANNER_0、
   PAGE_RECOMMEND_LBS、PAGE_RECOMMEND_GREETING、PAGE_BLOCK_MLOG、PAGE_BLOCK_TODAY_FORTUNE、
   PAGE_MUSIC_CALENDAR、PAGE_BLOCK_THEATER。仍走「PAGE_ 直配 + 3 条实测别名」匹配。
2. **UI-02 标为 blocked**（与 UI-04 同等待遇）：Feature 枚举 defaultEnabled=false +
   blockedReason「9.6.08 顶部频道数据链路未接通」，功能页置灰、hook 不注册；
   首页定制页的「顶部频道保留」配置项同步置灰并注明暂不生效。待数据链路重摸后再解锁。
3. **AD-07 :play 疑问结论**：实测 9.6.08 播放音乐时**没有独立 :play 进程**（ps 仅主进程），
   AdAudioClipManager 音频贴片链路就在主进程，不存在进程错配。为防未来版本/播客场景
   出现 :play，ProcessFilter 已放行 :play 并只安装 AD-07（Hooks.installPlayOnly），
   装不装均无副作用。
4. release 重打包，签名指纹一致；真机冒烟：UI-02 跳过、其余 14 项 enabled 正常。

---

# UI-02 深度修复尝试与结论（2026-10-19 夜）

用户质疑「只屏蔽文字没屏蔽功能」，实测属实：`k.S` 清文本后 tab 槽位仍在、仍可点击切换。

**修复过程（全部真机验证）**：
1. `TextView.setText` 栈考古 → 定位渲染路径 `MusicTabLayout$TabView.setText ← k.T ← BaseArkV3Fragment.Uc`；
2. dexsql 反查 → 找到真实数据容器 `BaseArkV3Fragment.getTabDataList()`（元素 `DiscoveryTabInfoV3`，
   getTitle/getCode：心动/fastPlay、推荐/rcmd、音乐/music、播客/podcast、听书/vBook、AI写歌/ai-generate-song）；
3. 挂数据过滤（getTabDataList + hc/Tc/Lc 入参，均实测 hit 6→1）；
4. 挂文本层 `MusicTabLayout$k.S(CharSequence)` 清文本 + GONE TabView（实测命中）；
5. 尝试 `TabView.setTab(k)`、`MusicTabLayout$l` 选中回调、`onMeasure/onLayout` 扫描子 View、
   `getTabCount/getTabAt` 等多条渲染层路径——**均未命中或无效**。

**结论**：9.6.08 顶部 tab 是「预创建 TabView + 增量 addTab + 私有 TextView 子类」结构，
数据层过滤已生效，但渲染层的 tab 容器无法从外部可靠删除，空槽位仍可点击。
多轮尝试后判定渲染层不可达，**UI-02 降回 blocked**（Feature defaultEnabled=false +
配置项置灰 + 原因标注「渲染层无法完全屏蔽空频道」）。

保留代码：`getTabDataList/hc/Tc/Lc` 数据过滤与 `k.S` 文本清理（链路已打通，
待后续找到渲染层可靠入口即可解锁）。`top_channels_keep` 残留旧值不影响功能（UI-02 不读取）。
