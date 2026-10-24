package com.cloudmusictool.app.hooks;

import android.util.Log;

import com.cloudmusictool.app.Config;
import com.cloudmusictool.app.Feature;
import com.cloudmusictool.app.HookState;
import com.cloudmusictool.app.dexkit.DexKitLocator;
import com.cloudmusictool.app.profile.MountResolver;
import com.cloudmusictool.app.profile.MountSpec;
import com.cloudmusictool.app.profile.ProfileRegistry;
import com.cloudmusictool.app.profile.TargetProfile;
import com.cloudmusictool.app.profile.TypeResolver;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * HookKit 工具类。
 * 约束：
 * 1) 先走硬编码 Class.forName / getDeclaredMethod；失败则调用 DexKitLocator 进行特征锚点兜底。
 * 2) 定位/hook 失败只记日志并返回 null/false，绝不向外抛异常。
 * 3) 每个 intercept 体整段包在 try/catch(Throwable) 内，catch 后记日志并 proceed 原逻辑。
 * 4) 命中日志固定 "<功能id> hit ..."，tag 固定 CloudMusicTool；按 feature 限流（5 秒 1 条，首次必打）。
 * 5) 严格禁止引用任何 androidx 类型。
 */
final class HookKit {

    static final String TAG = "CloudMusicTool";

    private static final String LOADING_AD_MANAGER =
            "com.netease.cloudmusic.module.ad.LoadingAdManager";
    private static final long HIT_WINDOW_MS = 5000L;
    private static final Map<String, long[]> HIT_STATE = new ConcurrentHashMap<>();
    /** HIT_STATE 无界治理阈值：entry 超过即整表清空重建。 */
    private static final int HIT_STATE_MAX_ENTRIES = 512;
    /** 清空告警 warning-once：进程生命周期内只打一次。 */
    private static final AtomicBoolean HIT_STATE_CLEAR_WARNED = new AtomicBoolean(false);

    /**
     * Feature 可空路径的固定日志前缀：目前仅 ABTest 通道使用（ABTEST 不是 Feature，
     * 不参与 HookState 的 Feature 级计数），取值与 Profile 的 ABTEST 特征列一致。
     */
    private static final String NULL_FEATURE_PREFIX = "ABTEST";

    private HookKit() {
    }

    /** 日志前缀：f 为 null 时（ABTest 通道）用固定标识，其余沿用 feature id。 */
    private static String logPrefix(Feature f) {
        return f != null ? f.id : NULL_FEATURE_PREFIX;
    }

    static void info(XposedModule module, String msg) {
        module.log(Log.INFO, TAG, msg);
    }

    static void warn(XposedModule module, String msg) {
        module.log(Log.WARN, TAG, msg);
    }

    static void error(XposedModule module, String msg, Throwable t) {
        module.log(Log.ERROR, TAG, msg, t);
    }

    static Class<?> findClass(XposedModule module, Feature f, ClassLoader cl, String name, String... anchors) {
        boolean selftest = Config.dexKitSelfTest()
                && f == Feature.SPLASH_AD
                && LOADING_AD_MANAGER.equals(name);
        if (!selftest) {
            // 1) 优先用活动 Profile 的坐标（多版本架构）。
            Class<?> profiled = resolveProfileClass(module, f, cl, name);
            if (profiled != null) {
                return profiled;
            }
            // 2) Profile 未命中/解析失败：保持原有硬编码路径。
            try {
                return Class.forName(name, false, cl);
            } catch (Throwable ignored) {
                // fall through to DexKit
            }
        }
        if (anchors != null && anchors.length > 0) {
            try {
                long start = System.currentTimeMillis();
                Class<?> fallback = DexKitLocator.findClass(cl, name, anchors);
                long elapsed = System.currentTimeMillis() - start;
                if (fallback != null) {
                    if (selftest) {
                        info(module, "DexKit selftest located=" + fallback.getName()
                                + " elapsed=" + elapsed + "ms");
                    }
                    info(module, logPrefix(f) + " DexKit fallback located class " + name + " -> " + fallback.getName());
                    return fallback;
                }
                if (selftest) {
                    info(module, "DexKit selftest located=null elapsed=" + elapsed + "ms");
                }
            } catch (Throwable dexT) {
                warn(module, logPrefix(f) + " DexKit class query failed for " + name + ": " + dexT.getMessage());
            }
        } else if (selftest) {
            warn(module, "DexKit selftest requested but no anchors for " + name);
        }
        warn(module, logPrefix(f) + " skip: class not found " + name);
        if (f != null) {
            HookState.markNotFound(f, "class:" + name, isCriticalMount(name, null));
            HookState.note(f, "类未找到 " + name);
        }
        return null;
    }

