package com.cloudmusictool.app.hooks;

import android.util.Log;

import com.cloudmusictool.app.Config;
import com.cloudmusictool.app.Feature;
import com.cloudmusictool.app.HookState;

import io.github.libxposed.api.XposedModule;

/**
 * 统一在 onPackageReady（已确认包名 com.netease.cloudmusic）之后调用。
 * 单个功能的失败不得影响其余功能，也不得让模块加载失败。
 */
public final class Hooks {

    private static final String TAG = "CloudMusicTool";

    private Hooks() {
    }

    public static void installAll(XposedModule module, ClassLoader cl, Config config) {
        if (config == null) {
            module.log(Log.ERROR, TAG, "config is null");
            return;
        }
        if (!config.anyEnabled()) {
            module.log(Log.INFO, TAG, "all features disabled, only ABTest observer");
            installAbTest(module, cl, config);
            HookState.flush();
            return;
        }
        for (Feature f : Feature.values()) {
            if (f.blocked()) {
                module.log(Log.INFO, TAG, f.id + " blocked: " + f.blockedReason);
                continue;
            }
            if (!config.enabled(f)) {
                module.log(Log.INFO, TAG, f.id + " disabled");
                continue;
            }
            HookState.enable(f);
            installOne(module, cl, f, config);
            HookState.finish(f);
        }
        HookState.flush();
        // ABTest 不是 Feature 开关：始终安装只读观测；forceMap 为空时不改任何返回值。
        installAbTest(module, cl, config);
    }

    private static void installAbTest(XposedModule module, ClassLoader cl, Config config) {
        try {
            AbTestHooks.install(module, cl, config);
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "ABTEST install threw", t);
        }
    }

    /** :play 进程只安装音频贴片拦截；不安装 AbTestHooks，也不写 experiments。 */
    public static void installPlayOnly(XposedModule module, ClassLoader cl, Config config) {
        Feature f = Feature.AUDIO_CLIP_AD;
        if (!config.enabled(f)) {
            module.log(Log.INFO, TAG, f.id + " disabled in :play");
            return;
        }
        HookState.enable(f);
        installOne(module, cl, f, config);
        HookState.finish(f);
        HookState.flush();
    }

    private static void installOne(XposedModule module, ClassLoader cl, Feature f, Config config) {
        try {
            switch (f) {
                case SPLASH_AD:
                    SplashAdHooks.install(module, cl);
                    break;
                case LAUNCH_DIALOG:
                    LaunchDialogHooks.install(module, cl);
                    break;
                case UPDATE_DIALOG:
                    UpdateHooks.install(module, cl);
                    break;
                case AD_REPORT_JUMP:
                    AdReportHooks.install(module, cl);
                    break;
                case SEARCH_AD:
                    SearchAdHooks.install(module, cl, f);
                    break;
                case AD_PRELOAD:
                    AdPreloadHooks.install(module, cl, f);
                    break;
                case FEED_AD:
                    FeedAdHooks.install(module, cl);
                    break;
                case COMMENT_AD:
                    CommentAdHooks.install(module, cl);
                    break;
                case PLAYER_AD:
                    PlayerAdHooks.install(module, cl);
                    break;
                case AUDIO_CLIP_AD:
                    AudioAdHooks.install(module, cl);
                    break;
                case STARTUP_DELAY:
                    StartupDelayHooks.install(module, cl);
                    break;
                case HOME_BLOCKS:
                    HomeBlockHooks.install(module, cl, config);
                    break;
                case TOP_CHANNELS:
                    TopTabHooks.install(module, cl, config);
                    break;
                case PLAYER_HINTS:
                    PlayerHintHooks.install(module, cl, f);
                    break;
                case SEARCH_GUIDE:
                    SearchGuideHooks.install(module, cl, f);
                    break;
                case PODCAST_GUIDE:
                    PodcastGuideHooks.install(module, cl, f);
                    break;
                case WIDGET_PUSH_GUIDE:
                    WidgetPushGuideHooks.install(module, cl, f);
                    break;
                case LYRICS_HINT:
                    LyricsHintHooks.install(module, cl, f);
                    break;
                case COMMUNITY_LIVE:
                    CommunityLiveHooks.install(module, cl, f);
                    break;
                case MINE_BACKGROUND:
                    MineBackgroundHooks.install(module, cl, f);
                    break;
                case BOTTOM_NAV:
                    BottomTabHooks.install(module, cl, config);
                    break;
                case HINTS:
                    HintHooks.install(module, cl);
                    break;
                case AUTOPLAY:
                    AutoPlayHooks.install(module, cl);
                    break;
                case DARK_MODE:
                    ThemeHooks.install(module, cl);
                    break;
                case PENDANT:
                    PendantHooks.install(module, cl);
                    break;
                case OAID_REPORT:
                    OaidHooks.install(module, cl);
                    break;
                case LOG_TASKS:
                    LogTaskHooks.install(module, cl);
                    break;
                case STARTUP_BLACKLIST:
                    StartupBlacklistHooks.install(module, cl, config);
                    break;
                case APM:
                    ApmHooks.install(module, cl);
                    break;
                case STATS_OBSERVE:
                    StatisticHooks.install(module, cl);
                    break;
                case CLIPBOARD:
                    ClipboardHooks.install(module, cl, f);
                    break;
                case STARTUP_ORCHESTRATION:
                    StartupOrchestrationHooks.install(module, cl, f);
                    break;
                case DRAWER:
                    // UI-04 当前 blocked，installAll 不会走到这里；保留分支保证 switch 与 Feature 一一对应。
                    module.log(Log.INFO, TAG, f.id + " blocked: " + f.blockedReason);
                    break;
                default:
                    module.log(Log.INFO, TAG, f.id + " pending implementation in later phase");
                    break;
            }
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, f.id + " install threw", t);
            HookState.note(f, "安装异常");
        }
    }
}
