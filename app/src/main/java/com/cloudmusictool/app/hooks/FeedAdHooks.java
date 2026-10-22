package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * AD-06 首页 / 发现页信息流广告。
 * 以 Banner.isAdBanner 为主闸门；不主动改 block 列表，避免误删正常卡片。
 */
public final class FeedAdHooks {

    private FeedAdHooks() {
    }

    public static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.FEED_AD;

        try {
            Class<?> bannerCls = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.meta.Banner");
            if (bannerCls != null) {
                Method isAdBanner = HookKit.findMethod(module, f, bannerCls, "isAdBanner");
                HookKit.install(module, f, "Banner#isAdBanner", isAdBanner,
                        HookKit.constBool(module, f, "Banner.isAdBanner -> false", false));
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " Banner hook failed", t);
        }
    }
}
