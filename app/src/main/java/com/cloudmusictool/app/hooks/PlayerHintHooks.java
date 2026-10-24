package com.cloudmusictool.app.hooks;

import android.content.Context;

import com.cloudmusictool.app.Feature;

import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedModule;

/**
 * UI-10 播放页提示治理。
 * 评论气泡、推荐歌曲提示、卡顿/网络错误弹窗、激励视频浮层。
 */
public final class PlayerHintHooks {

    private static final String COMMENT_POP_VIEW =
            "com.netease.cloudmusic.ui.hint.PlayerCommentPopView";
    private static final String RECOMMEND_SONG_HINT =
            "com.netease.cloudmusic.module.hint.view.PlayerRecommendSongHintView";
    private static final String JANK_DIALOG_HELPER =
            "com.netease.cloudmusic.ui.PlayerJankDialogHelper";
    private static final String INCENTIVE_AD_VIEW =
            "com.netease.cloudmusic.module.hint.view.PlayerIncentiveAdView";

    private PlayerHintHooks() {
    }

    public static void install(XposedModule module, ClassLoader cl, Feature feature) {
        if (feature == null) {
            HookKit.warn(module, "UI-10 install skipped: feature is null");
            return;
        }
        Feature f = feature;

        // 1. 播放页评论气泡
        try {
            Class<?> cls = HookKit.findClass(module, f, cl, COMMENT_POP_VIEW);
            if (cls != null) {
                Method m = HookKit.findMethod(module, f, cls, "renderCommentListAndShow",
                        void.class, new Class<?>[]{List.class});
                // 返回类型校验收敛到 HookKit.validateReturnType（skipVoid 要求精确 void）。
                HookKit.install(module, f, "PlayerCommentPopView#renderCommentListAndShow", m,
                        HookKit.skipVoid(module, f, "PlayerCommentPopView.renderCommentListAndShow"));
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " PlayerCommentPopView hook failed", t);
        }

        // 2. 播放页“为你推荐”提示
        installVoidMaybeDisplayHint(module, f, cl, RECOMMEND_SONG_HINT, "PlayerRecommendSongHintView");

        // 3. 播放卡顿 / 网络错误 / 缓存 IO 错误弹窗
        try {
            Class<?> cls = HookKit.findClass(module, f, cl, JANK_DIALOG_HELPER);
            if (cls != null) {
                installVoidWithContext(module, f, cls, "showPlayJankNetErrorDialog",
                        "PlayerJankDialogHelper#showPlayJankNetErrorDialog");
                installVoidWithContext(module, f, cls, "showNMCacheIOErrorDialog",
                        "PlayerJankDialogHelper#showNMCacheIOErrorDialog");
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " PlayerJankDialogHelper hook failed", t);
        }

        // 4. 播放页激励视频浮层
        installVoidMaybeDisplayHint(module, f, cl, INCENTIVE_AD_VIEW, "PlayerIncentiveAdView");
    }

    private static void installVoidMaybeDisplayHint(XposedModule module, Feature f, ClassLoader cl,
                                                    String className, String simpleName) {
        try {
            Class<?> cls = HookKit.findClass(module, f, cl, className);
            if (cls == null) {
                return;
            }
            // 定位/返回类型校验/安装/登记收敛到 HookKit.installVoidByName。
            HookKit.installVoidByName(module, f, cls, "maybeDisplayHint",
                    simpleName + "#maybeDisplayHint");
        } catch (Throwable t) {
            HookKit.error(module, f.id + " " + simpleName + " hook failed", t);
        }
    }

    private static void installVoidWithContext(XposedModule module, Feature f, Class<?> cls,
                                               String name, String label) {
        try {
            Method m = HookKit.findMethod(module, f, cls, name,
                    void.class, new Class<?>[]{Context.class});
            // 返回类型校验收敛到 HookKit.validateReturnType（skipVoid 要求精确 void）。
            HookKit.install(module, f, label, m, HookKit.skipVoid(module, f, label));
        } catch (Throwable t) {
            HookKit.error(module, f.id + " " + label + " hook failed", t);
        }
    }
}
