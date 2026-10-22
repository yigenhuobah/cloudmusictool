package com.cloudmusictool.app.profile;

import com.cloudmusictool.app.dexkit.DexKitAnchors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 广告（AD-01~AD-09）、界面（UI-01）、隐私（PV-01~PV-06）与实验（EX-02）
 * 的挂载点分片，另含 ABTest 只读观测入口。
 *
 * 全部坐标均用 dexsql 在网易云 9.6.08（versionCode 9006008）上核对：
 *   SELECT class_descriptor, name, descriptor FROM methods WHERE ...
 * anchors 只放真实 dex 字符串字面量（method_strings 已验证）。
 *
 * critical 语义：缺失即视为该功能降级；每个 Feature 至少一个 critical。
 * 只描述坐标，不含 hook 逻辑；hook 文件仍保留原有硬编码路径作为回退。
 */
public final class ProfileAdPv {

    /** ABTest 不是 Feature 开关，但同样需要版本化坐标。 */
    public static final String ABTEST_FEATURE = "ABTEST";

    private static final String AD_MANAGER = "com.netease.cloudmusic.module.ad.LoadingAdManager";
    private static final String AD_ACTIVITY = "com.netease.cloudmusic.activity.LoadingAdActivity";
    private static final String AD_FRAGMENT = "com.netease.cloudmusic.fragment.LoadingAdFragment";
    private static final String AD_SHAKE = "com.netease.cloudmusic.module.ad.boot.LoadingAdShakeHelper";
    private static final String Q60_W0 = "q60.w0";
    private static final String LAUNCH_DIALOG = "com.netease.cloudmusic.ui.LaunchDialog";
    private static final String LAUNCH_DIALOG_DATA = "Lcom/netease/cloudmusic/ui/LaunchDialog$Data;";
    private static final String UPDATE_CONFIG = "com.netease.cloudmusic.appupdate.CMUpdateConfig";
    private static final String UPDATE_STATE = "com.netease.cloudmusic.appupdate.d";
    private static final String UPDATE_INFO = "com.netease.cloudmusic.appupdate.meta.UpdateInfo";
    private static final String FW0_E = "fw0.e";
    private static final String BANNER = "com.netease.cloudmusic.meta.Banner";
    private static final String COMMENT_ADAPTER = "com.netease.cloudmusic.adapter.CommentAdapter";
    private static final String SPECIAL_COMMENT = "com.netease.cloudmusic.meta.virtual.SpecialComment";
    private static final String COMMENT = "com.netease.cloudmusic.meta.Comment";
    private static final String COMMENT_VIP_ADAPTER = "com.netease.cloudmusic.adapter.CommentVipRcmdAdapter";
    private static final String PLAYER_AD_DELEGATE =
            "com.netease.cloudmusic.module.ad.playerjar.PlayerAdJarDelegateImpl";
    private static final String MUSIC_GIFT =
            "com.netease.cloudmusic.module.player.vinylfakelayer.musicgift.meta.MusicGiftModel";
    private static final String PLAYER_FULLSCREEN =
            "com.netease.cloudmusic.ui.ad.player.AdPlayerFullScreenHelper";
    private static final String AUDIO_CLIP = "com.netease.cloudmusic.module.ad.audioclip.AdAudioClipManager";
    private static final String PODCAST_AD_HELPER =
            "com.netease.cloudmusic.module.ad.podcast.AdPodcastPlayerPageHelper";
    private static final String PODCAST_VIDEO_AD =
            "com.netease.cloudmusic.module.ad.podcast.PodcastVideoAdHelperImpl";
    private static final String AD_REPORT_TASK = "com.netease.cloudmusic.module.ad.AdCloudReportTask";
    private static final String START_APP_HELPER_B = "com.netease.cloudmusic.ad.StartAppHelper$b";
    private static final String DEEPLINK_PRE =
            "com.netease.cloudmusic.process.task.app.attach.AdExposureDeeplinkPreRequestTask";
    private static final String DEEPLINK_TASK =
            "com.netease.cloudmusic.process.task.app.create.AdExposureDeeplinkTask";
    private static final String SEARCH_AD_MANAGER =
            "com.netease.cloudmusic.music.biz.search.start.rnview.RNSearchAdViewManger";
    private static final String SEARCH_AD_LARGE =
            "com.netease.cloudmusic.music.biz.search.complex.viewholder.ad.SearchAdItemLargeView";
    private static final String SEARCH_AD_MEDIUM =
            "com.netease.cloudmusic.music.biz.search.complex.viewholder.ad.SearchAdItemMediumView";
    private static final String AD_COLD_BOOT =
            "com.netease.cloudmusic.process.task.main.create.AdColdBootEntranceTask";
    private static final String PODCAST_REWARD_AD =
            "com.netease.cloudmusic.process.task.main.createproxy.PodcastRewardAdTask";
    private static final String AD_PRELOAD_RECOMMEND =
            "com.netease.cloudmusic.process.task.main.create.APreloadRecommendViewTask";

