# Issue #1 评估：「我的」页自定义全屏背景

> Issue：<https://github.com/yigenhuobah/cloudmusictool/issues/1>
> 标题：许愿
> 内容：能否实现「我的」界面可以自定义图片全屏，就是与会员专享的个人主页全屏一样的效果。
> 评估日期：2026-10-20
> 目标版本：网易云音乐 9.6.08（versionCode 9006008）

## 1. 结论

- **技术上可行**。9.6.08 的「我的」页本来就有一层全屏背景容器，背景图由
  `MyMusicProfileHeaderBgViewHolderManager` 动态挂载；我们可以把用户选择的本地图片
  画进同一个容器，不需要改宿主布局。
- **但这属于会员专享视觉效果**。是否显示背景、显示哪种类型，完全由服务端字段
  `UserBackgroundInfo.isShowEntrance()` / `getValidType()` / `getInitedTypeList()`
  决定，客户端没有本地 VIP 判断，完全信任服务端。
- 因此**不能**通过伪造 `validType=4`、`MineUserBackground.fullScreen=true` 或
  `isVip=true` 来“开会员样式”，那会违反项目的「不做 VIP/付费/权益绕过」红线。
- 可接受的替代方案：**纯本地渲染**——不动任何服务端字段和会员状态，只把本机图片
  绘制到「我的」页已有的背景容器里，仅本机可见，不上传、不影响他人。建议作为
  `UI-16`（默认关）实现，需要维护者拍板。

## 2. 9.6.08 实际实现（只读反编译结论）

### 2.1 「我的」页

- Fragment：`com.netease.cloudmusic.fragment.MyMusicFragmentV4`
  （基类 `MyMusicFragmentV4Base`）；未登录为 `MyMusicGuestFragmentV4`。
- Fragment 布局：`MyMusicFragmentV4Base#Rc` inflate `res/layout/asn.xml`
  （`Ldl1/f;->h`），根节点是
  `com.netease.cloudmusic.ui.MineCoordinatorLayout`。
- `asn.xml` 顶部 include `res/layout/d1d.xml`：
  - 根 `FrameLayout` id = `flHeaderImage`（`0x7f09136e`），match_parent，是全屏背景层；
  - 内部 `fragmentContainer`（`0x7f0914ad`）→ `headerBgContainer`（`0x7f0916c1`）；
  - `headerBgContainer` 内是全屏 `NeteaseMusicSimpleDraweeView`
    id = `headerSinglePicBgImage`（`0x7f0916d9`）和一个遮罩
    `0x7f0916c4`；
  - 旁边是 `MyMusicTopArcLayout`（`0x7f090c9f`）。

### 2.2 背景挂载

`MyMusicProfileHeaderBgViewHolderManager`：

- 构造参数：`(ViewGroup headerBgContainer, boolean isForProfilePage)`；
- `e(UserBackgroundInfo info)`：按 `info.getValidType()` 选择实现类并挂到容器：
  - `1` → `gh2.g`（默认）
  - `2` → `gh2.f`
  - `3` → `gh2.o`
  - `4` → `gh2.b`（full pic）
- `gh2.b`（type 4）：
  - inflate `res/layout/d21.xml`（`Ldl1/f;->R`），里面就是全屏
    `headerSinglePicBgImage` + 遮罩；
  - `b()` 读取 `backgroundValue.getFullPicValue()`，经模板
    `profile_utils_get_full_pic_requrl` 拼 URL，用 `utils.bh.E(...)` 加载；
  - 加载完成后写入 `headerSinglePicBgImage`。
- `MyMusicFragmentV4Base#nd()` 从 `bi2.g.x2()` 的 LiveData 取
  `Pair<UserBackgroundInfo, ...>`，再调用 `manager.e(...)`。
- 全屏状态：`MyMusicFragmentV4#jf` 观察到背景类型 `== 4` 且当前主题不是
  `SKIN` / `SKIN_CUSTOM` 时，调用 `bi2.b.W1(true)`（`isFullScreenHeader`），
  随后 `kd("backgroundRealLoaded")` 调整内容背景（半透明白遮罩等）。
