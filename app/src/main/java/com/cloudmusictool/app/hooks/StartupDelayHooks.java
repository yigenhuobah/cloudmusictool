package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * UI-02 启动延迟（红屏停留）优化。
 */
public final class StartupDelayHooks {

    private StartupDelayHooks() {
    }

    public static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.STARTUP_DELAY;
        try {
            Class<?> handlerCls = HookKit.findClass(module, f, cl, "com.netease.cloudmusic.module.startup.StartTimeHandler");
            if (handlerCls == null) {
                return;
            }

            // 1. isNeedDelayStart()Z -> false
            Method isNeedDelayStart = HookKit.findMethod(module, f, handlerCls, "isNeedDelayStart");
            HookKit.install(module, f, "StartTimeHandler#isNeedDelayStart", isNeedDelayStart,
                    HookKit.constBool(module, f, "StartTimeHandler.isNeedDelayStart -> false", false));

            // 2. getStartAppRedPageDelayTime(String)J -> 0L
            Method getDelayTime = HookKit.findMethod(module, f, handlerCls, "getStartAppRedPageDelayTime",
                    long.class, new Class<?>[]{String.class});
            if (getDelayTime == null) {
                getDelayTime = HookKit.findByNameQuiet(handlerCls, "getStartAppRedPageDelayTime");
            }
            HookKit.install(module, f, "StartTimeHandler#getStartAppRedPageDelayTime", getDelayTime, chain -> {
                try {
                    HookKit.hit(module, f, "StartTimeHandler.getStartAppRedPageDelayTime -> 0L");
                    return Long.valueOf(0L);
                } catch (Throwable t) {
                    HookKit.error(module, f.id + " intercept error getStartAppRedPageDelayTime", t);
                    return chain.proceed();
                }
            });

            // 3. checkDelayDoAction(Runnable, String)V -> before: 直接 run()，跳过延迟
            Method checkDelay = HookKit.findMethod(module, f, handlerCls, "checkDelayDoAction", Runnable.class, String.class);
            if (checkDelay == null) {
                checkDelay = HookKit.findByNameQuiet(handlerCls, "checkDelayDoAction");
            }
            HookKit.install(module, f, "StartTimeHandler#checkDelayDoAction", checkDelay, chain -> {
                try {
                    HookKit.hit(module, f, "StartTimeHandler.checkDelayDoAction execute immediately");
                    java.util.List<Object> args = chain.getArgs();
                    if (args != null && !args.isEmpty() && args.get(0) instanceof Runnable) {
                        ((Runnable) args.get(0)).run();
                    }
                    return null;
                } catch (Throwable t) {
                    HookKit.error(module, f.id + " intercept error checkDelayDoAction", t);
                    return chain.proceed();
                }
            });
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install failed", t);
        }
    }
}
