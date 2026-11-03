package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * AD-09 音频贴片与播客广告。
 */
public final class AudioAdHooks {

    private AudioAdHooks() {
    }

    public static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.AUDIO_CLIP_AD;

        Class<?> adCls = null;
        try {
            adCls = Class.forName("com.netease.cloudmusic.meta.Ad", false, cl);
        } catch (Throwable ignored) {
        }

        try {
            Class<?> manager = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.module.ad.audioclip.AdAudioClipManager");
            if (manager != null) {
                Method d = findAdMethod(module, f, manager, "d", Object.class,
                        new Class<?>[]{String.class, int.class, long.class}, 5,
                        "AdAudioClipManager#d");
                HookKit.install(module, f, "AdAudioClipManager#d", d,
                        chain -> interceptAudioClipD(module, f, chain));

                Method c = findAdMethod(module, f, manager, "c", Object.class, null, 5,
                        "AdAudioClipManager#c");
                HookKit.install(module, f, "AdAudioClipManager#c", c, chain -> {
                    try {
                        HookKit.hit(module, f, "AdAudioClipManager.c -> skip clip playback");
                    } catch (Throwable t) {
                        // 替换型 hook：记账失败也按拦截语义返回 false，绝不回退成二次 proceed。
                        HookKit.error(module, f.id + " intercept error AdAudioClipManager#c", t);
                    }
                    return Boolean.FALSE;
                });
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " AdAudioClipManager hook failed", t);
        }

        try {
            Class<?> podcast = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.module.ad.podcast.AdPodcastPlayerPageHelper");
            if (podcast != null) {
                Method h = findAdMethod(module, f, podcast, "h", void.class,
                        adCls != null ? new Class<?>[]{adCls} : null, 1,
                        "AdPodcastPlayerPageHelper#h");
                HookKit.install(module, f, "AdPodcastPlayerPageHelper#h", h,
                        HookKit.skipVoid(module, f, "AdPodcastPlayerPageHelper.h"));
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " AdPodcastPlayerPageHelper hook failed", t);
        }

        try {
            Class<?> videoHelper = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.module.ad.podcast.PodcastVideoAdHelperImpl");
            if (videoHelper != null) {
                // 9.6.13 的 render 方法已改名（z -> B），先经 Profile 映射再按名/形状扫描。
                Method z = findAdMethod(module, f, videoHelper, "z", void.class,
                        new Class<?>[]{long.class, long.class}, 4,
                        "PodcastVideoAdHelperImpl#z");
                HookKit.install(module, f, "PodcastVideoAdHelperImpl#z", z,
                        HookKit.skipVoid(module, f, "PodcastVideoAdHelperImpl.z"));
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " PodcastVideoAdHelperImpl hook failed", t);
        }
    }

    /**
     * 定位 AD-09 的方法：先按 Profile 映射后的实际方法名做「前导参数类型 / 参数个数」匹配
     * （未适配版本没有 Profile 时的 best-effort 路径），再交给 HookKit.findMethod 按
     * Profile 精确坐标解析——后者失败会统一登记挂载点缺失，不再静默失效。
     *
     * 命中后校验参数个数与返回类型：同名同参数个数的无关方法（例如别的 void 重载）
     * 不会再被整体 no-op；校验失败同样交给 Profile 路径兜底。
     */
    static Method findAdMethod(XposedModule module, Feature f, Class<?> cls, String name,
                               Class<?> returnType, Class<?>[] leading, int arity, String label) {
        String actual = HookKit.mappedMethodName(cls, name);
        Method m = leading != null
                ? HookKit.findByShape(module, f, label, cls, actual, leading)
                : HookKit.findByNameAndArity(module, f, label, cls, actual, arity);
        if (m != null && m.getParameterTypes().length != arity) {
            m = null;
        }
        if (m != null && returnType != null && m.getReturnType() != returnType) {
            HookKit.warn(module, f.id + " " + label + " return type mismatch: "
                    + m.getReturnType().getName() + " (expected " + returnType.getName()
                    + "); try profile coordinates");
            m = null;
        }
        if (m == null) {
            m = HookKit.findMethod(module, f, cls, name, returnType,
                    (Class<?>[]) null, (String[]) null);
        }
        return m;
    }

    /**
     * AdAudioClipManager#d 的 hook 体：先按回调契约通知 AudioClipData(null)，再跳过业务体。
     *
     * proceed() 只在“读不到回调参数”这一条回退路径上调用，且在 catch 之外，
     * 绝不出现同一次调用二次 proceed。
     */
    static Object interceptAudioClipD(XposedModule module, Feature f,
                                      XposedInterface.Chain chain) throws Throwable {
        Object callback = null;
        boolean argsReadable = true;
        try {
            callback = chain.getArg(3);
        } catch (Throwable t) {
            argsReadable = false;
            HookKit.error(module, f.id + " intercept error AdAudioClipManager#d", t);
        }
        if (!argsReadable) {
            // 读不到回调参数：放弃拦截并回退宿主实现（proceed 在 catch 外，仅此一次）。
            return chain.proceed();
        }
        boolean notified = invokeCallbackNull(module, f, callback);
        HookKit.hit(module, f, notified
                ? "AdAudioClipManager.d -> callback(null), skip clip"
                : "AdAudioClipManager.d -> callback not notified, skip clip");
        return null;
    }

    /**
     * 反射调用宿主的一参回调并传 null（AudioClipData 的通知契约）。
     *
     * 宿主 Kotlin lambda / 匿名实现类通常是包级私有，Method.invoke 直接调用会抛
     * IllegalAccessException，因此先 setAccessible(true)。返回值表示回调是否真的执行：
     * 只有 true 才允许记 “callback(null)” 命中日志，失败时 WARN + 返回 false，
     * 不再静默吞异常、也不再谎报回调已通知。
     */
    static boolean invokeCallbackNull(XposedModule module, Feature f, Object callback) {
        if (callback == null) {
            return false;
        }
        try {
            Method invoke = findInvokeMethod(callback.getClass());
            if (invoke == null) {
                HookKit.warn(module, f.id + " callback invoke(Object) not found: "
                        + callback.getClass().getName());
                return false;
            }
            invoke.setAccessible(true);
            invoke.invoke(callback, new Object[]{null});
            return true;
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " callback(null) failed: " + t);
            return false;
        }
    }

    /** 在类层次与接口里找一参 invoke（宿主回调的声明类可能不可见，必须 setAccessible 后才可调用）。 */
    private static Method findInvokeMethod(Class<?> cls) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod("invoke", Object.class);
            } catch (NoSuchMethodException ignored) {
                // 继续向上/向外找
            } catch (Throwable ignored) {
                return null;
            }
        }
        Class<?>[] interfaces = cls.getInterfaces();
        if (interfaces != null) {
            for (Class<?> itf : interfaces) {
                Method m = findInvokeMethod(itf);
                if (m != null) {
                    return m;
                }
            }
        }
        return null;
    }
}