- 滚动/视差：`com.google.android.material.appbar.MyMusicHeaderBehavior` 与
  `l7.e` 负责 `flHeaderImage`、`headerBgContainer` 的 alpha/位移。

### 2.3 会员门禁在哪里

- `UserBackgroundInfo` 有 `isVip()` 字段，但反编译没有发现客户端在任何背景路径上
  读取它；真正的门禁是服务端下发的：
  - `isShowEntrance()`：是否展示“更换背景”入口；
  - `getValidType()` / `getInitedTypeList()`：允许哪些背景类型；
  - `CoverEditLauncher` 只做裁剪与上传，没有本地 VIP 判断。
- 个人主页的 `MineUserBackground.fullScreen` 同样是服务端 `Profile.mineUserBackground`
  下发的字段，`ProfileActivityV3#Ec` 直接信任它。
- 结论：**客户端不校验会员，服务端不下发就没有这个效果**。伪造字段等于绕过服务端
  权益，项目不做。

## 3. 方案

### 3.1 推荐：UI-16「我的页自定义背景」（纯本地）

**模块侧**

1. 设置页新增「我的页自定义背景」开关（默认关）+「选择图片」「清除」。
2. `ACTION_OPEN_DOCUMENT` 选图后，立即把图片复制到模块 App 私有目录
   （`files/mine_background.<ext>`），不依赖临时 URI 授权。
3. 扩展现有 `StatusProvider`（authority `${applicationId}.status`）：
   - 新增 `openFile(Uri)`，只允许读取固定的背景文件；
   - 继续用 `Binder.getCallingUid()` + `getPackagesForUid` 校验调用方必须是
     `com.netease.cloudmusic`；
   - 不接受任意路径，避免目录穿越。
4. 模块 App 侧提供预览与“清除后恢复默认”。

**Hook 侧**

1. 新功能 `UI-16`，默认关；挂载点纳入 `Profile`（9.6.08）。
2. Hook `MyMusicProfileHeaderBgViewHolderManager#e(UserBackgroundInfo)`：
   - 先 `proceed()` 保留宿主原有逻辑；
   - 通过反射取 manager 的 `headerBgContainer` 字段；
   - 移除宿主刚挂上的背景子 View（或在其上叠加），加入我们自己的
     `ImageView`（match_parent + CENTER_CROP）；
   - 从模块 Provider 读取本地图片，后台线程解码并按屏幕宽度下采样；
   - 设置 `flHeaderImage` alpha=1；
   - 需要全屏观感时，调用宿主自己的 `bi2.b.W1(true)` 与
     `MyMusicFragmentV4Base#kd("custom_bg")`，让内容区按宿主逻辑变半透明；
     不直接改任何会员/服务端字段。
3. 因为 `e()` 会在数据刷新/回前台时重复调用，hook 必须幂等：每次重新确认我们的
   View 仍在最上层，Bitmap 只解码一次并缓存。
4. 主题切换、页面重建、进程重启后重新应用；找不到挂载点时按功能降级，不影响其它功能。

### 3.2 备选：独立 Overlay（不推荐）

在 `MineCoordinatorLayout` 第一个子 View 位置插入自己的全屏 `ImageView`，并把
AppBar/RecyclerView 背景改成透明。优点是完全不碰宿主的背景管理器；缺点是层级、
滚动、主题、自定义皮肤的回归面更大，且容易挡住点击。仅作为方案 3.1 不可行时的退路。

## 4. 边界（必须写进功能说明）

- 仅本机可见：不上传、不写个人主页、不影响其他用户看到的背景。
- 不修改 `isVip` / `isShowEntrance` / `validType` / `fullScreen` 等任何会员或服务端字段。
- 不调用“更换背景”上传链路，不产生任何背景相关网络请求。
- 默认关闭；开启后如果宿主布局变化导致挂载失败，明确显示“不可用”，不静默半开。

## 5. 实施清单（批准后）