    private static final String OAID_MANAGER = "com.netease.cloudmusic.oaid.MusicOaidUploadManager";
    private static final String OAID_TASK = "com.netease.cloudmusic.process.task.app.create.AdOaidUploadTask";
    private static final String LOG_TO_FILE_TASK =
            "com.netease.cloudmusic.process.task.app.create.NeedLogToFileTask";
    private static final String NB_LOG_TASK = "com.netease.cloudmusic.process.task.app.create.NBLogTask";
    private static final String STARTUP_TASK = "com.netease.cloudmusic.core.startup.StartupTask";
    private static final String STATISTIC_IMPL = "com.netease.cloudmusic.core.statistic.StatisticImpl";
    private static final String LOG_MONITOR =
            "com.netease.cloudmusic.statistic.monitor.LogMonitorConfigManager";
    private static final String CLIPBOARD_UTILS = "com.netease.cloudmusic.utils.h2";

    private static final String AB_MANAGER = "com.netease.cloudmusic.abtest2.ABTestManagerImpl";
    private static final String AB_CONFIG = "com.netease.cloudmusic.abtest2.ABTestConfig";
    private static final String AB_LIBRA_CONFIG = "com.netease.cloudmusic.abtest2.LibraABTestConfig";
    private static final String AB_MODEL = "com.netease.cloudmusic.abtest2.b";
    private static final String AB_STARTUP = "com.netease.cloudmusic.module.startup.StartupABManager";

    private static final String[] NONE = new String[0];

    private ProfileAdPv() {
    }

    public static List<MountSpec> mounts() {
        List<MountSpec> out = new ArrayList<>(80);
        addSplashAd(out);
        addLaunchDialog(out);
        addUpdate(out);
        addFeedAd(out);
        addCommentAd(out);
        addPlayerAd(out);
        addAudioAd(out);
        addAdReport(out);
        addSearchAd(out);
        addAdPreload(out);
        addPrivacy(out);
        addAbTest(out);
        return Collections.unmodifiableList(out);
    }

    // AD-01 开屏广告：判定 -> 数据源 -> 落地页/摇晃兜底。
    private static void addSplashAd(List<MountSpec> out) {
        out.add(MountSpec.method("AD-01", "LoadingAdManager#E", true,
                AD_MANAGER, "E", "Z", new String[]{"Lcom/netease/cloudmusic/module/ad/meta/AdInfo;"},
                DexKitAnchors.LOADING_AD_MANAGER));
        out.add(MountSpec.method("AD-01", "LoadingAdManager#G", false,
                AD_MANAGER, "G", "V", new String[]{"Landroid/app/Activity;"},
                DexKitAnchors.LOADING_AD_MANAGER));
        out.add(MountSpec.method("AD-01", "LoadingAdManager#o", true,
                AD_MANAGER, "o", "Ljava/util/ArrayList;", new String[]{"Z"},
                DexKitAnchors.LOADING_AD_MANAGER));
        out.add(MountSpec.method("AD-01", "LoadingAdActivity#onCreate", false,
                AD_ACTIVITY, "onCreate", "V", new String[]{"Landroid/os/Bundle;"}));
        out.add(MountSpec.method("AD-01", "LoadingAdFragment#onViewCreated", false,
                AD_FRAGMENT, "onViewCreated", "V",
                new String[]{"Landroid/view/View;", "Landroid/os/Bundle;"}));
        out.add(MountSpec.method("AD-01", "LoadingAdShakeHelper#onSensorChanged", false,
                AD_SHAKE, "onSensorChanged", "V", new String[]{"Landroid/hardware/SensorEvent;"}));
        out.add(MountSpec.method("AD-01", "q60.w0#g", false,
                Q60_W0, "g", "Z", NONE, DexKitAnchors.Q60_W0));
    }