    static Class<?> findClass(XposedModule module, Feature f, ClassLoader cl, String name) {
        return findClass(module, f, cl, name, (String[]) null);
    }

    /**
     * 按活动 Profile 解析类；无 Profile / 无该坐标 / 解析失败均返回 null，
     * 由调用方继续走原有硬编码 + DexKit 路径。不在此处登记 HookState，避免重复计数。
     */
    private static Class<?> resolveProfileClass(XposedModule module, Feature f, ClassLoader cl, String name) {
        try {
            TargetProfile profile = ProfileRegistry.active();
            if (profile == null) {
                return null;
            }
            MountSpec spec = profile.findClassSpec(name);
            if (spec == null) {
                return null;
            }
            Class<?> resolved = MountResolver.resolveClass(spec, cl);
            if (resolved != null) {
                info(module, logPrefix(f) + " profile class " + name + " -> " + spec.id);
            }
            return resolved;
        } catch (Throwable t) {
            warn(module, logPrefix(f) + " profile class resolve error " + name + ": " + t.getMessage());
            return null;
        }
    }

    /**
     * 按「hook 请求的旧混淆方法名」在活动 Profile 中找映射坐标。
     *
     * hook 文件写死的是 9.6.08 的方法名（如 LoadingAdManager#E）；9.6.13 的
     * Profile 覆盖里 methodName 已换成实际名（如 H），logicalName 保留旧逻辑名。
     * 命中且 methodName 与请求名不同时返回该 spec（以 Profile 为准）；未命中 /
     * 名字一致（走 findBestMethodSpec 常规路径）时返回 null。
     */
    private static MountSpec findSpecByRequestedName(Class<?> cls, String name) {
        TargetProfile profile = ProfileRegistry.active();
        if (profile == null || cls == null || name == null) {
            return null;
        }
        try {
            MountSpec spec = profile.findByLogicalName(cls.getName(), name);
            if (spec == null || spec.methodName == null || name.equals(spec.methodName)) {
                return null;
            }
            return spec;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 把 hook 请求的旧混淆方法名映射成活动 Profile 里的实际方法名。
     * 供 hook 文件里按名扫描（getDeclaredMethods）的调用点使用：
     * 9.6.13 时 "Tc" -> "Pc"，9.6.08 无覆盖时原样返回请求名。
     */
    static String mappedMethodName(Class<?> cls, String requested) {
        MountSpec spec = findSpecByRequestedName(cls, requested);
        return spec != null ? spec.methodName : requested;
    }

    /**
     * 按活动 Profile 解析方法；解析结果必须与调用方期望的参数/返回类型一致，
     * 否则返回 null 交给原有逻辑，避免 Profile 与 hook 期望漂移时误挂。
     */
    private static Method resolveProfileMethod(XposedModule module, Feature f, Class<?> cls,
                                               String name, Class<?> returnType, Class<?>[] params) {
        if (cls == null) {
            return null;
        }
        try {
            // 1) 旧方法名 -> Profile 实际坐标（方法名/参数/返回类型以 spec 为准）。
            MountSpec mapped = findSpecByRequestedName(cls, name);
            if (mapped != null) {
                Method resolved = MountResolver.resolveMethod(mapped, cls, cls.getClassLoader());
                if (resolved != null) {
                    info(module, logPrefix(f) + " profile mapped method " + cls.getName() + "#" + name
                            + " -> " + mapped.methodName + " (" + mapped.id + ")");
                    return resolved;
                }
            }
            // 2) 常规路径：请求名即 methodName，且与 hook 期望的参数/返回一致才使用。
            MountSpec spec = findBestMethodSpec(cls, name, params);
            if (spec == null) {
                return null;
            }
            Method resolved = MountResolver.resolveMethod(spec, cls, cls.getClassLoader());
            if (resolved == null) {
                return null;
            }
            if (params != null && !sameParams(resolved.getParameterTypes(), params)) {
                return null;
            }
            if (returnType != null && !sameType(returnType, resolved.getReturnType())) {
                return null;
            }
            info(module, logPrefix(f) + " profile method " + cls.getName() + "#" + name + " -> " + spec.id);
            return resolved;
        } catch (Throwable t) {
            warn(module, logPrefix(f) + " profile method resolve error "
                    + cls.getName() + "#" + name + ": " + t.getMessage());
            return null;
        }
    }

    /**
     * 无 module/feature 上下文的 Profile 方法解析，供 findMethodQuiet / findByNameQuiet 使用。
     * 命中后同样校验参数/返回类型，失败返回 null 由调用方回退。
     */
    private static Method resolveProfileMethodQuiet(Class<?> cls, String name,
                                                    Class<?> returnType, Class<?>[] params) {
        if (cls == null) {
            return null;
        }
        try {
            // 1) 旧方法名 -> Profile 实际坐标（以 spec 为准）。
            MountSpec mapped = findSpecByRequestedName(cls, name);
            if (mapped != null) {
                Method resolved = MountResolver.resolveMethod(mapped, cls, cls.getClassLoader());
                if (resolved != null) {
                    Log.i(TAG, "profile mapped method " + cls.getName() + "#" + name
                            + " -> " + mapped.methodName + " (" + mapped.id + ") (quiet)");
                    return resolved;
                }
            }
            // 2) 常规路径。
            MountSpec spec = findBestMethodSpec(cls, name, params);
            if (spec == null) {
                return null;
            }
            Method resolved = MountResolver.resolveMethod(spec, cls, cls.getClassLoader());
            if (resolved == null) {
                return null;
            }
            if (params != null && !sameParams(resolved.getParameterTypes(), params)) {
                return null;
            }
            if (returnType != null && !sameType(returnType, resolved.getReturnType())) {
                return null;
            }
            Log.i(TAG, "profile method " + cls.getName() + "#" + name
                    + " -> " + spec.id + " (quiet)");
            return resolved;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 在 Profile 中找 (class, method) 坐标；params 非空时优先返回参数完全匹配的重载，
     * 没有精确匹配则返回第一个同名坐标（由调用方校验后决定是否回退）。
     */
    private static MountSpec findBestMethodSpec(Class<?> cls, String name, Class<?>[] params) {
        TargetProfile profile = ProfileRegistry.active();
        if (profile == null) {
            return null;
        }
        String className = cls.getName();
        ClassLoader cl = cls.getClassLoader();
        MountSpec first = null;
        for (MountSpec spec : profile.mounts) {
            if (!name.equals(spec.methodName) || spec.classCandidates == null) {
                continue;
            }
            boolean classMatch = false;
            for (String candidate : spec.classCandidates) {
                if (className.equals(candidate)) {
                    classMatch = true;
                    break;
                }
            }
            if (!classMatch) {
                continue;
            }
            if (first == null) {
                first = spec;
            }
            if (params == null) {
                return spec;
            }
            if (spec.paramTypes == null || spec.paramTypes.length != params.length) {
                continue;
            }
            boolean match = true;
            for (int i = 0; i < params.length; i++) {
                Class<?> expected = TypeResolver.resolve(spec.paramTypes[i], cl);
                if (expected == null || !sameType(expected, params[i])) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return spec;
            }
        }
        return first;
    }

    private static boolean sameParams(Class<?>[] actual, Class<?>[] expected) {
        if (actual.length != expected.length) {
            return false;
        }
        for (int i = 0; i < actual.length; i++) {
            if (!sameType(expected[i], actual[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameType(Class<?> a, Class<?> b) {
        return a == b || a.equals(b);
    }

    /**
     * 判断失败的挂载点是否 critical。
     * 类级失败：该类任一坐标 critical 即视为 critical（类缺失会带走全部方法）。
     * 方法级失败：只认精确的 (class, method) 坐标。
     * 无 Profile / 未收录的挂载点一律非 critical，不触发降级。
     */
    private static boolean isCriticalMount(String className, String methodName) {
        if (className == null) {
            return false;
        }
        try {
            TargetProfile profile = ProfileRegistry.active();
            if (profile == null) {
                return false;
            }
            if (methodName != null) {
                MountSpec spec = profile.findMethodSpec(className, methodName);
                return spec != null && spec.critical;
            }
            for (MountSpec spec : profile.mounts) {
                if (spec.classCandidates == null) {
                    continue;
                }
                for (String candidate : spec.classCandidates) {
                    if (className.equals(candidate) && spec.critical) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    static Method findMethod(XposedModule module, Feature f, Class<?> cls, String name,
                             Class<?> returnType, Class<?>[] params, String... anchors) {
        Method m = resolveProfileMethod(module, f, cls, name, returnType, params);
        if (m == null) {
            m = findMethodQuiet(cls, name, params);
        }
        if (m == null && cls != null && anchors != null && anchors.length > 0) {
            try {
                ClassLoader cl = cls.getClassLoader();
                m = DexKitLocator.findMethod(cl, cls.getName(), name, returnType, params, anchors);
                if (m != null) {
                    info(module, logPrefix(f) + " DexKit fallback located method " + cls.getName() + "#" + name);
                    return m;
                }
            } catch (Throwable dexT) {
                warn(module, logPrefix(f) + " DexKit method query failed: " + dexT.getMessage());
            }
        }
        if (m == null) {
            String clsName = cls == null ? "?" : cls.getName();
            warn(module, logPrefix(f) + " skip: method not found " + clsName + "#" + name);
            if (f != null) {
                HookState.markNotFound(f, "method:" + clsName + "#" + name,
                        isCriticalMount(cls == null ? null : clsName, name));
                HookState.note(f, "方法未找到 " + name);
            }
        }
        return m;
    }

    static Method findMethod(XposedModule module, Feature f, Class<?> cls, String name,
                             Class<?>... params) {
        return findMethod(module, f, cls, name, null, params, (String[]) null);
    }

    static Method findMethod(XposedModule module, Feature f, Class<?> cls, String name) {
        Method m = resolveProfileMethod(module, f, cls, name, null, null);
        if (m == null) {
            m = findByNameQuiet(cls, name);
        }
        if (m == null) {
            String clsName = cls == null ? "?" : cls.getName();
            warn(module, f.id + " skip: method not found " + clsName + "#" + name);
            HookState.markNotFound(f, "method:" + clsName + "#" + name,
                    isCriticalMount(cls == null ? null : clsName, name));
            HookState.note(f, "方法未找到 " + name);
        }
        return m;
    }

    static Method findMethodWithAnchors(XposedModule module, Feature f, Class<?> cls, String name,
                                        String... anchors) {
        Method m = resolveProfileMethod(module, f, cls, name, null, null);
        if (m == null) {
            m = findByNameQuiet(cls, name);
        }
        if (m == null && cls != null && anchors != null && anchors.length > 0) {
            try {
                ClassLoader cl = cls.getClassLoader();
                m = DexKitLocator.findMethod(cl, cls.getName(), name, null, null, anchors);
                if (m != null) {
                    info(module, f.id + " DexKit fallback located method " + cls.getName() + "#" + name);
                    return m;
                }
            } catch (Throwable dexT) {
                warn(module, f.id + " DexKit method query failed: " + dexT.getMessage());
            }
        }
        if (m == null) {
            String clsName = cls == null ? "?" : cls.getName();
            warn(module, f.id + " skip: method not found " + clsName + "#" + name);
            HookState.markNotFound(f, "method:" + clsName + "#" + name,
                    isCriticalMount(cls == null ? null : clsName, name));
            HookState.note(f, "方法未找到 " + name);
        }
        return m;
    }

    static Method findByNameQuiet(Class<?> cls, String name) {
        if (cls == null) {
            return null;
        }
        Method profiled = resolveProfileMethodQuiet(cls, name, null, null);
        if (profiled != null) {
            return profiled;
        }
        try {
            Method found = null;
            for (Method m : cls.getDeclaredMethods()) {
                if (!name.equals(m.getName())) {
                    continue;
                }
                if (found != null) {
                    return null;
                }
                found = m;
            }
            return found;
        } catch (Throwable t) {
            return null;
        }
    }

    static Method findByShape(Class<?> cls, String name, Class<?>... leading) {
        if (cls == null) {
            return null;
        }
        try {
            Method found = null;
            for (Method m : cls.getDeclaredMethods()) {
                if (!name.equals(m.getName())) {
                    continue;
                }
                Class<?>[] p = m.getParameterTypes();
                if (p.length < leading.length) {
                    continue;
                }
                boolean ok = true;
                for (int i = 0; i < leading.length; i++) {
                    if (p[i] != leading[i]) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) {
                    continue;
                }
                if (found != null) {
                    return null;
                }
                found = m;
            }
            return found;
        } catch (Throwable t) {
            return null;
        }
    }

    static Method findByNameAndArity(Class<?> cls, String name, int arity) {
        if (cls == null) {
            return null;
        }
        try {
            Method found = null;
            for (Method m : cls.getDeclaredMethods()) {
                if (!name.equals(m.getName()) || m.getParameterTypes().length != arity) {
                    continue;
                }
                if (found != null) {
                    return null;
                }
                found = m;
            }
            return found;
        } catch (Throwable t) {
            return null;
        }
    }

    static Method findMethodQuiet(Class<?> cls, String name, Class<?>... params) {
        if (cls == null) {
            return null;
        }
        Method profiled = resolveProfileMethodQuiet(cls, name, null, params);
        if (profiled != null) {
            return profiled;
        }
        try {
            return cls.getDeclaredMethod(name, params);
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean install(XposedModule module, Feature f, String label, Method m,
                           XposedInterface.Hooker hooker) {
        if (m == null) {
            return false;
        }
        return install(module, f, label, (Executable) m, hooker);
    }

    static boolean install(XposedModule module, Feature f, String label,
                           Executable m,
                           XposedInterface.Hooker hooker) {
        if (m == null) {
            return false;
        }
        if (!validateReturnType(module, f, label, m, hooker)) {
            return false;
        }
        try {
            module.hook(m)
                    .setId(logPrefix(f) + "#" + label)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(hooker);
            if (f != null) {
                HookState.installed(f, label);
            }
            info(module, logPrefix(f) + " registered " + label);
            return true;
        } catch (Throwable t) {
            error(module, logPrefix(f) + " hook failed " + label, t);
            if (f != null) {
                HookState.markNotFound(f, "hook:" + label, isCriticalExecutable(m));
                HookState.note(f, "hook 失败 " + label);
            }
            return false;
        }
    }

    /**
     * 安装前校验返回值：constBool 只能挂 boolean/Boolean，skipVoid 只能挂 void 方法。
     * 不匹配时拒绝安装并 WARN，避免“hook 静默不生效”或返回 null 导致宿主 NPE。
     */
    private static boolean validateReturnType(XposedModule module, Feature f, String label,
                                              Executable m, XposedInterface.Hooker hooker) {
        if (!(m instanceof Method)) {
            return true;
        }
        Class<?> rt = ((Method) m).getReturnType();
        if (hooker instanceof ConstBoolHooker) {
            if (rt != boolean.class && rt != Boolean.class) {
                warn(module, logPrefix(f) + " return type mismatch: " + label + " -> "
                        + rt.getName() + " (constBool requires boolean); not installed");
                if (f != null) {
                    HookState.markNotFound(f, "rettype:" + label, isCriticalExecutable(m));
                    HookState.note(f, "返回类型不匹配 " + label);
                }
                return false;
            }
        } else if (hooker instanceof SkipVoidHooker) {
            if (rt != void.class) {
                warn(module, logPrefix(f) + " return type mismatch: " + label + " -> "
                        + rt.getName() + " (skipVoid requires void); not installed");
                if (f != null) {
                    HookState.markNotFound(f, "rettype:" + label, isCriticalExecutable(m));
                    HookState.note(f, "返回类型不匹配 " + label);
                }
                return false;
            }
        }
        return true;
    }

    /** hook 安装失败时判断该 Executable 是否对应 Profile 中的 critical 坐标。 */
    private static boolean isCriticalExecutable(Executable m) {
        if (!(m instanceof Method)) {
            return false;
        }
        Method method = (Method) m;
        return isCriticalMount(method.getDeclaringClass().getName(), method.getName());
    }

    static void hit(XposedModule module, Feature f, String detail) {
        // 旧签名：限流键缺省取 detail，键形与重构前完全一致，清单外调用方行为不变。
        hit(module, f, detail, detail);
    }

    /**
     * 命中记账 + 限流日志。按 label 限流：每个挂载点首次必打，之后 5 秒最多 1 条。
     * label 作限流键（须为稳定前缀，不含随 payload 变化的量），detail 仅在真正打日志时拼接；
     * 快速路径（窗口内被限流）不拼 detail、不进 map 写路径。entry 数超
     * {@link #HIT_STATE_MAX_ENTRIES} 时整表清空重建，防止 detail 含变化量的调用方
     * 造成无界慢泄漏。
     */
    static void hit(XposedModule module, Feature f, String label, String detail) {
        long now = System.currentTimeMillis();
        String key = f.id + "|" + label;
        long[] state = HIT_STATE.get(key);
        if (state != null && state[1] != 0L && now - state[0] < HIT_WINDOW_MS) {
            // 快速路径：本次不打日志，只记 Feature 级命中数。
            HookState.hit(f);
            return;
        }
        if (HIT_STATE.size() > HIT_STATE_MAX_ENTRIES) {
            HIT_STATE.clear();
            if (HIT_STATE_CLEAR_WARNED.compareAndSet(false, true)) {
                warn(module, "HIT_STATE entries exceed " + HIT_STATE_MAX_ENTRIES + ", cleared");
            }
        }
        state = HIT_STATE.computeIfAbsent(key, k -> new long[]{0L, 0L});
        boolean log;
        synchronized (state) {
            log = state[1] == 0L || now - state[0] >= HIT_WINDOW_MS;
            if (log) {
                state[0] = now;
                state[1]++;
            }
        }
        HookState.hit(f);
        if (log) {
            module.log(Log.INFO, TAG, f.id + " hit " + detail);
        }
    }

    static XposedInterface.Hooker skipVoid(XposedModule module, Feature f, String detail) {
        return new SkipVoidHooker(module, f, detail);
    }

    static XposedInterface.Hooker constBool(XposedModule module, Feature f, String detail, boolean value) {
        return new ConstBoolHooker(module, f, detail, value);
    }

    /**
     * 按名定位方法并安装 skipVoid hook：定位 → 返回类型校验（要求精确 void）→
     * 安装 → 登记（HookState.installed / markNotFound）全在中心层完成，
     * hook 文件不再自写 getReturnType 检查样板。
     */
    static boolean installVoidByName(XposedModule module, Feature f, Class<?> cls,
                                     String name, String label) {
        Method m = findMethod(module, f, cls, name);
        return install(module, f, label, m, skipVoid(module, f, label));
    }

    /**
     * 按名定位 boolean 无参方法并安装 const-false hook：定位 → 返回类型校验（要求
     * boolean/Boolean）→ 安装 → 登记 全在中心层完成，hook 文件不再自写检查样板。
     */
    static boolean installConstFalse(XposedModule module, Feature f, Class<?> cls,
                                     String name, String label) {
        Method m = findMethod(module, f, cls, name, boolean.class, new Class<?>[0]);
        return install(module, f, label, m, constBool(module, f, label + " -> false", false));
    }

    private static final class SkipVoidHooker implements XposedInterface.Hooker {
        private final XposedModule module;
        private final Feature feature;
        private final String detail;

        SkipVoidHooker(XposedModule module, Feature feature, String detail) {
            this.module = module;
            this.feature = feature;
            this.detail = detail;
        }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            try {
                hit(module, feature, detail);
                return null;
            } catch (Throwable t) {
                error(module, feature.id + " intercept error " + detail, t);
                return chain.proceed();
            }
        }
    }

    private static final class ConstBoolHooker implements XposedInterface.Hooker {
        private final XposedModule module;
        private final Feature feature;
        private final String detail;
        private final boolean value;

        ConstBoolHooker(XposedModule module, Feature feature, String detail, boolean value) {
            this.module = module;
            this.feature = feature;
            this.detail = detail;
            this.value = value;
        }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            try {
                hit(module, feature, detail);
                return value ? Boolean.TRUE : Boolean.FALSE;
            } catch (Throwable t) {
                error(module, feature.id + " intercept error " + detail, t);
                return chain.proceed();
            }
        }
    }

    // ------------------------------------------------------------------
    // 反射样板（原分散在各 hook 文件，统一收口）
    // ------------------------------------------------------------------

    static boolean shouldKeepTab(Object item, Set<String> keepSet) {
        return shouldKeep(item, keepSet,
                new String[]{"getTitle", "getTabName", "getName", "getCode", "getTabTitle", "getTabCode"});
    }

    static boolean shouldKeepItem(Object item, Set<String> keepSet) {
        return shouldKeep(item, keepSet,
                new String[]{"getTitle", "getChannelCode", "getCode", "getName"});
    }

    private static boolean shouldKeep(Object item, Set<String> keepSet, String[] methodNames) {
        if (item == null || keepSet == null || keepSet.isEmpty()) {
            return false;
        }
        for (String name : methodNames) {
            Object val = invokeNoArg(item, name);
            if (val != null && keepSet.contains(val.toString().trim())) {
                return true;
            }
        }
        return false;
    }

    static String firstStringGetter(Object item) {
        if (item == null) {
            return null;
        }
        String[] names = {"getTitle", "getName", "getTabName"};
        for (String n : names) {
            Object v = invokeNoArg(item, n);
            if (v != null) {
                return v.toString().trim();
            }
        }
        return null;
    }

    static Field findField(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    static Object invokeNoArg(Object target, String method) {
        if (target == null) {
            return null;
        }
        try {
            Method m = target.getClass().getMethod(method);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable ignored) {
        }
        try {
            Method m = target.getClass().getDeclaredMethod(method);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static void hookNull(XposedModule module, Feature f, Class<?> cls, String name) {
        Method m = findMethod(module, f, cls, name);
        install(module, f, cls.getSimpleName() + "#" + name, m, chain -> {
            try {
                hit(module, f, cls.getSimpleName() + "." + name + " -> null");
                return null;
            } catch (Throwable t) {
                return chain.proceed();
            }
        });
    }
}