1. `Feature.java` 增加 `UI-16`；`Hooks.java` 接线；`strings.xml` / `prefs.xml` 增加文案。
2. 模块 App：图片选择、复制、预览、清除；`StatusProvider.openFile` + UID 校验。
3. Hook：`MineBackgroundHooks`；`Profile9608`/`ProfileUi` 增加挂载点描述。
4. 图片管线：`BitmapFactory` 下采样、`CENTER_CROP`、内存缓存、OOM 兜底。
5. 测试：
   - 单测：Provider 路径白名单、UID 校验、配置读写、下采样尺寸计算；
   - 真机：进入「我的」→ 滚动 → 切 Tab → 重进 → 旋转 → 深色/自定义皮肤 →
     清除图片 → 杀进程重启；全程无崩溃/ANR；
   - 回归：个人主页背景仍由服务端正常控制，未被我们的 hook 影响。
6. 版本：建议 `2.1.0` / `versionCode 7`；CHANGELOG 与 README 说明“本地背景，不改会员状态”。

## 6. 风险

| 风险 | 处理 |
|---|---|
| `headerBgContainer` 被子 View 裁剪/遮挡 | 用 match_parent + CENTER_CROP，实测层级；必要时叠加在宿主背景之上 |
| 宿主数据刷新把我们的 View 移除 | `e()` 每次调用都重新确认并补挂 |
| 主题/自定义皮肤改变容器透明度 | 跟宿主 `W1`/`kd` 状态走，不自建一套 |
| 大图 OOM / 卡顿 | 后台解码 + 按屏幕宽度下采样 + 复用 Bitmap |
| Provider 被其他 App 读取 | 严格 UID 校验 + 固定文件路径 + 不导出目录 |
| 被理解为“解锁会员” | 功能名与说明明确“本地图片，仅本机，不改变会员状态”；默认关 |

## 7. 补充（2026-10-21）：截图是整套 SVIP 皮肤，不是单张背景

维护者提供的截图是「皮肤预览」页里的 **「眠眠兔 2.0」SVIP 专享** 皮肤，包含：

- 全页插画背景（格纹 + 兔子/云朵图案）；
- 顶栏图标、搜索/更多按钮的换肤；
- 头像框、会员标、资料卡、标签 chips 的配色与描边；
- 音乐/播客/笔记 Tab、歌单卡片、播放条、底栏四个图标的整套替换；
- 整体色板、圆角、字体风格。

这已经超出 issue 原文说的“个人主页全屏背景”，属于 **完整皮肤包**。9.6.08 有独立的皮肤系统
（`DolphinThemeToken.SkinTheme`：`SKIN` / `SKIN_CUSTOM`；`ResourceRouter` 负责资源路由、
`buildCustomBgDrawable` 负责自定义背景合成），皮肤包和自定义皮肤入口都由服务端权益控制。

因此需要区分两种目标：

| 目标 | 模块可做性 | 说明 |
|---|---|---|
| A. 只要全屏背景层 | **可做**（UI-16） | 本地图片画进「我的」页背景容器，保留宿主默认图标/配色/卡片 |
| B. 截图这种整套皮肤 | **不做** | 本质是 SVIP 皮肤包；不复刻、不解锁、不复制官方素材 |

如果目标是 B，即使用户自备素材，也需要自建一套主题引擎（换图标、换色板、逐 View 换肤），
工作量和回归面远大于一个背景功能；且官方皮肤素材有版权，不能打包分发。

## 8. 待拍板

1. 目标效果是 A（只要背景层）还是 B（整套皮肤）？
   - A → 按 UI-16 实施；
   - B → 明确告知超出模块边界，关闭 issue 或转为“本地主题引擎”单独立项（不建议）。
2. 若选 A，是否接受“本地渲染会员风格背景”这条边界？
   - 接受 → 实施；
   - 不接受 → 以“不涉及会员权益”为由关闭 issue，并说明原因。
3. 若选 A，是否只做「我的」页，还是也做个人主页的本地背景？
   - 建议先只做「我的」页；个人主页涉及他人可见性与服务端数据，风险更高。