    // AD-02 运营弹窗：只列营销入口，登录弹窗不在坐标内。
    private static void addLaunchDialog(List<MountSpec> out) {
        out.add(MountSpec.method("AD-02", "LaunchDialog#showDialog", true,
                LAUNCH_DIALOG, "showDialog", "V",
                new String[]{"Landroid/app/Activity;", LAUNCH_DIALOG_DATA}));
        // 第二个重载：showDialog(LaunchDialog, Context, int)，LaunchDialogHooks 按方法名一并安装。
        out.add(MountSpec.method("AD-02", "LaunchDialog#showDialog(3)", false,
                LAUNCH_DIALOG, "showDialog", "V",
                new String[]{"Lcom/netease/cloudmusic/ui/LaunchDialog;",
                        "Landroid/content/Context;", "I"}));
        out.add(MountSpec.method("AD-02", "LaunchDialog#showMultiModulePopUpDialog", true,
                LAUNCH_DIALOG, "showMultiModulePopUpDialog", "V",
                new String[]{"Landroid/app/Activity;", LAUNCH_DIALOG_DATA}));
        out.add(MountSpec.method("AD-02", "LaunchDialog#showSinglePicDialog", false,
                LAUNCH_DIALOG, "showSinglePicDialog", "V",
                new String[]{"Landroid/app/Activity;", LAUNCH_DIALOG_DATA}));
        out.add(MountSpec.method("AD-02", "LaunchDialog#showPictureDialog", false,
                LAUNCH_DIALOG, "showPictureDialog", "V",
                new String[]{"Landroid/app/Activity;", LAUNCH_DIALOG_DATA,
                        "Landroid/content/DialogInterface$OnDismissListener;"}));
        out.add(MountSpec.method("AD-02", "LaunchDialog#showCompactDialog", false,
                LAUNCH_DIALOG, "showCompactDialog", "V",
                new String[]{"Landroid/app/Activity;", LAUNCH_DIALOG_DATA}));
    }

    // UI-01 更新弹窗与强制更新。
    private static void addUpdate(List<MountSpec> out) {
        out.add(MountSpec.method("UI-01", "CMUpdateConfig#hasNewVersion", true,
                UPDATE_CONFIG, "hasNewVersion", "Z", NONE));
        out.add(MountSpec.method("UI-01", "appupdate.d#B", true,
                UPDATE_STATE, "B", "Z", NONE, DexKitAnchors.APPUPDATE_D));
        out.add(MountSpec.method("UI-01", "appupdate.d#I", false,
                UPDATE_STATE, "I", "Z", NONE, DexKitAnchors.APPUPDATE_D));
        out.add(MountSpec.method("UI-01", "UpdateInfo#getNeedPopUp", false,
                UPDATE_INFO, "getNeedPopUp", "Ljava/lang/Boolean;", NONE));
        out.add(MountSpec.method("UI-01", "UpdateInfo#getForceUpdate", true,
                UPDATE_INFO, "getForceUpdate", "Ljava/lang/Boolean;", NONE));
        out.add(MountSpec.method("UI-01", "fw0.e#q", false,
                FW0_E, "q", "V",
                new String[]{"Landroid/content/Context;", "Z", "Z"}, DexKitAnchors.FW0_E));
    }

    // AD-06 首页/发现页信息流广告。
    private static void addFeedAd(List<MountSpec> out) {
        out.add(MountSpec.method("AD-06", "Banner#isAdBanner", true,
                BANNER, "isAdBanner", "Z", NONE));
    }

