package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * EX-01 强制深色模式：hook ResourceRouter 返回夜间模式状态。
 */
public final class ThemeHooks {

    private ThemeHooks() {
    }

    public static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.DARK_MODE;

        try {
            Class<?> routerCls = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.theme.core.ResourceRouter");
            if (routerCls != null) {
                Method mNight = HookKit.findMethodQuiet(routerCls, "isNightTheme");
                if (mNight != null) {
                    HookKit.install(module, f, "ResourceRouter#isNightTheme", mNight,
                            HookKit.constBool(module, f, "ResourceRouter.isNightTheme -> true", true));
                }

                Method mUiMode = HookKit.findMethodQuiet(routerCls, "isUiModeNightYes");
                if (mUiMode != null) {
                    HookKit.install(module, f, "ResourceRouter#isUiModeNightYes", mUiMode,
                            HookKit.constBool(module, f, "ResourceRouter.isUiModeNightYes -> true", true));
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " ResourceRouter hook failed", t);
        }
    }
}
