package com.cloudmusictool.app.hooks;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.cloudmusictool.app.Config;
import com.cloudmusictool.app.Feature;
import com.cloudmusictool.app.HookState;
import com.cloudmusictool.app.SampleSize;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;

/**
 * EN-01「我的页自定义背景」。
 *
 * 9.6.08 的「我的」页有 RN / 原生 V4 两条分支（AB mine-rn-android）。
 * RN 分支的头部背景在 JS 里渲染，本地无法可靠压平；因此启用 EN-01 时
 * 强制走原生 MyMusicFragmentV4，并复用官方全屏背景链路：
 *
 * 1. 把 UserBackgroundInfo.validType 注入为 4（FullScreen），只改渲染
 *    决策输入，不碰 vip/showEntrance；
 * 2. 原生 manager 的 gh2/b 负责把背景图铺到 headerBgContainer；
 * 3. 本 hook 再把本地图片追加到 headerBgContainer，作为实际背景内容。
 *
 * 已知限制：原生 V4 内容区的部分卡片仍使用主题深色背景，不会完全透出
 * 背景图（头部与顶部区域是完整全屏效果）。
 */
public final class MineBackgroundHooks {

    private static final String TAG = HookKit.TAG;
    private static final String MANAGER =
            "com.netease.cloudmusic.vh.MyMusicProfileHeaderBgViewHolderManager";
    private static final String BG_INFO =
            "com.netease.cloudmusic.meta.virtual.UserBackgroundInfo";
    private static final String MINE_FRAGMENT =
            "com.netease.cloudmusic.fragment.MyMusicFragmentV4";
    /**
     * 非本人页 V4 与本人页 V4 继承同一个 MyMusicFragmentV4Base，也持有同一个
     * MyMusicProfileHeaderBgViewHolderManager。当前只匹配本人页 V4，未改变
     * 非本人页行为（见 findMineFragment 的 TODO）。
     */
    private static final String MINE_GUEST_FRAGMENT =
            "com.netease.cloudmusic.fragment.MyMusicGuestFragmentV4";
    private static final String MINE_PROXY =
            "com.netease.cloudmusic.fragment.MyMusicProxyFragment";
    private static final String SG =
            "com.netease.cloudmusic.utils.sg";

    private static final int FL_HEADER_IMAGE_FALLBACK = 0x7f09136e;

    private static final String TAG_IMAGE = "cloudmusictool.mine.bg.image";
    private static final String TAG_SCRIM = "cloudmusictool.mine.bg.scrim";

    /**
     * 官方 FullScreen 分支的内容区遮罩色：gd() 在 z15 条件（manager 类型 4 且
     * 背景图经官方加载器真实加载完成）成立时为 bottomAreaMask 设此色（约 10%
     * 白），内容区透出背景图。取值与 9.6.13 反编译的 ColorDrawable(452984831)
     * 完全一致。
     */
    static final int FULLSCREEN_CONTENT_SCRIM = 0x1AFFFFFF;
    /** 内容区遮罩 view 的资源名；旧版本不存在时整页增强静默降级为头部效果。 */
    static final String CONTENT_MASK_VIEW = "bottomAreaMask";

    /** 模块包名缺失时的兜底 URL；只要非空，宿主就会进入 FullScreen 分支。 */
    private static final String FULL_PIC_PLACEHOLDER =
            "content://local/cloudmusictool/mine_background";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean sLoading = new AtomicBoolean(false);
    private static final AtomicBoolean sApplying = new AtomicBoolean(false);

    private static volatile Bitmap sCachedBitmap;
    /** sCachedBitmap 对应 external 源文件的 {lastModified, length} 指纹;null 表示缓存经 provider 兜底读取,无源文件可 stat。 */
    private static volatile long[] sCachedFingerprint;
    private static volatile int sFlHeaderImageId;
    private static volatile int sContentMaskId;
    private static final AtomicBoolean sSgProbeHcLogged = new AtomicBoolean();
    private static final AtomicBoolean sSgProbeOtherLogged = new AtomicBoolean();
    private static final AtomicBoolean sHcProbeLogged = new AtomicBoolean();
    private static final AtomicBoolean sGuestFragmentLogged = new AtomicBoolean();

    private MineBackgroundHooks() {
    }

    /**
     * 9.6.13 运行时使用 Tinker DelegateLastClassLoader，启动阶段拿到的 ClassLoader
     * 与运行时不同。EN-01 的全部类钩子统一交给 {@link RuntimeHookBootstrap}，
     * 在首个 Activity#onCreate 用运行时 ClassLoader 安装。
     */
    public static void install(XposedModule module, ClassLoader cl, Config config) {
        RuntimeHookBootstrap.register(Feature.MINE_BACKGROUND,
                MineBackgroundHooks::installRuntimeHooks, config);
        RuntimeHookBootstrap.ensureInstalled(module, cl);
    }

