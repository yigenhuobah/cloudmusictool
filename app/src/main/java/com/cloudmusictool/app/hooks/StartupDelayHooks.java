package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
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
            if (!isDelayTimeMethod(getDelayTime)) {
                warnSignature(module, f, "getStartAppRedPageDelayTime", getDelayTime, "long(String)");
                getDelayTime = null;
            }
            HookKit.install(module, f, "StartTimeHandler#getStartAppRedPageDelayTime", getDelayTime,
                    chain -> interceptDelayTime(module, f, chain));

            // 3. checkDelayDoAction(Runnable, String)V -> before: 直接 run()，跳过延迟
            Method checkDelay = HookKit.findMethod(module, f, handlerCls, "checkDelayDoAction", Runnable.class, String.class);
            if (checkDelay == null) {
                checkDelay = HookKit.findByNameQuiet(handlerCls, "checkDelayDoAction");
            }
            if (!isCheckDelayMethod(checkDelay)) {
                warnSignature(module, f, "checkDelayDoAction", checkDelay, "void(Runnable,String)");
                checkDelay = null;
            }
            HookKit.install(module, f, "StartTimeHandler#checkDelayDoAction", checkDelay,
                    chain -> interceptCheckDelay(module, f, chain));
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install failed", t);
        }
    }

    /**
     * 期望签名：返回 long/Long，唯一参数 String。
     *
     * HookKit.findMethodQuiet / findByNameQuiet 只按名（或参数）取方法，不校验返回类型；
     * 自写 lambda 又不被 HookKit.validateReturnType 识别（只认 constBool/skipVoid）。
     * 目标版本同名同参但返回类型变化时，Long 交给非 long 方法会被框架抛
     * ClassCastException / 宿主 unbox 崩溃，因此宁可不装。
     */
    static boolean isDelayTimeMethod(Method m) {
        if (m == null) {
            return false;
        }
        Class<?> rt = m.getReturnType();
        if (rt != long.class && rt != Long.class) {
            return false;
        }
        Class<?>[] p = m.getParameterTypes();
        return p.length == 1 && p[0] == String.class;
    }

    /**
     * 期望签名：void(Runnable, String)。
     * 兜底到只按名取到的错误重载时会静默丢掉红屏动作（args.get(0) 不是 Runnable 就跳过业务体）。
     */
    static boolean isCheckDelayMethod(Method m) {
        if (m == null || m.getReturnType() != void.class) {
            return false;
        }
        Class<?>[] p = m.getParameterTypes();
        return p.length == 2 && p[0] == Runnable.class && p[1] == String.class;
    }

    private static void warnSignature(XposedModule module, Feature f, String name,
                                      Method m, String expected) {
        if (m == null) {
            return;
        }
        HookKit.warn(module, f.id + " skip " + name + ": unexpected signature "
                + m.getReturnType().getName() + "(" + m.getParameterCount() + " args) != " + expected);
    }

    /** getStartAppRedPageDelayTime：恒返回 0，不调用 proceed。 */
    static Object interceptDelayTime(XposedModule module, Feature f, XposedInterface.Chain chain) {
        HookKit.hit(module, f, "StartTimeHandler.getStartAppRedPageDelayTime -> 0L");
        return Long.valueOf(0L);
    }

    /**
     * checkDelayDoAction 的替代实现：直接执行宿主传入的 Runnable，跳过延迟。
     *
     * 宿主原方法在 isNeedDelayStart()==false 时就是 actionRunnable.run()，因此 Runnable
     * 自身的异常必须原样抛回。绝不能用 proceed 兜底：isNeedDelayStart 已被本类 hook 成
     * false，proceed 会让同一个启动动作立刻再执行一次。
     */
    static Object interceptCheckDelay(XposedModule module, Feature f, XposedInterface.Chain chain)
            throws Throwable {
        HookKit.hit(module, f, "StartTimeHandler.checkDelayDoAction execute immediately");
        java.util.List<Object> args = chain.getArgs();
        Object action = args != null && !args.isEmpty() ? args.get(0) : null;
        if (!(action instanceof Runnable)) {
            // 安装前已校验签名；这里只防御性放行，仍只 proceed 一次。
            return proceed(module, f, "checkDelayDoAction", chain);
        }
        ((Runnable) action).run();
        return null;
    }

    /** 唯一的 proceed 入口：异常记录后原样抛回，调用方不得再补一次。 */
    private static Object proceed(XposedModule module, Feature f, String label,
                                  XposedInterface.Chain chain) throws Throwable {
        try {
            return chain.proceed();
        } catch (Throwable t) {
            HookKit.error(module, f.id + " proceed error " + label, t);
            throw t;
        }
    }
}
