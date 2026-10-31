package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;
import com.cloudmusictool.app.HookState;

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

        // 2. 歌词“选项提示”气泡：宿主 LyricViewContainer#checkAndShowLrcOptionHint() 只有
        //    return-void（9.6.08/9.6.13/9.6.15 dexsql 反汇编均为 opcode_count=1、0x0e），
        //    没有任何可拦截逻辑，挂 skipVoid 只是死挂载点；因此标记 unsupported 且不安装，
        //    其余两个挂载点照常安装。
        markOptionHintUnsupported(module, f);

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

    /**
     * UI-14 第 2 个挂载点的 unsupported 标记：宿主方法体为空（return-void），没有逻辑可拦，
     * 安装 skipVoid 只会产生死挂载点。这里不安装、不计入 expected/failed（避免把已知的
     * 无逻辑挂载点算成失败或降级），只把原因写进诊断的“跳过原因”。
     */
    static void markOptionHintUnsupported(XposedModule module, Feature f) {
        String reason = "unsupported: " + LYRICS_VIEW_CONTAINER + "#checkAndShowLrcOptionHint"
                + " 宿主方法仅 return-void，无可拦截逻辑";
        HookKit.warn(module, f.id + " " + reason);
        HookState.note(f, reason);
    }
}