    private static void installRuntimeHooks(XposedModule module, ClassLoader cl, Config config) {
        Feature f = Feature.MINE_BACKGROUND;
        installNativeManagerHooks(module, cl, f);
        installFullScreenInjection(module, f, cl);
        installProxyHook(module, f, cl);
        installProfileModelHooks(module, f, cl);
        installContentMaskHooks(module, cl, f);
        HookState.finish(f);
    }

    // ---------------------------------------------------------------- native

    private static void installNativeManagerHooks(XposedModule module, ClassLoader cl, Feature f) {
        Class<?> manager = HookKit.findClass(module, f, cl, MANAGER);
        if (manager == null) {
            return;
        }
        Method e = HookKit.findByNameAndArity(manager, "e", 1);
        if (e == null || e.getParameterTypes().length != 1
                || !BG_INFO.equals(e.getParameterTypes()[0].getName())) {
            HookKit.warn(module, f.id + " skip: " + MANAGER + "#e(UserBackgroundInfo) not found");
            HookState.markNotFound(f, "method:" + MANAGER + "#e", true);
            HookState.note(f, "方法未找到 e");
        } else {
            HookKit.install(module, f, "manager#e", e, chain -> {
                Object result = chain.proceed();
                try {
                    apply(module, f, chain.getThisObject());
                } catch (Throwable t) {
                    HookKit.warn(module, f.id + " manager#e apply failed: " + t.getMessage());
                }
                return result;
            });
        }

        Method onResume = HookKit.findMethodQuiet(manager, "onResume");
        if (onResume != null && onResume.getParameterTypes().length == 0) {
            HookKit.install(module, f, "manager#onResume", onResume, chain -> {
                Object result = chain.proceed();
                try {
                    apply(module, f, chain.getThisObject());
                } catch (Throwable t) {
                    HookKit.warn(module, f.id + " manager#onResume apply failed: " + t.getMessage());
                }
                return result;
            });
        } else {
            HookKit.warn(module, f.id + " skip: " + MANAGER + "#onResume not found");
        }
    }

    /**
     * 启用 EN-01 时强制「我的」页走原生 V4（AB mine-rn-android=false）。
     *
     * 9.6.13 的分支方法是 {@code kc()Z}（与 9.6.08 的 {@code oc()Z} opcode 完全
     * 一致）；9.6.13 的 {@code oc()V} 只启动协程，hook 它返回 false 没有意义，
     * 因此只接受「零参 + boolean 返回」的候选，绝不会误挂 {@code oc()V}。
     * 找不到 kc 时回退 9.6.08 的 oc()Z。
     */
    private static void installProxyHook(XposedModule module, Feature f, ClassLoader cl) {
        Class<?> proxy = HookKit.findClass(module, f, cl, MINE_PROXY);
        if (proxy == null) {
            return;
        }
        Method kc = selectProxyBranchMethod(proxy,
                HookKit.mappedMethodName(proxy, "kc"),
                HookKit.mappedMethodName(proxy, "oc"));
        if (kc == null) {
            HookKit.warn(module, f.id + " skip: " + MINE_PROXY
                    + "#kc()Z/oc()Z not found (oc()V is a coroutine starter, not hooked)");
            HookState.markNotFound(f, "method:" + MINE_PROXY + "#kc", true);
            HookState.note(f, "kc()Z/oc()Z 未找到");
            return;
        }
        boolean isKc = "kc".equals(kc.getName());
        String label = isKc ? "MyMusicProxyFragment#kc" : "MyMusicProxyFragment#oc";
        HookKit.install(module, f, label, kc, chain -> {
            HookKit.hit(module, f, isKc ? "proxy kc=false" : "proxy oc=false",
                    isKc ? "proxy kc=false (forced native Mine page)"
                            : "proxy oc=false (9.6.08 fallback, forced native Mine page)");
            return false;
        });
        if (isKc) {
            installProxyProbes(module, f, cl, proxy);
        }
    }

