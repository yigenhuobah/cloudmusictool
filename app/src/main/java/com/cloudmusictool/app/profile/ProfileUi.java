package com.cloudmusictool.app.profile;

import com.cloudmusictool.app.dexkit.DexKitAnchors;

import java.util.ArrayList;
import java.util.List;

/**
 * 界面（UI-01~UI-15）与增强/实验（EN-01、EX-01）的挂载点分片。
 *
 * 坐标全部来自 9.6.08 现有 hook 文件，并用 dexsql 在
 * {@code E:/download/base(2).apk}（versionCode 9006008）上逐条核对过
 * class descriptor、方法参数与返回类型。UI-06 没有可靠挂载点，按计划跳过。
 *
 * critical=true 表示该挂载点缺失时对应功能应降级；每个有挂载点的 Feature
 * 至少保留一个 critical。anchors 只放真实 dex 字符串字面量，没有验证过的
 * 一律留空，避免 DexKit 误命中。
 */
public final class ProfileUi {

    private static final String[] NONE = new String[0];

    /** 宿主所有 HintView#maybeDisplayHint 的固定三参数签名。 */
    private static final String[] HINT_ARGS = {
            "Lcom/netease/cloudmusic/module/hint/view/AbsHintContainer;",
            "Lcom/netease/cloudmusic/module/hint/view/a;",
            "Lcom/netease/cloudmusic/module/hint/view/r;"
    };

    private ProfileUi() {
    }

