package com.cloudmusictool.app.hooks;

import android.app.Activity;
import android.hardware.SensorEvent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import com.cloudmusictool.app.Feature;
import com.cloudmusictool.app.dexkit.DexKitAnchors;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedModule;

/**
 * AD-01 开屏广告三层拦截：判定 -> 数据源 -> 落地页兜底。
 */
public final class SplashAdHooks {

    private SplashAdHooks() {
    }

    public static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.SPLASH_AD;
        try {
            hookLoadingAdManager(module, cl, f);
        } catch (Throwable t) {
            HookKit.error(module, f.id + " LoadingAdManager hook failed", t);
        }

        try {
            hookLoadingAdActivity(module, cl, f);
        } catch (Throwable t) {
            HookKit.error(module, f.id + " LoadingAdActivity hook failed", t);
        }

        try {
            hookLoadingAdFragment(module, cl, f);
        } catch (Throwable t) {
            HookKit.error(module, f.id + " LoadingAdFragment hook failed", t);
        }

        try {
            hookLoadingAdShakeHelper(module, cl, f);
        } catch (Throwable t) {
            HookKit.error(module, f.id + " LoadingAdShakeHelper hook failed", t);
        }

        try {
            hookNoAdAbTest(module, cl, f);
        } catch (Throwable t) {
            HookKit.error(module, f.id + " q60.w0 hook failed", t);
        }
    }

    private static void hookLoadingAdManager(XposedModule module, ClassLoader cl, Feature f) {
        Class<?> managerCls = HookKit.findClass(module, f, cl, "com.netease.cloudmusic.module.ad.LoadingAdManager",
                DexKitAnchors.LOADING_AD_MANAGER);
        Class<?> adInfoCls = HookKit.findClass(module, f, cl, "com.netease.cloudmusic.module.ad.meta.AdInfo");
        if (managerCls == null) {
            return;
        }

        // 1. 最终展示判定：E(AdInfo)Z -> false
        if (adInfoCls != null) {
            Method mE = HookKit.findMethod(module, f, managerCls, "E", adInfoCls);
            HookKit.install(module, f, "LoadingAdManager#E", mE,
                    HookKit.constBool(module, f, "LoadingAdManager.E -> false", false));
        }

        // 2. 热启动拉起：G(Activity)V -> return
        Method mG = HookKit.findMethod(module, f, managerCls, "G", Activity.class);
        HookKit.install(module, f, "LoadingAdManager#G", mG,
                HookKit.skipVoid(module, f, "LoadingAdManager.G"));

        // 3. 数据源：o(Z)ArrayList -> 空 ArrayList
        Method mO = HookKit.findMethod(module, f, managerCls, "o", boolean.class);
        HookKit.install(module, f, "LoadingAdManager#o", mO, chain -> {
            try {
                HookKit.hit(module, f, "LoadingAdManager.o -> empty ArrayList");
                return new ArrayList<>();
            } catch (Throwable t) {
                HookKit.error(module, f.id + " intercept error LoadingAdManager#o", t);
                return chain.proceed();
            }
        });

        // 4. 签名扫描：所有 (AdInfo)Z 方法统一返回 false
        //    9.6.13 的判定方法可能已改名（E -> H），上面的 profile 映射已挂过 H，
        //    这里把映射后的名字一并排除，避免同一方法重复挂载。
        if (adInfoCls != null) {
            String judgeName = HookKit.mappedMethodName(managerCls, "E");
            for (Method m : managerCls.getDeclaredMethods()) {
                if (m.getName().equals("E") || m.getName().equals(judgeName)) {
                    continue;
                }
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 1 && params[0] == adInfoCls
                        && (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class)) {
                    HookKit.install(module, f, "LoadingAdManager#" + m.getName() + "(AdInfo)Z", m,
                            HookKit.constBool(module, f, "LoadingAdManager." + m.getName() + " -> false", false));
                }
            }
        }
    }

    private static void hookLoadingAdActivity(XposedModule module, ClassLoader cl, Feature f) {
        Class<?> actCls = HookKit.findClass(module, f, cl, "com.netease.cloudmusic.activity.LoadingAdActivity");
        if (actCls == null) {
            return;
        }
        Method onCreate = HookKit.findMethod(module, f, actCls, "onCreate", Bundle.class);
        HookKit.install(module, f, "LoadingAdActivity#onCreate", onCreate, chain -> {
            Object res = chain.proceed();
            try {
                Object thisObj = chain.getThisObject();
                if (thisObj instanceof Activity) {
                    HookKit.hit(module, f, "LoadingAdActivity onCreate -> finish");
                    ((Activity) thisObj).finish();
                }
            } catch (Throwable t) {
                HookKit.error(module, f.id + " LoadingAdActivity finish error", t);
            }
            return res;
        });
    }

    private static void hookLoadingAdFragment(XposedModule module, ClassLoader cl, Feature f) {
        Class<?> fragCls = HookKit.findClass(module, f, cl, "com.netease.cloudmusic.fragment.LoadingAdFragment");
        if (fragCls == null) {
            return;
        }
        // 冷启动广告 Fragment：after-proceed 正常执行后将广告 View 置 GONE 并从 parent 移除，杜绝空白覆盖层
        Method onViewCreated = HookKit.findByNameQuiet(fragCls, "onViewCreated");
        if (onViewCreated != null) {
            HookKit.install(module, f, "LoadingAdFragment#onViewCreated", onViewCreated, chain -> {
                Object res = chain.proceed();
                try {
                    List<Object> args = chain.getArgs();
                    if (args != null && !args.isEmpty() && args.get(0) instanceof View) {
                        View view = (View) args.get(0);
                        view.setVisibility(View.GONE);
                        ViewParent parent = view.getParent();
                        if (parent instanceof ViewGroup) {
                            ((ViewGroup) parent).removeView(view);
                            HookKit.hit(module, f, "LoadingAdFragment onViewCreated: set GONE and removed from parent");
                        }
                    }
                } catch (Throwable t) {
                    HookKit.error(module, f.id + " LoadingAdFragment onViewCreated error", t);
                }
                return res;
            });
        } else {
            HookKit.info(module, f.id + " LoadingAdFragment onViewCreated not found, skip");
        }
    }

    private static void hookLoadingAdShakeHelper(XposedModule module, ClassLoader cl, Feature f) {
        Class<?> shakeCls = HookKit.findClass(module, f, cl, "com.netease.cloudmusic.module.ad.boot.LoadingAdShakeHelper");
        if (shakeCls == null) {
            return;
        }
        Method onSensorChanged = HookKit.findMethod(module, f, shakeCls, "onSensorChanged", SensorEvent.class);
        HookKit.install(module, f, "LoadingAdShakeHelper#onSensorChanged", onSensorChanged,
                HookKit.skipVoid(module, f, "LoadingAdShakeHelper.onSensorChanged"));
    }

    private static void hookNoAdAbTest(XposedModule module, ClassLoader cl, Feature f) {
        Class<?> w0Cls = HookKit.findClass(module, f, cl, "q60.w0", DexKitAnchors.Q60_W0);
        if (w0Cls == null) {
            return;
        }
        // 注意极性：q60/w0.g() 返回 false 才是命中免广分支
        Method g = HookKit.findMethodWithAnchors(module, f, w0Cls, "g", DexKitAnchors.Q60_W0);
        HookKit.install(module, f, "q60.w0#g", g,
                HookKit.constBool(module, f, "q60.w0.g -> false (hit_noad_abtest)", false));
    }
}