    /**
     * 纯函数（不依赖 Android/Xposed）：按优先级挑一个「零参 + boolean 返回」的
     * 分支方法。9.6.13 的 oc()V 返回 void，会被这里拒绝；同时存在多个同名
     * 候选时放弃，避免误挂重载。
     */
    static Method selectProxyBranchMethod(Class<?> proxy, String... names) {
        if (proxy == null || names == null) {
            return null;
        }
        for (String name : names) {
            if (name == null || name.isEmpty()) {
                continue;
            }
            Method found = null;
            for (Method m : proxy.getDeclaredMethods()) {
                if (!name.equals(m.getName()) || m.getParameterTypes().length != 0
                        || m.getReturnType() != boolean.class) {
                    continue;
                }
                if (found != null) {
                    found = null;
                    break;
                }
                found = m;
            }
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * 只读探针：不改变宿主行为，仅用于真机判断 hc 最终走 V4 还是 V3。
     *
     * - sg.s(JSONObject) 的返回值决定 hc 的 V4/V3 分支（true -> V4，false -> V3）；
     * - hc(Continuation) 的返回值就是最终创建的 Fragment（挂起时首次返回
     *   COROUTINE_SUSPENDED，恢复后会再次进入并返回 Fragment）。
     *
     * 保守策略：本版不全局强制 sg.s=true。sg.o(Z) 也调用 sg.s，强制 true 可能
     * 让无效缓存被当成有效；先看日志确认是否真的落到 V3，再决定是否做
     * 只针对 hc 调用路径的条件 hook。
     */
    private static void installProxyProbes(XposedModule module, Feature f, ClassLoader cl,
                                           Class<?> proxy) {
        try {
            Class<?> sg = HookKit.findClass(module, f, cl, SG);
            if (sg != null) {
                Method s = HookKit.findMethodQuiet(sg, "s", org.json.JSONObject.class);
                if (s != null && s.getReturnType() == boolean.class
                        && s.getParameterTypes().length == 1
                        && "org.json.JSONObject".equals(s.getParameterTypes()[0].getName())) {
                    HookKit.install(module, f, "sg#s(probe)", s, chain -> {
                        Object result = chain.proceed();
                        logSgProbe(module, result);
                        return result;
                    });
                }
            }
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " sg.s probe failed: " + t.getMessage());
        }

        try {
            Method hc = HookKit.findByNameAndArity(proxy, "hc", 1);
            if (hc != null && hc.getReturnType() == Object.class
                    && "kotlin.coroutines.Continuation"
                    .equals(hc.getParameterTypes()[0].getName())) {
                HookKit.install(module, f, "MyMusicProxyFragment#hc(probe)", hc, chain -> {
                    Object result = chain.proceed();
                    logHcProbe(module, result);
                    return result;
                });
            }
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " hc probe failed: " + t.getMessage());
        }
    }

    private static void logSgProbe(XposedModule module, Object result) {
        String caller = probeCaller();
        if (caller.startsWith("MyMusicProxyFragment#hc")) {
            if (sSgProbeHcLogged.compareAndSet(false, true)) {
                HookKit.info(module, "EN-01 probe sg.s=" + result + " caller=" + caller
                        + (Boolean.TRUE.equals(result) ? " (expected V4)" : " (expected V3)"));
            }
        } else if (sSgProbeOtherLogged.compareAndSet(false, true)) {
            HookKit.info(module, "EN-01 probe sg.s=" + result + " caller=" + caller);
        }
    }

    private static void logHcProbe(XposedModule module, Object result) {
        if (result == null) {
            return;
        }
        String name = result.getClass().getName();
        if (name.startsWith("com.netease.cloudmusic.fragment.MyMusicFragment")
                && sHcProbeLogged.compareAndSet(false, true)) {
            HookKit.info(module, "EN-01 probe hc fragment=" + name);
        }
    }

