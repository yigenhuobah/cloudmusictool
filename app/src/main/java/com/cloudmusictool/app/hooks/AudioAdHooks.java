package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;

import java.lang.reflect.Method;

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
                Method d = HookKit.findByShape(manager, "d", String.class, int.class, long.class);
                if (d == null) {
                    d = HookKit.findByNameAndArity(manager, "d", 5);
                }
                HookKit.install(module, f, "AdAudioClipManager#d", d, chain -> {
                    try {
                        Object callback = chain.getArg(3);
                        invokeCallbackNull(callback);
                        HookKit.hit(module, f, "AdAudioClipManager.d -> callback(null), skip clip");
                        return null;
                    } catch (Throwable t) {
                        HookKit.error(module, f.id + " intercept error AdAudioClipManager#d", t);
                        return chain.proceed();
                    }
                });

                Method c = HookKit.findByNameAndArity(manager, "c", 5);
                HookKit.install(module, f, "AdAudioClipManager#c", c, chain -> {
                    try {
                        HookKit.hit(module, f, "AdAudioClipManager.c -> skip clip playback");
                        return Boolean.FALSE;
                    } catch (Throwable t) {
                        HookKit.error(module, f.id + " intercept error AdAudioClipManager#c", t);
                        return chain.proceed();
                    }
                });
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " AdAudioClipManager hook failed", t);
        }

        try {
            Class<?> podcast = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.module.ad.podcast.AdPodcastPlayerPageHelper");
            if (podcast != null) {
                Method h = adCls != null
                        ? HookKit.findMethod(module, f, podcast, "h", adCls)
                        : HookKit.findByNameAndArity(podcast, "h", 1);
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
                String zName = HookKit.mappedMethodName(videoHelper, "z");
                Method z = HookKit.findByShape(videoHelper, zName, long.class, long.class);
                if (z == null) {
                    z = HookKit.findByNameAndArity(videoHelper, zName, 4);
                }
                HookKit.install(module, f, "PodcastVideoAdHelperImpl#z", z,
                        HookKit.skipVoid(module, f, "PodcastVideoAdHelperImpl.z"));
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " PodcastVideoAdHelperImpl hook failed", t);
        }
    }

    private static void invokeCallbackNull(Object callback) {
        if (callback == null) {
            return;
        }
        try {
            Method invoke = callback.getClass().getMethod("invoke", Object.class);
            invoke.invoke(callback, new Object[]{null});
        } catch (Throwable ignored) {
        }
    }
}
