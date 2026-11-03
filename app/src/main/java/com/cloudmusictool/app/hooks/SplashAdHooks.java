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
        //    记住实际挂上的那个方法：签名扫描只在它确实安装成功时才排除，
        //    定位/安装失败时扫描必须能按签名补挂。
        Method installedJudge = null;
        if (adInfoCls != null) {
            Method mE = HookKit.findMethod(module, f, managerCls, "E", adInfoCls);
            if (HookKit.install(module, f, "LoadingAdManager#E", mE,
                    HookKit.constBool(module, f, "LoadingAdManager.E -> false", false))) {
                installedJudge = mE;
            }
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
            } catch (Throwable t) {
                // 替换型 hook：记账失败也按拦截语义返回空列表，绝不回退成二次 proceed。
                HookKit.error(module, f.id + " intercept error LoadingAdManager#o", t);
            }
            return new ArrayList<>();
        });

        // 4. 签名扫描：所有 (AdInfo)Z 方法统一返回 false
        //    排除依据是第 1 步实际挂上的方法（按身份比较），而不是无条件按名字跳过：
        //    第 1 步 install 失败时（Profile 映射签名不符、hook 返回 false 等），
        //    映射出的新方法名仍能被这里补挂，判定 hook 不会整体失效。
        if (adInfoCls != null) {
            for (Method m : judgeScanTargets(managerCls, adInfoCls, installedJudge)) {
                HookKit.install(module, f, "LoadingAdManager#" + m.getName() + "(AdInfo)Z", m,
                        HookKit.constBool(module, f, "LoadingAdManager." + m.getName() + " -> false", false));
            }
        }
    }

    /**
     * 签名扫描的候选方法：所有 (AdInfo)Z/Boolean 方法，排除第 1 步已成功挂载的那个。
     *
     * @param installedJudge 第 1 步判定方法实际挂上的 Method（null 表示第 1 步未挂上，
     *                       此时全部候选都是补挂目标，不再按名字排除）。
     */
    static List<Method> judgeScanTargets(Class<?> managerCls, Class<?> adInfoCls,
                                         Method installedJudge) {
        List<Method> targets = new ArrayList<>();
        for (Method m : managerCls.getDeclaredMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if (params.length != 1 || params[0] != adInfoCls) {
                continue;
            }
            if (m.getReturnType() != boolean.class && m.getReturnType() != Boolean.class) {
                continue;
            }
            if (installedJudge != null && installedJudge.equals(m)) {
                continue;
            }
            targets.add(m);
        }
        return targets;
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
        Method onViewCreated = HookKit.findByNameQuiet(module, f,
                "LoadingAdFragment#onViewCreated", fragCls, "onViewCreated");
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