    /** 返回 sg.s 的首个业务调用方（hc 或 sg.o），用于区分 V4/V3 判定路径。 */
    private static String probeCaller() {
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            for (StackTraceElement e : stack) {
                if (MINE_PROXY.equals(e.getClassName())) {
                    return "MyMusicProxyFragment#" + e.getMethodName();
                }
            }
            for (StackTraceElement e : stack) {
                if (SG.equals(e.getClassName())) {
                    return "sg#" + e.getMethodName();
                }
            }
        } catch (Throwable ignored) {
        }
        return "unknown";
    }

    // ------------------------------------------------------- fullscreen data

    /**
     * 把 UserBackgroundInfo 的 validType 注入为 4（FullScreen）。
     *
     * 入口：
     * - pc2.e$a#B(JSONObject)：/api/user/page/pretend/info 响应解析；
     * - pc2.f#g(J)：SP my_music_sp 读取；
     * - pc2.f#s(J,String)：SP 写入。
     *
     * 不读也不写 vip/showEntrance；权益校验只在「点入口->弹窗->上传」链路，
     * 渲染链路没有 VIP 校验。
     */
    private static void installFullScreenInjection(XposedModule module, Feature f, ClassLoader cl) {
        Class<?> parser = HookKit.findClass(module, f, cl, "pc2.e$a");
        if (parser != null) {
            try {
                Method b = parser.getDeclaredMethod("B", org.json.JSONObject.class);
                HookKit.install(module, f, "BgInfoParser#B", b, chain -> {
                    List<Object> args = chain.getArgs();
                    if (!args.isEmpty() && args.get(0) instanceof org.json.JSONObject) {
                        if (forceFullScreen(module, f, (org.json.JSONObject) args.get(0))) {
                            HookKit.hit(module, f, "injected fullScreen validType=4 (network)");
                        }
                    }
                    return chain.proceed();
                });
            } catch (Throwable t) {
                HookKit.warn(module, f.id + " BgInfoParser#B hook failed: " + t.getMessage());
            }
        }

        Class<?> store = HookKit.findClass(module, f, cl, "pc2.f");
        if (store != null) {
            try {
                Method g = store.getDeclaredMethod("g", long.class);
                HookKit.install(module, f, "BgStore#g", g, chain -> {
                    Object result = chain.proceed();
                    if (result instanceof String) {
                        String patched = patchBackgroundJson(module, f, (String) result);
                        if (patched != null) {
                            HookKit.hit(module, f, "injected fullScreen validType=4 (sp read)");
                            return patched;
                        }
                    }
                    return result;
                });

                Method s = store.getDeclaredMethod("s", long.class, String.class);
                HookKit.install(module, f, "BgStore#s", s, chain -> {
                    List<Object> args = chain.getArgs();
                    if (args.size() > 1 && args.get(1) instanceof String) {
                        String patched = patchBackgroundJson(module, f, (String) args.get(1));
                        if (patched != null) {
                            Object[] newArgs = args.toArray();
                            newArgs[1] = patched;
                            HookKit.hit(module, f, "injected fullScreen validType=4 (sp write)");
                            return chain.proceed(newArgs);
                        }
                    }
                    return chain.proceed();
                });
            } catch (Throwable t) {
                HookKit.warn(module, f.id + " BgStore hook failed: " + t.getMessage());
            }
        }
    }

    /**
     * 直接在 Profile 模型上注入全屏：比 JSON 字符串更早、更可靠。
     * 只改 validType/backgroundValue/fullScreen，不碰 vip/showEntrance。
     */
    private static void installProfileModelHooks(XposedModule module, Feature f, ClassLoader cl) {
        Class<?> profile = HookKit.findClass(module, f, cl, "com.netease.cloudmusic.meta.Profile");
        if (profile == null) {
            return;
        }
        try {
            Class<?> infoClass = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.meta.virtual.UserBackgroundInfo");
            if (infoClass != null) {
                Method setInfo = profile.getDeclaredMethod("setUserBackgroundInfo", infoClass);
                HookKit.install(module, f, "Profile#setUserBackgroundInfo", setInfo, chain -> {
                    List<Object> args = chain.getArgs();
                    if (!args.isEmpty() && args.get(0) != null) {
                        if (forceFullScreenModel(module, f, args.get(0))) {
                            HookKit.hit(module, f, "injected fullScreen validType=4 (profile model)");
                        }
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " Profile#setUserBackgroundInfo hook failed: "
                    + t.getMessage());
        }

        try {
            Class<?> mineClass = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.meta.MineUserBackground");
            if (mineClass != null) {
                Method setMine = profile.getDeclaredMethod("setMineUserBackground", mineClass);
                HookKit.install(module, f, "Profile#setMineUserBackground", setMine, chain -> {
                    List<Object> args = chain.getArgs();
                    if (!args.isEmpty()) {
                        Object replacement = fullScreenMineBackground(mineClass);
                        if (replacement != null) {
                            Object[] newArgs = args.toArray();
                            newArgs[0] = replacement;
                            HookKit.hit(module, f, "injected fullScreen (mine model)");
                            return chain.proceed(newArgs);
                        }
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " Profile#setMineUserBackground hook failed: "
                    + t.getMessage());
        }
    }

    private static boolean forceFullScreenModel(XposedModule module, Feature f, Object info) {
        try {
            invoke(info, "setValidType", new Class<?>[]{int.class}, 4);
            Object value = HookKit.invokeNoArg(info, "getBackgroundValue");
            if (value == null) {
                Class<?> valueClass = HookKit.findClass(module, f,
                        info.getClass().getClassLoader(),
                        "com.netease.cloudmusic.meta.virtual.UserBackgroundValue");
                if (valueClass == null) {
                    return false;
                }
                value = valueClass.newInstance();
                invoke(info, "setBackgroundValue", new Class<?>[]{valueClass}, value);
            }
            invoke(value, "setFullPicValue", new Class<?>[]{String.class}, fullPicUri());
            invoke(value, "setLatestSetPicTime", new Class<?>[]{long.class},
                    System.currentTimeMillis());
            return true;
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " forceFullScreenModel failed: " + t.getMessage());
            return false;
        }
    }

    private static Object fullScreenMineBackground(Class<?> mineClass) {
        try {
            java.lang.reflect.Constructor<?> ctor = mineClass.getConstructor(
                    Boolean.class, String.class, boolean.class, Long.class);
            return ctor.newInstance(Boolean.TRUE, fullPicUri(), false,
                    System.currentTimeMillis());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 同时兼容两种 JSON 形态：
     * - 网络响应：{"code":200,"data":{"validType":...}} -> 改 data；
     * - SP/缓存：{"validType":...} -> 改顶层。
     */
    private static boolean forceFullScreen(XposedModule module, Feature f,
                                           org.json.JSONObject json) {
        try {
            org.json.JSONObject target = json.optJSONObject("data");
            if (target == null) {
                target = json;
            }
            if (target.optInt("validType", 0) == 4) {
                bumpLatestSetPicTime(target);
                return false;
            }
            target.put("validType", 4);
            org.json.JSONObject value = target.optJSONObject("backgroundValue");
            if (value == null) {
                value = new org.json.JSONObject();
                target.put("backgroundValue", value);
            }
            if (value.optString("fullPicValue", "").isEmpty()) {
                value.put("fullPicValue", fullPicUri());
            }
            bumpLatestSetPicTime(target);
            return true;
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " forceFullScreen failed: " + t.getMessage());
            return false;
        }
    }

    private static String patchBackgroundJson(XposedModule module, Feature f, String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            org.json.JSONObject json = new org.json.JSONObject(raw);
            if (forceFullScreen(module, f, json)) {
                return json.toString();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Skin 分支与 FullScreen 并列，靠 latestSetPicTime > lastUseThemeSkinTime 取胜。 */
    private static void bumpLatestSetPicTime(org.json.JSONObject target) {
        try {
            org.json.JSONObject value = target.optJSONObject("backgroundValue");
            if (value != null) {
                value.put("latestSetPicTime", System.currentTimeMillis());
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 共享文件 URI：原生全屏背景加载它；加载失败也不影响
     * FullScreen 分支本身（分支只要求 fullPicValue 非空）。
     */
    private static String fullPicUri() {
        try {
            String pkg = HookState.modulePackage();
            if (pkg == null || pkg.isEmpty()) {
                return FULL_PIC_PLACEHOLDER;
            }
            File file = new File(new File(new File(new File(
                    Environment.getExternalStorageDirectory(), "Android"), "media"), pkg),
                    "mine_background.jpg");
            return "file://" + file.getAbsolutePath();
        } catch (Throwable t) {
            return FULL_PIC_PLACEHOLDER;
        }
    }

    // ---------------------------------------------------------------- apply

    private static void apply(XposedModule module, Feature f, Object manager) {
        if (manager == null || !sApplying.compareAndSet(false, true)) {
            return;
        }
        try {
            ViewGroup container = headerBgContainer(manager);
            if (container == null || !isSelfPage(manager)) {
                return;
            }
            Context context = container.getContext();
            String modulePackage = HookState.modulePackage();
            if (context == null || modulePackage == null || modulePackage.isEmpty()) {
                return;
            }
            Bitmap bitmap = sCachedBitmap;
            if (bitmap == null || bitmap.isRecycled() || cachedSourceStale(modulePackage)) {
                loadAsync(module, f, manager, container, context,
                        Uri.parse("content://" + modulePackage + ".status/mine_background"));
                return;
            }
            applyBitmap(module, f, manager, container, bitmap);
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " apply error: " + t.getMessage());
        } finally {
            sApplying.set(false);
        }
    }

    /**
     * 缓存命中前校验 external 源文件指纹:模块 App 侧换图/清除会写/删
     * Android/media/&lt;模块包名&gt;/mine_background.jpg,指纹变化或文件消失
     * 说明缓存已过期,需丢弃缓存重走 loadAsync(加载成功则替换缓存,
     * 加载失败走 clearApplied 清除旧背景)。
     *
     * 已知限制:缓存经 provider 读 internal 的兜底路径(external 不可读时)
     * 拿不到源文件指纹,此处无法 stat 校验,维持原行为直接用缓存。
     */
    private static boolean cachedSourceStale(String modulePackage) {
        long[] fingerprint = sCachedFingerprint;
        if (fingerprint == null) {
            return false;
        }
        File external = externalFile(modulePackage);
        if (external == null) {
            return false;
        }
        try {
            if (!external.isFile()) {
                return true;
            }
            return external.lastModified() != fingerprint[0]
                    || external.length() != fingerprint[1];
        } catch (Throwable t) {
            // stat 失败降级为「用缓存」,避免校验本身影响贴图
            return false;
        }
    }

    // ---------------------------------------------------------------- image

    private static void loadAsync(XposedModule module, Feature f, Object manager,
                                  ViewGroup container, Context context, Uri uri) {
        if (!sLoading.compareAndSet(false, true)) {
            return;
        }
        new Thread(() -> {
            Bitmap decoded = null;
            long[] fingerprint = null;
            try {
                // 优先读共享目录：MIUI/HyperOS 会拦截模块 Provider 的跨进程唤醒。
                File external = externalFile(HookState.modulePackage());
                if (external != null && external.isFile() && external.length() > 0) {
                    decoded = decodeFile(external);
                    if (decoded == null) {
                        HookKit.warn(module, f.id + " external background unreadable, fallback provider");
                    } else {
                        // 解码成功后紧跟 stat,把源文件指纹与位图成对缓存,
                        // 供 apply() 命中缓存时校验模块侧换图/清除事件。
                        fingerprint = fingerprintOf(external);
                    }
                }
                if (decoded == null) {
                    decoded = decode(context, uri);
                }
            } catch (Throwable t) {
                HookKit.warn(module, f.id + " background decode failed: " + t.getMessage());
            }
            final Bitmap bitmap = decoded;
            final long[] sourceFingerprint = fingerprint;
            MAIN.post(() -> {
                sLoading.set(false);
                if (bitmap == null || bitmap.isRecycled()) {
                    clearApplied(module, f, manager, container);
                    return;
                }
                sCachedBitmap = bitmap;
                sCachedFingerprint = sourceFingerprint;
                if (container.isAttachedToWindow()) {
                    applyBitmap(module, f, manager, container, bitmap);
                }
            });
        }, "CloudMusicTool-MineBg").start();
    }

    /** 读取源文件指纹 {lastModified, length};stat 失败返回 null,按「无指纹可比」处理。 */
    private static long[] fingerprintOf(File file) {
        try {
            return new long[]{file.lastModified(), file.length()};
        } catch (Throwable t) {
            return null;
        }
    }

    private static void applyBitmap(XposedModule module, Feature f, Object manager,
                                    ViewGroup container, Bitmap bitmap) {
        try {
            if (container.getContext() == null) {
                return;
            }
            addImageToContainer(module, f, container, bitmap);
            showHeaderLayer(container);
            applyFullScreen(module, f, manager, container);
            forceContentMask(module, f, container);
            HookKit.hit(module, f, "manager applied local background");
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " applyBitmap failed: " + t.getMessage());
        }
    }

    private static void addImageToContainer(XposedModule module, Feature f,
                                            ViewGroup container, Bitmap bitmap) {
        try {
            Context context = container.getContext();
            if (context == null) {
                return;
            }
            ImageView image = findTagged(container, TAG_IMAGE, ImageView.class);
            if (image == null) {
                image = new ImageView(context);
                image.setTag(TAG_IMAGE);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setLayoutParams(new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }
            if (image.getParent() == null) {
                container.addView(image);
            }
            image.setImageBitmap(bitmap);
            image.setVisibility(View.VISIBLE);

            View scrim = findTagged(container, TAG_SCRIM, View.class);
            if (scrim == null) {
                scrim = new View(context);
                scrim.setTag(TAG_SCRIM);
                scrim.setLayoutParams(new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }
            if (scrim.getParent() == null) {
                container.addView(scrim);
            }
            boolean night = (context.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            scrim.setBackgroundColor(night ? 0x33000000 : 0x1AFFFFFF);
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " addImageToContainer failed: " + t.getMessage());
        }
    }

    private static void clearContainer(ViewGroup container) {
        View image = findTagged(container, TAG_IMAGE, View.class);
        if (image != null) {
            container.removeView(image);
        }
        View scrim = findTagged(container, TAG_SCRIM, View.class);
        if (scrim != null) {
            container.removeView(scrim);
        }
    }

    private static void clearApplied(XposedModule module, Feature f,
                                     Object manager, ViewGroup container) {
        try {
            clearContainer(container);
            Bitmap cached = sCachedBitmap;
            sCachedBitmap = null;
            sCachedFingerprint = null;
            if (cached != null && !cached.isRecycled()) {
                cached.recycle();
            }

            int type = managerType(manager);
            boolean hostFullScreen = type == 4;

            View fl = findFlHeaderImage(container);
            if (fl != null && !hostFullScreen) {
                fl.setAlpha(0f);
            }
            requestHostRestyle(container);
            HookKit.info(module, f.id + " local background cleared");
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " clearApplied failed: " + t.getMessage());
        }
    }

    private static void showHeaderLayer(ViewGroup container) {
        View fl = findFlHeaderImage(container);
        if (fl != null) {
            fl.setVisibility(View.VISIBLE);
            fl.setAlpha(1f);
        }
    }

    /**
     * 9.6.13 的反射降级：
     * - {@code pe()} 已变为 {@code pe(Activity)}（初始化 StatePopView，不返回
     *   ViewModel）；传入当前 Activity，拿不到就跳过；
     * - 旧 ViewModel 的 {@code W1(boolean)} 在 9.6.13 变成 {@code W1(String)}
     *   （设置 mPageRefer），语义与全屏无关，分支删除；
     * - {@code kd(String)} 在 MyMusicFragmentV4 不存在（V4Base 的 kd 接收
     *   CustomThemeTabLayout2），分支删除。
     *
     * 宿主全屏状态不再由模块强制，统一以本地 ImageView + forceContentMask()
     * 为准；gd(String) 的 after-hook 仍保留。
     */
    private static void applyFullScreen(XposedModule module, Feature f,
                                        Object manager, ViewGroup container) {
        try {
            Activity activity = findActivity(container.getContext());
            if (activity == null) {
                return;
            }
            Object fragment = findMineFragment(activity);
            if (fragment == null) {
                return;
            }
            invoke(fragment, "pe", new Class<?>[]{Activity.class}, activity);
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " full-screen state failed: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------ content mask

    /**
     * 整页透出（Issue #1）：官方 gd() 在「manager 类型为 FullScreen 且背景图经
     * 官方加载器真实加载完成（zg2.b#T1 通知）」时把内容区遮罩 bottomAreaMask
     * 设为半透明，内容区透出背景图；否则设主题深色。模块贴的是本地图片，官方
     * 加载链路对 file:// 失败，T1 永不触发，gd() 退回深色遮罩把内容区盖死——
     * 这就是 2.1.0「头部透出、内容区深色」的根因。
     *
     * 此处在 gd() 之后按「模块已贴图」补设官方同款半透明；宿主每次重设主题色
     * 都被即时纠正。9.6.08 为 gd()V、9.6.13 为 gd(String)，两种签名都挂；
     * bottomAreaMask 资源不存在（更旧版本）时静默降级为仅头部效果。
     */
    private static void installContentMaskHooks(XposedModule module, ClassLoader cl, Feature f) {
        Class<?> fragment = HookKit.findClass(module, f, cl, MINE_FRAGMENT);
        if (fragment == null) {
            return;
        }
        boolean installed = false;
        for (Method m : fragment.getDeclaredMethods()) {
            if (!"gd".equals(m.getName()) || m.getParameterTypes().length > 1) {
                continue;
            }
            HookKit.install(module, f, "fragment#gd", m, chain -> {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    Method getView = findMethod(self.getClass(), "getView", new Class<?>[0]);
                    View fragmentView = getView == null ? null : (View) getView.invoke(self);
                    if (fragmentView != null) {
                        forceContentMask(module, f, fragmentView);
                    }
                } catch (Throwable t) {
                    HookKit.warn(module, f.id + " fragment#gd mask failed: " + t.getMessage());
                }
                return result;
            });
            installed = true;
        }
        if (!installed) {
            HookState.note(f, "gd 未找到，整页遮罩增强不生效");
        }
    }

    /** 模块已贴图时把内容区遮罩设为官方全屏分支同款半透明；未贴图不干预。 */
    private static void forceContentMask(XposedModule module, Feature f, View anyView) {
        try {
            Bitmap bitmap = sCachedBitmap;
            if (bitmap == null || bitmap.isRecycled()) {
                return;
            }
            View mask = anyView.getRootView().findViewById(resolveMaskId(anyView));
            if (mask == null) {
                return;
            }
            mask.setBackground(new ColorDrawable(FULLSCREEN_CONTENT_SCRIM));
            HookKit.hit(module, f, "content mask translucent (full page)");
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " forceContentMask failed: " + t.getMessage());
        }
    }

    /** 清除背景后让宿主 gd() 重算遮罩；位图已置空，after-hook 放行官方主题色。 */
    private static void requestHostRestyle(ViewGroup container) {
        try {
            Object fragment = findMineFragment(findActivity(container.getContext()));
            if (fragment == null) {
                return;
            }
            Method zero = findMethod(fragment.getClass(), "gd", new Class<?>[0]);
            if (zero != null) {
                zero.setAccessible(true);
                zero.invoke(fragment);
                return;
            }
            Method one = findMethod(fragment.getClass(), "gd", new Class<?>[]{String.class});
            if (one != null) {
                one.setAccessible(true);
                one.invoke(fragment, "cloudmusictool_clear");
            }
        } catch (Throwable ignored) {
        }
    }

    private static int resolveMaskId(View view) {
        int id = sContentMaskId;
        if (id != 0) {
            return id;
        }
        try {
            id = view.getResources().getIdentifier(
                    CONTENT_MASK_VIEW, "id", Config.TARGET_PACKAGE);
            sContentMaskId = id;
        } catch (Throwable t) {
            id = 0;
        }
        return id;
    }

    // ---------------------------------------------------------------- views

    private static ViewGroup headerBgContainer(Object manager) {
        try {
            Field field = HookKit.findField(manager.getClass(), "a");
            if (field == null) {
                return null;
            }
            field.setAccessible(true);
            Object value = field.get(manager);
            return value instanceof ViewGroup ? (ViewGroup) value : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isSelfPage(Object manager) {
        try {
            Field field = HookKit.findField(manager.getClass(), "b");
            if (field == null || field.getType() != boolean.class) {
                return false;
            }
            field.setAccessible(true);
            return field.getBoolean(manager);
        } catch (Throwable t) {
            return false;
        }
    }

    private static int managerType(Object manager) {
        Object value = HookKit.invokeNoArg(manager, "c");
        return value instanceof Integer ? (Integer) value : -1;
    }

    private static View findFlHeaderImage(View view) {
        try {
            int id = sFlHeaderImageId;
            if (id == 0) {
                id = view.getResources().getIdentifier(
                        "flHeaderImage", "id", Config.TARGET_PACKAGE);
                if (id == 0) {
                    id = FL_HEADER_IMAGE_FALLBACK;
                }
                sFlHeaderImageId = id;
            }
            return view.getRootView().findViewById(id);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends View> T findTagged(ViewGroup parent, String tag, Class<T> type) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (tag.equals(child.getTag()) && type.isInstance(child)) {
                return (T) child;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- reflect

    private static Activity findActivity(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) {
                return (Activity) current;
            }
            current = ((ContextWrapper) current).getBaseContext();
        }
        return null;
    }

    private static Object findMineFragment(Activity activity) {
        if (activity == null) {
            return null;
        }
        try {
            Method getFm = activity.getClass().getMethod("getSupportFragmentManager");
            Object fm = getFm.invoke(activity);
            if (fm == null) {
                return null;
            }
            Method getFragments = fm.getClass().getMethod("getFragments");
            Object value = getFragments.invoke(fm);
            if (!(value instanceof List)) {
                return null;
            }
            for (Object fragment : (List<?>) value) {
                if (fragment == null) {
                    continue;
                }
                String name = fragment.getClass().getName();
                if (MINE_FRAGMENT.equals(name)) {
                    return fragment;
                }
                // TODO(EN-01): MyMusicGuestFragmentV4 继承 MyMusicFragmentV4Base，
                // 也持有同一个 MyMusicProfileHeaderBgViewHolderManager。非本人页
                // 是否应该显示模块背景尚未确认，本版只记录一次日志、不改变行为。
                if (MINE_GUEST_FRAGMENT.equals(name)
                        && sGuestFragmentLogged.compareAndSet(false, true)) {
                    Log.i(TAG, "EN-01 note: MyMusicGuestFragmentV4 shares the header-bg"
                            + " manager; non-self page behavior unchanged");
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 带参调用仍保留最小私有实现：HookKit 只有无参 invokeNoArg，且其方法定位
     * （findMethodQuiet）仅查声明类本身；这里的宿主方法（如 pe/W1/kd）需要沿
     * 继承链查找非 public 声明，也不该为运行期反射失败登记 HookState。
     */
    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) {
        if (target == null) {
            return null;
        }
        Method method = findMethod(target.getClass(), name, types);
        if (method == null) {
            return null;
        }
        try {
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?>[] types) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredMethod(name, types);
            } catch (Throwable ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- decode

    private static File externalFile(String modulePackage) {
        if (modulePackage == null || modulePackage.isEmpty()) {
            return null;
        }
        try {
            File base = Environment.getExternalStorageDirectory();
            return new File(new File(new File(new File(base, "Android"), "media"),
                    modulePackage), "mine_background.jpg");
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap decodeFile(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = new FileInputStream(file)) {
            BitmapFactory.decodeStream(in, null, bounds);
        } catch (Throwable t) {
            return null;
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = SampleSize.calculateInSampleSize(
                bounds.outWidth, bounds.outHeight, 1440, 2560);
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        try (InputStream in = new FileInputStream(file)) {
            return BitmapFactory.decodeStream(in, null, opts);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap decode(Context context, Uri uri) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            BitmapFactory.decodeStream(in, null, bounds);
        } catch (Throwable t) {
            return null;
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }

        int maxWidth = 1440;
        int maxHeight = 2560;
        try {
            android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
            if (dm.widthPixels > 0) {
                maxWidth = Math.min(maxWidth, dm.widthPixels);
            }
            if (dm.heightPixels > 0) {
                maxHeight = Math.min(maxHeight, dm.heightPixels * 2);
            }
        } catch (Throwable ignored) {
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = SampleSize.calculateInSampleSize(
                bounds.outWidth, bounds.outHeight, maxWidth, maxHeight);
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            return BitmapFactory.decodeStream(in, null, opts);
        } catch (Throwable t) {
            return null;
        }
    }
}
