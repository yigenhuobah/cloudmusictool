package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;

import io.github.libxposed.api.XposedModule;

/**
 * UI-12 播客 / AI DJ 引导屏蔽。
 */
public final class PodcastGuideHooks {

    private static final String BOTTOM_GUIDE_MANAGER =
            "com.netease.cloudmusic.music.biz.voice.home.PodcastBottomGuideManager";
    private static final String PLAYER_AIDJ_GUIDE =
            "com.netease.cloudmusic.ui.hint.aidj.PlayerAIDJSwitchGuideView";
    private static final String FM_AIDJ_SWITCH =
            "com.netease.cloudmusic.hint.aidj.FMAIDJSwitchView";

    private PodcastGuideHooks() {
    }

    public static void install(XposedModule module, ClassLoader cl, Feature feature) {
        if (feature == null) {
            HookKit.warn(module, "UI-12 install skipped: feature is null");
            return;
        }
        Feature f = feature;

        // 1. 播客 FM 自动播放 / 听书权益引导条
        try {
            Class<?> cls = HookKit.findClass(module, f, cl, BOTTOM_GUIDE_MANAGER);
            if (cls != null) {
                // 定位/返回类型校验/安装/登记收敛到 HookKit.installConstFalse。
                HookKit.installConstFalse(module, f, cls, "isTimeToShowFMAutoPlayGuide",
                        "PodcastBottomGuideManager#isTimeToShowFMAutoPlayGuide");
                HookKit.installConstFalse(module, f, cls, "isTimeToShowVoiceBookGuide",
                        "PodcastBottomGuideManager#isTimeToShowVoiceBookGuide");
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " PodcastBottomGuideManager hook failed", t);
        }

        // 2. 播放页 AI DJ 切换引导
        try {
            Class<?> cls = HookKit.findClass(module, f, cl, PLAYER_AIDJ_GUIDE);
            if (cls != null) {
                HookKit.installVoidByName(module, f, cls, "showGuide",
                        "PlayerAIDJSwitchGuideView#showGuide");
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " PlayerAIDJSwitchGuideView hook failed", t);
        }

        // 3. FM 页 AI DJ 切换引导气泡
        try {
            Class<?> cls = HookKit.findClass(module, f, cl, FM_AIDJ_SWITCH);
            if (cls != null) {
                HookKit.installVoidByName(module, f, cls, "maybeDisplayHint",
                        "FMAIDJSwitchView#maybeDisplayHint");
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " FMAIDJSwitchView hook failed", t);
        }
    }
}