    public static List<MountSpec> mounts() {
        List<MountSpec> out = new ArrayList<>();

        // ------------------------------------------------------------------
        // UI-03 首页模块净化
        // ------------------------------------------------------------------
        out.add(m("UI-03", "MixContainerData#getBlockCodeOrderList", true,
                "com.netease.cloudmusic.dsl.container.data.MixContainerData",
                "getBlockCodeOrderList", "Ljava/lang/String;", NONE));
        out.add(m("UI-03", "MixContainerData#getResources", true,
                "com.netease.cloudmusic.dsl.container.data.MixContainerData",
                "getResources", "Ljava/util/List;", NONE));
        out.add(m("UI-03", "DiscoveryBlockData#getBlockList", false,
                "com.netease.cloudmusic.discovery.data.meta.DiscoveryBlockData",
                "getBlockList", "Ljava/util/List;", NONE));
        out.add(m("UI-03", "MMKV#decodeString(String)", false,
                "com.tencent.mmkv.MMKV",
                "decodeString", "Ljava/lang/String;",
                new String[]{"Ljava/lang/String;"}));
        out.add(m("UI-03", "MMKV#decodeString(String,String)", false,
                "com.tencent.mmkv.MMKV",
                "decodeString", "Ljava/lang/String;",
                new String[]{"Ljava/lang/String;", "Ljava/lang/String;"}));
        out.add(m("UI-03", "MMKV#getString(String,String)", false,
                "com.tencent.mmkv.MMKV",
                "getString", "Ljava/lang/String;",
                new String[]{"Ljava/lang/String;", "Ljava/lang/String;"}));

        // ------------------------------------------------------------------
        // UI-04 顶部频道管理
        // ------------------------------------------------------------------
        out.add(m("UI-04", "HomeTopTabInfo#getCanDelete", false,
                "com.netease.cloudmusic.discovery.view.module.hometoptab.model.HomeTopTabInfo",
                "getCanDelete", "Ljava/lang/Boolean;", NONE));
        out.add(m("UI-04", "HomeTopTabInfo#getCanDrag", false,
                "com.netease.cloudmusic.discovery.view.module.hometoptab.model.HomeTopTabInfo",
                "getCanDrag", "Ljava/lang/Boolean;", NONE));
        out.add(m("UI-04", "hometoptab.TabExtra#getNowChannelItems", false,
                "com.netease.cloudmusic.discovery.view.module.hometoptab.model.TabExtra",
                "getNowChannelItems", "Ljava/util/List;", NONE));
        out.add(m("UI-04", "newframework.TabExtra#getNowChannelItems", false,
                "com.netease.cloudmusic.discovery.view.newframework.model.TabExtra",
                "getNowChannelItems", "Ljava/util/List;", NONE));
        out.add(m("UI-04", "hometoptab.UserSettingDTO#getCurrentRes", false,
                "com.netease.cloudmusic.discovery.view.module.hometoptab.model.UserSettingDTO",
                "getCurrentRes", "Ljava/util/List;", NONE));
        out.add(m("UI-04", "hometoptab.v2.UserSettingDTO#getCurrentRes", false,
                "com.netease.cloudmusic.discovery.view.module.hometoptab.v2.UserSettingDTO",
                "getCurrentRes", "Ljava/util/List;", NONE));
        out.add(m("UI-04", "BaseArkV3Fragment#getTabDataList", true,
                "com.netease.cloudmusic.discovery.view.newframework.BaseArkV3Fragment",
                "getTabDataList", "Ljava/util/List;", NONE));
        out.add(m("UI-04", "BaseArkV3Fragment#hc", false,
                "com.netease.cloudmusic.discovery.view.newframework.BaseArkV3Fragment",
                "hc", "V",
                new String[]{
                        "Ljava/util/List;",
                        "Landroidx/viewpager2/widget/ViewPager2;",
                        "Lcom/netease/cloudmusic/discovery/view/widget/"
                                + "DiscoveryCustomThemeRightIconTabLayout;"
                }));
        out.add(m("UI-04", "BaseArkV3Fragment#Tc", false,
                "com.netease.cloudmusic.discovery.view.newframework.BaseArkV3Fragment",
                "Tc", "V", new String[]{"Ljava/util/List;"}));
        out.add(m("UI-04", "BaseArkV3Fragment#Lc", false,
                "com.netease.cloudmusic.discovery.view.newframework.BaseArkV3Fragment",
                "Lc", "V", new String[]{"Ljava/util/List;"}));
        out.add(m("UI-04", "dr.h#T0", true,
                "dr.h", "T0", "V", new String[]{"Ljava/util/List;"},
                DexKitAnchors.DR_H));
        out.add(m("UI-04", "MusicTabLayout#addTab(k,int,boolean)", false,
                "com.netease.cloudmusic.theme.ui.tab.MusicTabLayout",
                "addTab", "V",
                new String[]{"Lcom/netease/cloudmusic/theme/ui/tab/MusicTabLayout$k;", "I", "Z"}));

        // ------------------------------------------------------------------
        // UI-05 底部导航精简
        // ------------------------------------------------------------------
        out.add(m("UI-05", "SettingActivity#T8", false,
                "com.netease.cloudmusic.music.biz.setting.activity.SettingActivity",
                "T8", "Z", NONE));
        out.add(m("UI-05", "SettingActivity#U8", false,
                "com.netease.cloudmusic.music.biz.setting.activity.SettingActivity",
                "U8", "Z", NONE));
        out.add(m("UI-05", "th0.h#h", true,
                "th0.h", "h", "Ljava/util/List;", NONE, DexKitAnchors.TH0_H));
        out.add(m("UI-05", "rh0.c#c", false,
                "rh0.c", "c", "Ljava/util/List;",
                new String[]{
                        "Z",
                        "Ljava/util/List;",
                        "Lcom/netease/cloudmusic/module/strategy/meta/MainTabContext;"
                },
                DexKitAnchors.RH0_C));

        // ------------------------------------------------------------------
        // UI-02 跳过启动延迟
        // ------------------------------------------------------------------
        out.add(m("UI-02", "StartTimeHandler#isNeedDelayStart", true,
                "com.netease.cloudmusic.module.startup.StartTimeHandler",
                "isNeedDelayStart", "Z", NONE));
        out.add(m("UI-02", "StartTimeHandler#getStartAppRedPageDelayTime", false,
                "com.netease.cloudmusic.module.startup.StartTimeHandler",
                "getStartAppRedPageDelayTime", "J",
                new String[]{"Ljava/lang/String;"}));
        out.add(m("UI-02", "StartTimeHandler#checkDelayDoAction", true,
                "com.netease.cloudmusic.module.startup.StartTimeHandler",
                "checkDelayDoAction", "V",
                new String[]{"Ljava/lang/Runnable;", "Ljava/lang/String;"}));

        // ------------------------------------------------------------------
        // UI-07 引导与气泡屏蔽
        // ------------------------------------------------------------------
        out.add(m("UI-07", "PushGuideHintView#maybeDisplayHint", true,
                "com.netease.cloudmusic.main.hint.push.PushGuideHintView",
                "maybeDisplayHint", "V", HINT_ARGS));
        out.add(m("UI-07", "ScoreHintDialogView#maybeDisplayHint", false,
                "com.netease.cloudmusic.module.hint.view.ScoreHintDialogView",
                "maybeDisplayHint", "V", HINT_ARGS));
        out.add(m("UI-07", "HintVipNormalRenewalDialogueView#maybeDisplayHint", false,
                "com.netease.cloudmusic.discovery.view.module.music.v3.vipcard."
                        + "HintVipNormalRenewalDialogueView",
                "maybeDisplayHint", "V", HINT_ARGS));
        out.add(m("UI-07", "LiveLocalPushHintView#maybeDisplayHint", false,
                "com.netease.cloudmusic.module.hint.view.LiveLocalPushHintView",
                "maybeDisplayHint", "V", HINT_ARGS));
        out.add(m("UI-07", "BottomTabGuideBubbleNativeView#maybeDisplayHint", false,
                "com.netease.cloudmusic.main.hint.BottomTabGuideBubbleNativeView",
                "maybeDisplayHint", "V", HINT_ARGS));
        out.add(m("UI-07", "nr2.c#checkAndShowYouthDialog", false,
                "nr2.c", "checkAndShowYouthDialog", "Z",
                new String[]{"Landroid/content/Context;"}));

        // ------------------------------------------------------------------
        // UI-08 禁止自动播放
        // ------------------------------------------------------------------
        out.add(m("UI-08", "SettingActivity#V8", true,
                "com.netease.cloudmusic.music.biz.setting.activity.SettingActivity",
                "V8", "Z", NONE));
        out.add(m("UI-08", "jn#m0", false,
                "com.netease.cloudmusic.utils.jn", "m0", "Z", NONE,
                DexKitAnchors.JN));
        out.add(m("UI-08", "tt0.d#a", false,
                "tt0.d", "a", "Z", NONE, DexKitAnchors.TT0_D));
        out.add(m("UI-08", "tt0.d#d", false,
                "tt0.d", "d", "Z", NONE, DexKitAnchors.TT0_D));
        out.add(m("UI-08", "vt0.r#j", false,
                "vt0.r", "j", "Z", NONE, DexKitAnchors.VT0_R));
        out.add(m("UI-08", "lv0.j#e", false,
                "lv0.j", "e",
                "Lcom/netease/cloudmusic/module/strategy/meta/AutoPlayConfig;",
                NONE, DexKitAnchors.LV0_J));

        // ------------------------------------------------------------------
        // EX-01 强制深色模式
        // ------------------------------------------------------------------
        out.add(m("EX-01", "ResourceRouter#isNightTheme", true,
                "com.netease.cloudmusic.theme.core.ResourceRouter",
                "isNightTheme", "Z", NONE));
        out.add(m("EX-01", "ResourceRouter#isUiModeNightYes", false,
                "com.netease.cloudmusic.theme.core.ResourceRouter",
                "isUiModeNightYes", "Z", NONE));

        // ------------------------------------------------------------------
        // UI-09 关闭头像挂件
        // ------------------------------------------------------------------
        out.add(m("UI-09", "jn#L", true,
                "com.netease.cloudmusic.utils.jn", "L", "Z", NONE,
                DexKitAnchors.JN));

        // ------------------------------------------------------------------
        // UI-10 播放页提示治理
        // ------------------------------------------------------------------
        out.add(m("UI-10", "PlayerCommentPopView#renderCommentListAndShow", true,
                "com.netease.cloudmusic.ui.hint.PlayerCommentPopView",
                "renderCommentListAndShow", "V",
                new String[]{"Ljava/util/List;"}));
        out.add(m("UI-10", "PlayerRecommendSongHintView#maybeDisplayHint", false,
                "com.netease.cloudmusic.module.hint.view.PlayerRecommendSongHintView",
                "maybeDisplayHint", "V", HINT_ARGS));
        out.add(m("UI-10", "PlayerJankDialogHelper#showPlayJankNetErrorDialog", false,
                "com.netease.cloudmusic.ui.PlayerJankDialogHelper",
                "showPlayJankNetErrorDialog", "V",
                new String[]{"Landroid/content/Context;"}));
        out.add(m("UI-10", "PlayerJankDialogHelper#showNMCacheIOErrorDialog", false,
                "com.netease.cloudmusic.ui.PlayerJankDialogHelper",
                "showNMCacheIOErrorDialog", "V",
                new String[]{"Landroid/content/Context;"}));
        out.add(m("UI-10", "PlayerIncentiveAdView#maybeDisplayHint", false,
                "com.netease.cloudmusic.module.hint.view.PlayerIncentiveAdView",
                "maybeDisplayHint", "V", HINT_ARGS));

        // ------------------------------------------------------------------
        // UI-11 搜索热词轮播
        // ------------------------------------------------------------------
        out.add(m("UI-11", "SearchGuide#isValid", true,
                "com.netease.cloudmusic.meta.SearchGuide",
                "isValid", "Z", NONE));

        // ------------------------------------------------------------------
        // UI-12 播客 / AI DJ 引导
        // ------------------------------------------------------------------
        out.add(m("UI-12", "PodcastBottomGuideManager#isTimeToShowFMAutoPlayGuide", true,
                "com.netease.cloudmusic.music.biz.voice.home.PodcastBottomGuideManager",
                "isTimeToShowFMAutoPlayGuide", "Z", NONE));
        out.add(m("UI-12", "PodcastBottomGuideManager#isTimeToShowVoiceBookGuide", false,
                "com.netease.cloudmusic.music.biz.voice.home.PodcastBottomGuideManager",
                "isTimeToShowVoiceBookGuide", "Z", NONE));
        out.add(m("UI-12", "PlayerAIDJSwitchGuideView#showGuide", false,
                "com.netease.cloudmusic.ui.hint.aidj.PlayerAIDJSwitchGuideView",
                "showGuide", "V",
                new String[]{"Ljava/lang/String;", "Ljava/lang/String;", "J"}));
        out.add(m("UI-12", "FMAIDJSwitchView#maybeDisplayHint", false,
                "com.netease.cloudmusic.hint.aidj.FMAIDJSwitchView",
                "maybeDisplayHint", "V", HINT_ARGS));

        // ------------------------------------------------------------------
        // UI-13 小组件 / 推送引导
        // ------------------------------------------------------------------
        out.add(m("UI-13", "WidgetAddGuideDialogHelper$a#a", true,
                "com.netease.cloudmusic.music.biz.appwidgets.view."
                        + "WidgetAddGuideDialogHelper$a",
                "a", "Z", NONE));
        out.add(m("UI-13", "PushGuideCenterDialogFragment#onCreateView", false,
                "com.netease.cloudmusic.main.hint.push.PushGuideCenterDialogFragment",
                "onCreateView", "Landroid/view/View;",
                new String[]{
                        "Landroid/view/LayoutInflater;",
                        "Landroid/view/ViewGroup;",
                        "Landroid/os/Bundle;"
                }));
        out.add(m("UI-13", "PushGuideCenterDialogFragment#onActivityCreated", false,
                "com.netease.cloudmusic.main.hint.push.PushGuideCenterDialogFragment",
                "onActivityCreated", "V",
                new String[]{"Landroid/os/Bundle;"}));
        out.add(m("UI-13", "PushGuideBottomSheetFragment#onCreateView", false,
                "com.netease.cloudmusic.main.hint.push.PushGuideBottomSheetFragment",
                "onCreateView", "Landroid/view/View;",
                new String[]{
                        "Landroid/view/LayoutInflater;",
                        "Landroid/view/ViewGroup;",
                        "Landroid/os/Bundle;"
                }));
        out.add(m("UI-13", "PushGuideBottomSheetFragment#onActivityCreated", false,
                "com.netease.cloudmusic.main.hint.push.PushGuideBottomSheetFragment",
                "onActivityCreated", "V",
                new String[]{"Landroid/os/Bundle;"}));

        // ------------------------------------------------------------------
        // UI-14 歌词页推广
        // ------------------------------------------------------------------
        out.add(m("UI-14", "LyricsBottomHintView#maybeDisplayHint", true,
                "com.netease.cloudmusic.module.hint.view.LyricsBottomHintView",
                "maybeDisplayHint", "V", HINT_ARGS));
        out.add(m("UI-14", "LyricViewContainer#checkAndShowLrcOptionHint", false,
                "com.netease.cloudmusic.ui.LyricViewContainer",
                "checkAndShowLrcOptionHint", "V", NONE));
        out.add(m("UI-14", "LyricVideoTipsDialog#oc", false,
                "com.netease.cloudmusic.fragment.LyricVideoTipsDialog",
                "oc", "V",
                new String[]{
                        "Landroidx/fragment/app/FragmentActivity;",
                        "Lcom/netease/cloudmusic/meta/LyricDialogData;"
                }));

        // ------------------------------------------------------------------
        // EN-01 我的页自定义背景（纯本地渲染，不碰会员字段）
        // ------------------------------------------------------------------
        // 9.6.08 的「我的」Tab 是 RN 页面；原生 manager 只覆盖旧布局。
        out.add(m("EN-01", "RnMineFragment#onResume", true,
                "com.netease.cloudmusic.fragment.RnMineFragment",
                "onResume", "V", NONE));
        out.add(m("EN-01", "MyMusicProfileHeaderBgViewHolderManager#e", true,
                "com.netease.cloudmusic.vh.MyMusicProfileHeaderBgViewHolderManager",
                "e", "V",
                new String[]{"Lcom/netease/cloudmusic/meta/virtual/UserBackgroundInfo;"}));
        out.add(m("EN-01", "MyMusicProfileHeaderBgViewHolderManager#onResume", false,
                "com.netease.cloudmusic.vh.MyMusicProfileHeaderBgViewHolderManager",
                "onResume", "V", NONE));

        // ------------------------------------------------------------------
        // UI-15 云村 / 直播推广
        // ------------------------------------------------------------------
        out.add(m("UI-15", "LivePromotion#listFromJson(JSONObject)", true,
                "com.netease.play.livepage.promotion.LivePromotion",
                "listFromJson", "Ljava/util/List;",
                new String[]{"Lorg/json/JSONObject;"}));
        out.add(m("UI-15", "LivePromotion#listFromJson(JSONArray)", false,
                "com.netease.play.livepage.promotion.LivePromotion",
                "listFromJson", "Ljava/util/List;",
                new String[]{"Lorg/json/JSONArray;"}));
        out.add(m("UI-15", "NotesPublishGuideHint#maybeDisplayHint", false,
                "com.netease.cloudmusic.music.biz.mlog.consume.social.homepage.ui."
                        + "NotesPublishGuideHint",
                "maybeDisplayHint", "V", HINT_ARGS));
        out.add(m("UI-15", "NotesSearchGuideHint#maybeDisplayHint", false,
                "com.netease.cloudmusic.music.biz.mlog.consume.social.homepage.ui."
                        + "NotesSearchGuideHint",
                "maybeDisplayHint", "V", HINT_ARGS));
        out.add(m("UI-15", "CommentLiveViewHolder#onBindViewHolder", false,
                "com.netease.cloudmusic.module.comment.CommentLiveViewHolder",
                "onBindViewHolder", "V",
                new String[]{"Ljava/lang/Object;", "I", "I"}));

        return out;
    }

    private static MountSpec m(String featureId, String logicalName, boolean critical,
                               String className, String methodName, String returnType,
                               String[] paramTypes, String... anchors) {
        return MountSpec.method(featureId, logicalName, critical, className,
                methodName, returnType, paramTypes, anchors);
    }
}