    // AD-07 评论区广告与推广组件。
    private static void addCommentAd(List<MountSpec> out) {
        out.add(MountSpec.method("AD-07", "CommentAdapter#y1", true,
                COMMENT_ADAPTER, "y1", "V",
                new String[]{"Lcom/netease/cloudmusic/meta/virtual/CommentListEntry;"}));
        out.add(MountSpec.method("AD-07", "SpecialComment#getGeneralBanner", false,
                SPECIAL_COMMENT, "getGeneralBanner",
                "Lcom/netease/cloudmusic/meta/virtual/CommentGeneralBanner;", NONE));
        out.add(MountSpec.method("AD-07", "SpecialComment#getLiveRcmdList", false,
                SPECIAL_COMMENT, "getLiveRcmdList", "Ljava/util/ArrayList;", NONE));
        out.add(MountSpec.method("AD-07", "SpecialComment#getVipRcmdList", false,
                SPECIAL_COMMENT, "getVipRcmdList", "Ljava/util/ArrayList;", NONE));
        out.add(MountSpec.method("AD-07", "SpecialComment#getConcertInfo", false,
                SPECIAL_COMMENT, "getConcertInfo",
                "Lcom/netease/cloudmusic/meta/virtual/ConcertInfo;", NONE));
        out.add(MountSpec.method("AD-07", "Comment#isTagValid", true,
                COMMENT, "isTagValid", "Z", NONE));
        out.add(MountSpec.method("AD-07", "Comment#getBottomTags", false,
                COMMENT, "getBottomTags", "Ljava/util/List;", NONE));
        out.add(MountSpec.method("AD-07", "CommentVipRcmdAdapter#getItemCount", false,
                COMMENT_VIP_ADAPTER, "getItemCount", "I", NONE));
    }

    // AD-08 播放页广告与商业挂件。
    private static void addPlayerAd(List<MountSpec> out) {
        out.add(MountSpec.method("AD-08", "PlayerAdJarDelegateImpl#k", true,
                PLAYER_AD_DELEGATE, "k", "V",
                new String[]{"Lcom/netease/cloudmusic/meta/Ad;",
                        "Landroid/view/ViewGroup;", "Landroid/view/ViewGroup;"}));
        out.add(MountSpec.method("AD-08", "PlayerAdJarDelegateImpl#q", true,
                PLAYER_AD_DELEGATE, "q", "V",
                new String[]{"Lcom/netease/cloudmusic/meta/Ad;"}));
        out.add(MountSpec.method("AD-08", "MusicGiftModel#isValid", false,
                MUSIC_GIFT, "isValid", "Z", NONE));
        out.add(MountSpec.method("AD-08", "AdPlayerFullScreenHelper#checkNeedExpose", false,
                PLAYER_FULLSCREEN, "checkNeedExpose", "Z",
                new String[]{"Lcom/netease/cloudmusic/meta/Ad;"}));
    }

    // AD-09 音频贴片与播客广告。
    private static void addAudioAd(List<MountSpec> out) {
        out.add(MountSpec.method("AD-09", "AdAudioClipManager#d", true,
                AUDIO_CLIP, "d", "Ljava/lang/Object;",
                new String[]{"Ljava/lang/String;", "I", "J",
                        "Lkotlin/jvm/functions/Function1;", "Lkotlin/coroutines/Continuation;"}));
        out.add(MountSpec.method("AD-09", "AdAudioClipManager#c", true,
                AUDIO_CLIP, "c", "Ljava/lang/Object;",
                new String[]{"Lkotlinx/coroutines/n0;",
                        "Lcom/netease/cloudmusic/meta/ad/audioclip/AudioClipData;", "J",
                        "Lkotlin/jvm/functions/Function1;", "Lkotlin/coroutines/Continuation;"}));
        out.add(MountSpec.method("AD-09", "AdPodcastPlayerPageHelper#h", false,
                PODCAST_AD_HELPER, "h", "V",
                new String[]{"Lcom/netease/cloudmusic/meta/Ad;"}));
        out.add(MountSpec.method("AD-09", "PodcastVideoAdHelperImpl#z", false,
                PODCAST_VIDEO_AD, "z", "V",
                new String[]{"J", "J", "Lcom/netease/cloudmusic/meta/Ad;",
                        "Landroid/view/ViewGroup;"}));
    }

