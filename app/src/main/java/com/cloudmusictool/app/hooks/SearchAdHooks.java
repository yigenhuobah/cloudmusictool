package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * AD-04 搜索广告净化。
 * 覆盖搜索起始页 RN 广告与搜索结果混排广告。
 */
public final class SearchAdHooks {

    private static final String MANAGER =
            "com.netease.cloudmusic.music.biz.search.start.rnview.RNSearchAdViewManger";
    private static final String LARGE_ITEM =
            "com.netease.cloudmusic.music.biz.search.complex.viewholder.ad.SearchAdItemLargeView";
    private static final String MEDIUM_ITEM =
            "com.netease.cloudmusic.music.biz.search.complex.viewholder.ad.SearchAdItemMediumView";

    private SearchAdHooks() {
    }

    public static void install(XposedModule module, ClassLoader cl, Feature feature) {
        if (feature == null) {
            HookKit.warn(module, "AD-04 install skipped: feature is null");
            return;
        }
        Feature f = feature;

        // 1. 搜索起始页广告：newStyle / oldStyle 两种渲染入口
        try {
            Class<?> cls = HookKit.findClass(module, f, cl, MANAGER);
            if (cls != null) {
                HookKit.installVoidByName(module, f, cls, "newStyle", "RNSearchAdViewManger#newStyle");
                HookKit.installVoidByName(module, f, cls, "oldStyle", "RNSearchAdViewManger#oldStyle");

                // hideAllViews 是官方“隐藏全部广告视图”入口，必须放行而不是 no-op，
                // 否则已经渲染出来的广告视图无法被清除。
                Method hide = HookKit.findMethod(module, f, cls, "hideAllViews");
                if (hide != null) {
                    if (hide.getReturnType() == void.class) {
                        HookKit.install(module, f, "RNSearchAdViewManger#hideAllViews", hide, chain -> {
                            try {
                                HookKit.hit(module, f, "RNSearchAdViewManger.hideAllViews pass-through");
                                return chain.proceed();
                            } catch (Throwable t) {
                                HookKit.error(module, f.id
                                        + " intercept error RNSearchAdViewManger#hideAllViews", t);
                                return null;
                            }
                        });
                    } else {
                        HookKit.warn(module, f.id + " skip RNSearchAdViewManger#hideAllViews: unexpected return "
                                + hide.getReturnType());
                    }
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " RNSearchAdViewManger hook failed", t);
        }

        // 2. 搜索结果混排广告卡片
        installAdItem(module, f, cl, LARGE_ITEM, "SearchAdItemLargeView");
        installAdItem(module, f, cl, MEDIUM_ITEM, "SearchAdItemMediumView");
    }

    private static void installAdItem(XposedModule module, Feature f, ClassLoader cl,
                                      String className, String simpleName) {
        try {
            Class<?> cls = HookKit.findClass(module, f, cl, className);
            if (cls == null) {
                return;
            }
            // 定位/返回类型校验/安装/登记收敛到 HookKit.installVoidByName。
            HookKit.installVoidByName(module, f, cls, "e", simpleName + "#e");
        } catch (Throwable t) {
            HookKit.error(module, f.id + " " + simpleName + " hook failed", t);
        }
    }
}
