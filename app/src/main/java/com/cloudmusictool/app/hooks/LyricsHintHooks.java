package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * UI-14 歌词页推广与提示屏蔽。
 */
public final class LyricsHintHooks {

    private static final String LYRICS_BOTTOM_HINT =
            "com.netease.cloudmusic.module.hint.view.LyricsBottomHintView";
    private static final String LYRICS_VIEW_CONTAINER =
            "com.netease.cloudmusic.ui.LyricViewContainer";
    private static final String LYRICS_VIDEO_TIPS_DIALOG =
            "com.netease.cloudmusic.fragment.LyricVideoTipsDialog";

    private LyricsHintHooks() {
    }

    public static void install(XposedModule module, ClassLoader cl, Feature feature) {
        if (feature == null) {
            HookKit.warn(module, "UI-14 install skipped: feature is null");
            return;
        }
        Feature f = feature;

        // 1. 歌词页底部推广 Hint
        try {
            Class<?> cls = HookKit.findClass(module, f, cl, LYRICS_BOTTOM_HINT);
            if (cls != null) {
                // 定位/返回类型校验/安装/登记收敛到 HookKit.installVoidByName。
                HookKit.installVoidByName(module, f, cls, "maybeDisplayHint",
                        "LyricsBottomHintView#maybeDisplayHint");
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " LyricsBottomHintView hook failed", t);
        }

        // 2. 歌词“选项提示”气泡
        try {
            Class<?> cls = HookKit.findClass(module, f, cl, LYRICS_VIEW_CONTAINER);
            if (cls != null) {
                Method m = HookKit.findMethod(module, f, cls, "checkAndShowLrcOptionHint",
                        void.class, new Class<?>[0]);
                // 返回类型校验收敛到 HookKit.validateReturnType（skipVoid 要求精确 void）。
                HookKit.install(module, f, "LyricViewContainer#checkAndShowLrcOptionHint", m,
                        HookKit.skipVoid(module, f, "LyricViewContainer.checkAndShowLrcOptionHint"));
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " LyricViewContainer hook failed", t);
        }

        // 3. 歌词视频推广弹窗
        // 参数含 androidx.fragment.app.FragmentActivity，模块侧不直接引用 androidx，
        // 这里按唯一方法名定位（该类只有一个 oc 方法）。
        try {
            Class<?> cls = HookKit.findClass(module, f, cl, LYRICS_VIDEO_TIPS_DIALOG);
            if (cls != null) {
                HookKit.installVoidByName(module, f, cls, "oc", "LyricVideoTipsDialog#oc");
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " LyricVideoTipsDialog hook failed", t);
        }
    }
}
