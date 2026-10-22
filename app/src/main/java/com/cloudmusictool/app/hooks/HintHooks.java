package com.cloudmusictool.app.hooks;

import android.content.Context;

import com.cloudmusictool.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * UI-07 引导与气泡屏蔽：屏蔽各类引导气泡、评分弹窗、青少年弹窗及权限引导。
 */
public final class HintHooks {

    private HintHooks() {
    }

    public static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.HINTS;

        // 1. maybeDisplayHint 系列弹窗与气泡
        String[] hintViewClasses = {
                "com.netease.cloudmusic.main.hint.push.PushGuideHintView",
                "com.netease.cloudmusic.module.hint.view.ScoreHintDialogView",
                "com.netease.cloudmusic.discovery.view.module.music.v3.vipcard.HintVipNormalRenewalDialogueView",
                "com.netease.cloudmusic.module.hint.view.LiveLocalPushHintView",
                "com.netease.cloudmusic.main.hint.BottomTabGuideBubbleNativeView"
        };

        for (String clsName : hintViewClasses) {
            try {
                String simpleName = clsName.substring(clsName.lastIndexOf('.') + 1);
                Class<?> cls = HookKit.findClass(module, f, cl, clsName);
                if (cls != null) {
                    Method m = HookKit.findByNameQuiet(cls, "maybeDisplayHint");
                    if (m != null) {
                        HookKit.install(module, f, simpleName + "#maybeDisplayHint", m, chain -> {
                            try {
                                HookKit.hit(module, f, simpleName + ".maybeDisplayHint intercepted");
                                Class<?> returnType = m.getReturnType();
                                if (returnType == boolean.class || returnType == Boolean.class) {
                                    return Boolean.FALSE;
                                }
                                return null;
                            } catch (Throwable t) {
                                HookKit.error(module, f.id + " intercept error " + simpleName + "#maybeDisplayHint", t);
                                return chain.proceed();
                            }
                        });
                    }
                }
            } catch (Throwable t) {
                HookKit.error(module, f.id + " " + clsName + " hook failed", t);
            }
        }

        // 2. nr2.c.checkAndShowYouthDialog(Context)Z -> false
        try {
            Class<?> nr2c = HookKit.findClass(module, f, cl, "nr2.c");
            if (nr2c != null) {
                Method mYouth = HookKit.findMethodQuiet(nr2c, "checkAndShowYouthDialog", Context.class);
                if (mYouth == null) {
                    mYouth = HookKit.findByNameQuiet(nr2c, "checkAndShowYouthDialog");
                }
                if (mYouth != null) {
                    HookKit.install(module, f, "nr2.c#checkAndShowYouthDialog", mYouth,
                            HookKit.constBool(module, f, "nr2.c.checkAndShowYouthDialog -> false", false));
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " nr2.c hook failed", t);
        }
    }
}
