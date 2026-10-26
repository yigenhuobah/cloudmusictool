package com.cloudmusictool.app.hooks;

import android.util.Log;

import com.cloudmusictool.app.Config;
import com.cloudmusictool.app.Feature;
import com.cloudmusictool.app.HookState;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

/**
 * 统一在 onPackageReady（已确认包名 com.netease.cloudmusic）之后调用。
 * 单个功能的失败不得影响其余功能，也不得让模块加载失败。
 */
public final class Hooks {

    private static final String TAG = "CloudMusicTool";

    /** 单个 Feature 的安装动作；module/cl/config/feature 统一由 installOne 传入。 */
    @FunctionalInterface
    interface Installer {
        void install(XposedModule module, ClassLoader cl, Config config, Feature feature);
    }

    /**
     * Feature -> 安装器。只登记非 blocked Feature；blocked Feature 不在表内，
     * installOne 会按原 switch 的行为打印 blocked 日志且不安装。
     *
     * 安装顺序仍由 installAll 遍历 Feature.values() 决定，与本表迭代顺序无关。
     */
    static final EnumMap<Feature, Installer> INSTALLERS = createInstallers();

    private Hooks() {
    }

    private static EnumMap<Feature, Installer> createInstallers() {
        EnumMap<Feature, Installer> map = new EnumMap<>(Feature.class);

        map.put(Feature.SPLASH_AD,
                (module, cl, config, f) -> SplashAdHooks.install(module, cl));
        map.put(Feature.LAUNCH_DIALOG,
                (module, cl, config, f) -> LaunchDialogHooks.install(module, cl));
        map.put(Feature.UPDATE_DIALOG,
                (module, cl, config, f) -> UpdateHooks.install(module, cl));
        map.put(Feature.AD_REPORT_JUMP,
                (module, cl, config, f) -> AdReportHooks.install(module, cl));
        map.put(Feature.SEARCH_AD,
                (module, cl, config, f) -> SearchAdHooks.install(module, cl, f));
        map.put(Feature.AD_PRELOAD,
                (module, cl, config, f) -> AdPreloadHooks.install(module, cl, f));
        map.put(Feature.FEED_AD,
                (module, cl, config, f) -> FeedAdHooks.install(module, cl));
        map.put(Feature.COMMENT_AD,
                (module, cl, config, f) -> CommentAdHooks.install(module, cl));
        map.put(Feature.PLAYER_AD,
                (module, cl, config, f) -> PlayerAdHooks.install(module, cl));
        map.put(Feature.AUDIO_CLIP_AD,
                (module, cl, config, f) -> AudioAdHooks.install(module, cl));
        map.put(Feature.STARTUP_DELAY,
                (module, cl, config, f) -> StartupDelayHooks.install(module, cl));
        map.put(Feature.HOME_BLOCKS,
                (module, cl, config, f) -> HomeBlockHooks.install(module, cl, config));
        map.put(Feature.TOP_CHANNELS,
                (module, cl, config, f) -> TopTabHooks.install(module, cl, config));
        map.put(Feature.PLAYER_HINTS,
                (module, cl, config, f) -> PlayerHintHooks.install(module, cl, f));
        map.put(Feature.SEARCH_GUIDE,
                (module, cl, config, f) -> SearchGuideHooks.install(module, cl, f));
        map.put(Feature.PODCAST_GUIDE,
                (module, cl, config, f) -> PodcastGuideHooks.install(module, cl, f));
        map.put(Feature.WIDGET_PUSH_GUIDE,
                (module, cl, config, f) -> WidgetPushGuideHooks.install(module, cl, f));
        map.put(Feature.LYRICS_HINT,
                (module, cl, config, f) -> LyricsHintHooks.install(module, cl, f));
        map.put(Feature.COMMUNITY_LIVE,
                (module, cl, config, f) -> CommunityLiveHooks.install(module, cl, f));
        map.put(Feature.MINE_BACKGROUND,
                (module, cl, config, f) -> MineBackgroundHooks.install(module, cl, config));
        map.put(Feature.BOTTOM_NAV,
                (module, cl, config, f) -> BottomTabHooks.install(module, cl, config));
        map.put(Feature.HINTS,
                (module, cl, config, f) -> HintHooks.install(module, cl));
        map.put(Feature.AUTOPLAY,
                (module, cl, config, f) -> AutoPlayHooks.install(module, cl));
        map.put(Feature.DARK_MODE,
                (module, cl, config, f) -> ThemeHooks.install(module, cl));
        map.put(Feature.PENDANT,
                (module, cl, config, f) -> PendantHooks.install(module, cl));
        map.put(Feature.OAID_REPORT,
                (module, cl, config, f) -> OaidHooks.install(module, cl));
        map.put(Feature.LOG_TASKS,
                (module, cl, config, f) -> LogTaskHooks.install(module, cl));
        map.put(Feature.STARTUP_BLACKLIST,
                (module, cl, config, f) -> StartupBlacklistHooks.install(module, cl, config));
        map.put(Feature.APM,
                (module, cl, config, f) -> ApmHooks.install(module, cl));
        map.put(Feature.STATS_OBSERVE,
                (module, cl, config, f) -> StatisticHooks.install(module, cl));
        map.put(Feature.CLIPBOARD,
                (module, cl, config, f) -> ClipboardHooks.install(module, cl, f));
        map.put(Feature.STARTUP_ORCHESTRATION,
                (module, cl, config, f) -> StartupOrchestrationHooks.install(module, cl, config, f));

        // UI-06 DRAWER 当前 blocked，刻意不注册 installer。
        return map;
    }

    /** 注册表键快照，供单测断言「非 blocked 全覆盖、blocked 不安装」。 */
    static Set<Feature> installerKeys() {
        EnumSet<Feature> keys = EnumSet.noneOf(Feature.class);
        keys.addAll(INSTALLERS.keySet());
        return Collections.unmodifiableSet(keys);
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
            Installer installer = INSTALLERS.get(f);
            if (installer == null) {
                if (f.blocked()) {
                    module.log(Log.INFO, TAG, f.id + " blocked: " + f.blockedReason);
                } else {
                    module.log(Log.INFO, TAG, f.id + " pending implementation in later phase");
                }
                return;
            }
            installer.install(module, cl, config, f);
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, f.id + " install threw", t);
            HookState.note(f, "安装异常");
        }
    }
}
