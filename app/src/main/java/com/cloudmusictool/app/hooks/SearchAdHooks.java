package com.cloudmusictool.app.hooks;

import android.view.View;

import com.cloudmusictool.app.Feature;
import com.cloudmusictool.app.HookState;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
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
                        HookKit.install(module, f, "RNSearchAdViewManger#hideAllViews", hide,
                                chain -> interceptHideAllViews(module, f, chain));
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
            // e(Ad, int, Function1, Function1) 的两个 Function1 是宿主在方法体内注册的
            // “移除/点击”事件回调，不是本次调用的同步回调；纯 skipVoid 会留下
            // 已 setVisibility(VISIBLE) 却未绑定的固定高度广告卡（见 interceptAdItem）。
            String label = simpleName + "#e";
            Method e = HookKit.findMethod(module, f, cls, "e");
            if (e == null) {
                return;
            }
            if (e.getReturnType() != void.class) {
                HookKit.warn(module, f.id + " skip " + label + ": unexpected return "
                        + e.getReturnType());
                return;
            }
            HookKit.install(module, f, label, e,
                    chain -> interceptAdItem(module, f, label, chain));
        } catch (Throwable t) {
            HookKit.error(module, f.id + " " + simpleName + " hook failed", t);
        }
    }

    /**
     * hideAllViews 放行型 hook：只记 entry（入口调用），不记 hit ——
     * 本 hook 不改写任何东西，记 hit 会让 newStyle/oldStyle 未装上时仍显示“已命中”。
     * proceed 恰好一次：记账异常只记录，宿主异常记录后原样抛回。
     */
    static Object interceptHideAllViews(XposedModule module, Feature f,
                                        XposedInterface.Chain chain) throws Throwable {
        try {
            HookState.entry(f);
        } catch (Throwable t) {
            HookKit.error(module, f.id
                    + " intercept error RNSearchAdViewManger#hideAllViews", t);
        }
        try {
            return chain.proceed();
        } catch (Throwable t) {
            HookKit.error(module, f.id + " proceed error RNSearchAdViewManger#hideAllViews", t);
            throw t;
        }
    }

    /**
     * SearchAdItem*View#e 的 hook 体：不 proceed（不绑定广告内容），并把宿主视图本身置 GONE。
     * 宿主 e 的首条逻辑是 setVisibility(VISIBLE) 再 AdTagView.bindData，只 no-op 会留下
     * 可见但未绑定的固定高度广告卡（构造器已按 getAdH() 定高）。
     * 两个 Function1 形参是宿主在方法体内注册的事件回调（AdTagView.setOnRemoveCallback /
     * OnClickListener），跳过业务体只意味着这些卡片级监听不再注册；盲调它们反而会伪造
     * 点击/移除事件，因此这里不调用。
     */
    static Object interceptAdItem(XposedModule module, Feature f, String label,
                                  XposedInterface.Chain chain) {
        try {
            HookKit.hit(module, f, label + " suppressed, itemView GONE");
            hideItem(module, f, chain.getThisObject());
        } catch (Throwable t) {
            HookKit.error(module, f.id + " intercept error " + label, t);
        }
        return null;
    }

    /** 卡片视图即方法 receiver（SearchAdItem*View 继承 RoundedFrameLayout）。 */
    private static void hideItem(XposedModule module, Feature f, Object target) {
        if (!(target instanceof View)) {
            HookKit.warn(module, f.id + " hide skipped: hook receiver is not a View");
            return;
        }
        try {
            ((View) target).setVisibility(View.GONE);
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " hide itemView failed: " + t.getMessage());
        }
    }
}