    // AD-03 广告上报与外部应用静默跳转。
    private static void addAdReport(List<MountSpec> out) {
        out.add(MountSpec.method("AD-03", "AdCloudReportTask#execute", true,
                AD_REPORT_TASK, "execute", "Z", NONE));
        out.add(MountSpec.method("AD-03", "StartAppHelper$b#startApp", true,
                START_APP_HELPER_B, "startApp", "Z",
                new String[]{"Landroid/app/Activity;", "Landroid/content/Intent;",
                        "Lcom/netease/cloudmusic/ad/StartAppHelper$StartAppListener;", "Z"}));
        out.add(MountSpec.method("AD-03", "AdExposureDeeplinkPreRequestTask#init", false,
                DEEPLINK_PRE, "init", "V", NONE));
        out.add(MountSpec.method("AD-03", "AdExposureDeeplinkTask#init", false,
                DEEPLINK_TASK, "init", "V", NONE));
    }

    // AD-04 搜索广告净化。
    private static void addSearchAd(List<MountSpec> out) {
        out.add(MountSpec.method("AD-04", "RNSearchAdViewManger#newStyle", true,
                SEARCH_AD_MANAGER, "newStyle", "V",
                new String[]{"Lcom/netease/cloudmusic/music/biz/search/start/rnview/RNSearchAdView;",
                        "Lcom/netease/cloudmusic/meta/Ad;", "I", "Ljava/lang/String;"}));
        out.add(MountSpec.method("AD-04", "RNSearchAdViewManger#oldStyle", true,
                SEARCH_AD_MANAGER, "oldStyle", "V",
                new String[]{"Lcom/netease/cloudmusic/music/biz/search/start/rnview/RNSearchAdView;",
                        "Lcom/netease/cloudmusic/meta/Ad;", "I", "Ljava/lang/String;"}));
        out.add(MountSpec.method("AD-04", "RNSearchAdViewManger#hideAllViews", false,
                SEARCH_AD_MANAGER, "hideAllViews", "V", NONE));
        out.add(MountSpec.method("AD-04", "SearchAdItemLargeView#e", true,
                SEARCH_AD_LARGE, "e", "V",
                new String[]{"Lcom/netease/cloudmusic/meta/Ad;", "I",
                        "Lkotlin/jvm/functions/Function1;", "Lkotlin/jvm/functions/Function1;"}));
        out.add(MountSpec.method("AD-04", "SearchAdItemMediumView#e", true,
                SEARCH_AD_MEDIUM, "e", "V",
                new String[]{"Lcom/netease/cloudmusic/meta/Ad;", "I",
                        "Lkotlin/jvm/functions/Function1;", "Lkotlin/jvm/functions/Function1;"}));
    }

    // AD-05 冷启动广告预加载裁剪。
    private static void addAdPreload(List<MountSpec> out) {
        out.add(MountSpec.method("AD-05", "AdColdBootEntranceTask#init", true,
                AD_COLD_BOOT, "init", "V", NONE));
        out.add(MountSpec.method("AD-05", "PodcastRewardAdTask#init", false,
                PODCAST_REWARD_AD, "init", "V", NONE));
        out.add(MountSpec.method("AD-05", "APreloadRecommendViewTask#init", false,
                AD_PRELOAD_RECOMMEND, "init", "V", NONE));
    }

