package com.cloudmusictool.app.hooks;

import android.os.Bundle;

import com.cloudmusictool.app.Config;
import com.cloudmusictool.app.Feature;
import com.cloudmusictool.app.HookState;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;

/**
 * 9.6.13 运行时 ClassLoader 钩子引导器。
 *
 * 网易云 9.6.13 运行时使用 Tinker 的 DelegateLastClassLoader 加载热修 dex
 * （/data/user/0/&lt;pkg&gt;/tinker/patch-xxx/dex/tinker_classN.apk）。启动阶段
 * onPackageReady 拿到的 ClassLoader 是 base 副本，直接挂 hook 会全部
 * registered 但零命中；而第一个 Activity 的 getClass().getClassLoader()
 * 是运行时 ClassLoader（Tinker 设备命中热修类，非 Tinker 设备命中原类）。
 *
 * 因此需要运行时 ClassLoader 的功能不再各自安装 Activity#onCreate 钩子，
 * 而是：
 * <ol>
 *   <li>在启动安装阶段调用 {@link #register(Feature, Installer, Config)} 登记；</li>
 *   <li>调用 {@link #ensureInstalled(XposedModule, ClassLoader)} 安装唯一的
 *       Activity#onCreate 引导钩子。</li>
 * </ol>
 * 首个 Activity#onCreate 时用运行时 ClassLoader 依次执行已登记的 installer。
 * 引导钩子本身不归属任何 Feature，不计入任何 Feature 的 installed/hit。
 *
 * 约定：
 * - 每个 Feature 最多登记一个 installer，重复登记忽略（保留首个）；
 * - installer 独立 try/catch，单个失败不影响其他；
 * - installer 内部自行调用 {@link HookState#finish(Feature)}；引导器在
 *   finally 中兜底再调用一次，避免 installer 抛异常时 unavailable 状态残留。
 */
public final class RuntimeHookBootstrap {

    /** 用运行时 ClassLoader 安装某个 Feature 的钩子。 */
    @FunctionalInterface
    public interface Installer {
        void install(XposedModule module, ClassLoader runtimeCl, Config config);
    }

    private static final class Registration {
        final Feature feature;
        final Installer installer;
        final Config config;

        Registration(Feature feature, Installer installer, Config config) {
            this.feature = feature;
            this.installer = installer;
            this.config = config;
        }
    }

    /** 按 Feature 登记；EnumMap 保证 installer 的执行顺序与 Feature 枚举一致。 */
    private static final Map<Feature, Registration> sInstallers = new EnumMap<>(Feature.class);

    /** Activity#onCreate 引导钩子只安装一次。 */
    private static final AtomicBoolean sBootstrapInstalled = new AtomicBoolean();
    /** 运行时 installer 只执行一次（多个 Activity 的 onCreate 只认第一个）。 */
    private static final AtomicBoolean sRuntimeInstalled = new AtomicBoolean();

    private RuntimeHookBootstrap() {
    }

    /**
     * 登记一个 Feature 的运行时 installer。必须在启动安装阶段调用。
     * 重复登记同一 Feature 时保留首个，避免重复 hook。
     */
    public static void register(Feature feature, Installer installer, Config config) {
        if (feature == null || installer == null || config == null) {
            return;
        }
        synchronized (sInstallers) {
            if (!sInstallers.containsKey(feature)) {
                sInstallers.put(feature, new Registration(feature, installer, config));
            }
        }
    }

    /**
     * 安装 Activity#onCreate 引导钩子；幂等，可被多个 Feature 的 install 重复调用。
     * 找不到 Activity/onCreate 或安装失败时允许后续调用重试。
     */
    public static void ensureInstalled(XposedModule module, ClassLoader bootCl) {
        if (module == null || bootCl == null) {
            return;
        }
        if (!sBootstrapInstalled.compareAndSet(false, true)) {
            return;
        }
        try {
            Class<?> activityCls = HookKit.findClass(module, null, bootCl, "android.app.Activity");
            if (activityCls == null) {
                sBootstrapInstalled.set(false);
                return;
            }
            Method onCreate = HookKit.findMethodQuiet(activityCls, "onCreate", Bundle.class);
            if (onCreate == null) {
                sBootstrapInstalled.set(false);
                return;
            }
            boolean hooked = HookKit.install(module, null, "runtime/bootstrap", onCreate, chain -> {
                try {
                    Object self = chain.getThisObject();
                    ClassLoader runtimeCl = self != null
                            ? self.getClass().getClassLoader() : bootCl;
                    if (runtimeCl != null) {
                        installRuntimeHooks(module, runtimeCl);
                    }
                } catch (Throwable t) {
                    HookKit.error(module, "runtime hooks bootstrap failed", t);
                }
                return chain.proceed();
            });
            if (!hooked) {
                sBootstrapInstalled.set(false);
            }
        } catch (Throwable t) {
            sBootstrapInstalled.set(false);
            HookKit.error(module, "runtime hooks bootstrap install failed", t);
        }
    }

    private static void installRuntimeHooks(XposedModule module, ClassLoader runtimeCl) {
        if (!sRuntimeInstalled.compareAndSet(false, true)) {
            return;
        }
        List<Registration> snapshot;
        synchronized (sInstallers) {
            snapshot = new ArrayList<>(sInstallers.values());
        }
        if (snapshot.isEmpty()) {
            // 允许后续 Activity 在 installer 登记后重试。
            sRuntimeInstalled.set(false);
            return;
        }
        // 保持 2.4.3 的 UI-04 诊断日志；没有 UI-04 时用通用前缀。
        boolean hasUi04 = false;
        for (Registration registration : snapshot) {
            if (registration.feature == Feature.TOP_CHANNELS) {
                hasUi04 = true;
                break;
            }
        }
        HookKit.info(module, (hasUi04 ? "UI-04 runtime loader " : "runtime loader ") + runtimeCl);

        int installed = 0;
        int failed = 0;
        for (Registration registration : snapshot) {
            try {
                registration.installer.install(module, runtimeCl, registration.config);
                installed++;
            } catch (Throwable t) {
                failed++;
                HookKit.error(module, registration.feature.id + " runtime install failed", t);
            } finally {
                HookState.finish(registration.feature);
            }
        }
        HookKit.info(module, "runtime hooks installed: " + installed);
        if (failed > 0) {
            HookKit.warn(module, "runtime hooks failed: " + failed);
        }
    }
}