    // PV-01~PV-06 与 EX-02。
    private static void addPrivacy(List<MountSpec> out) {
        // PV-01 OAID
        out.add(MountSpec.method("PV-01", "MusicOaidUploadManager#o", true,
                OAID_MANAGER, "o", "V", new String[]{"Ljava/lang/String;"}));
        out.add(MountSpec.method("PV-01", "AdOaidUploadTask#init", false,
                OAID_TASK, "init", "V", NONE));

        // PV-02 日志写盘
        out.add(MountSpec.method("PV-02", "NeedLogToFileTask#init", true,
                LOG_TO_FILE_TASK, "init", "V", NONE));
        out.add(MountSpec.method("PV-02", "NBLogTask#init", true,
                NB_LOG_TASK, "init", "V", NONE));

        // PV-03 启动任务黑名单
        out.add(MountSpec.method("PV-03", "StartupTask#needExecute", true,
                STARTUP_TASK, "needExecute", "Z", NONE));

        // PV-04 APM
        out.add(MountSpec.method("PV-04", "StatisticImpl#logApm", true,
                STATISTIC_IMPL, "logApm", "V",
                new String[]{"Lcom/alibaba/fastjson/JSONObject;"}));
        out.add(MountSpec.method("PV-04", "StatisticImpl#forceApmUpload", true,
                STATISTIC_IMPL, "forceApmUpload", "V", NONE));

        // PV-05 统计观测
        out.add(MountSpec.method("PV-05", "StatisticImpl#log", true,
                STATISTIC_IMPL, "log", "V",
                new String[]{"Ljava/lang/String;", "[Ljava/lang/Object;"}));
        out.add(MountSpec.method("PV-05", "StatisticImpl#logJSON", true,
                STATISTIC_IMPL, "logJSON", "V",
                new String[]{"Ljava/lang/String;", "Lcom/alibaba/fastjson/JSONObject;"}));
        out.add(MountSpec.method("PV-05", "LogMonitorConfigManager#b", false,
                LOG_MONITOR, "b", "Z", NONE));
        out.add(MountSpec.method("PV-05", "LogMonitorConfigManager#c", false,
                LOG_MONITOR, "c", "Z", NONE));
        out.add(MountSpec.method("PV-05", "LogMonitorConfigManager#d", false,
                LOG_MONITOR, "d", "Z", NONE));
        out.add(MountSpec.method("PV-05", "LogMonitorConfigManager#e", false,
                LOG_MONITOR, "e", "Z", NONE));

        // PV-06 剪贴板
        out.add(MountSpec.method("PV-06", "utils.h2#d", true,
                CLIPBOARD_UTILS, "d", "Landroid/content/ClipData$Item;",
                new String[]{"Landroid/content/Context;"}));

        // EX-02 启动任务编排
        out.add(MountSpec.method("EX-02", "StartupTask#runOnUIThread", true,
                STARTUP_TASK, "runOnUIThread", "Z", NONE));
        out.add(MountSpec.method("EX-02", "StartupTask#priority", true,
                STARTUP_TASK, "priority", "I", NONE));
        out.add(MountSpec.method("EX-02", "StartupTask#runOnStage", false,
                STARTUP_TASK, "runOnStage", "I", NONE));
        out.add(MountSpec.method("EX-02", "StartupTask#degradeInit", false,
                STARTUP_TASK, "degradeInit", "V", NONE));
    }

    // ABTest 只读观测入口；ABTest 不受 Feature 开关控制，始终安装。
    private static void addAbTest(List<MountSpec> out) {
        out.add(MountSpec.method(ABTEST_FEATURE, "ABTestManagerImpl#checkExperiment", true,
                AB_MANAGER, "checkExperiment",
                "Lcom/netease/cloudmusic/abtest2/ABTestConfig;",
                new String[]{"Ljava/lang/String;", "Z"}));
        out.add(MountSpec.method(ABTEST_FEATURE, "ABTestConfig#getGroupKeys", true,
                AB_CONFIG, "getGroupKeys", "Ljava/lang/String;", NONE));
        out.add(MountSpec.method(ABTEST_FEATURE, "LibraABTestConfig#getGroupKeys", true,
                AB_LIBRA_CONFIG, "getGroupKeys", "Ljava/lang/String;", NONE));
        out.add(MountSpec.method(ABTEST_FEATURE, "abtest2.b#x", true,
                AB_MODEL, "x", "Lcom/netease/cloudmusic/abtest2/ABTestConfig;",
                new String[]{"Ljava/lang/String;", "Z"}));
        out.add(MountSpec.method(ABTEST_FEATURE, "StartupABManager#getGroup", true,
                AB_STARTUP, "getGroup", "Ljava/lang/String;",
                new String[]{"Ljava/lang/String;"}));
    }
}
