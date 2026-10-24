package com.cloudmusictool.app.hooks;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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

    private static final int FL_HEADER_IMAGE_FALLBACK = 0x7f09136e;

    private static final String TAG_IMAGE = "cloudmusictool.mine.bg.image";
    private static final String TAG_SCRIM = "cloudmusictool.mine.bg.scrim";

    /** 模块包名缺失时的兜底 URL；只要非空，宿主就会进入 FullScreen 分支。 */
    private static final String FULL_PIC_PLACEHOLDER =
            "content://local/cloudmusictool/mine_background";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean sLoading = new AtomicBoolean(false);
    private static final AtomicBoolean sApplying = new AtomicBoolean(false);

    private static volatile Bitmap sCachedBitmap;
    /** sCachedBitmap 对应 external 源文件的 {lastModified, length} 指纹;null 表示缓存经 provider 兜底读取,无源文件可 stat。 */
    private static volatile long[] sCachedFingerprint;
    private static volatile boolean sForcedFullScreen;
    private static volatile int sFlHeaderImageId;

    private MineBackgroundHooks() {
    }

    public static void install(XposedModule module, ClassLoader cl, Feature f) {
        installNativeManagerHooks(module, cl, f);
        installFullScreenInjection(module, f, cl);
        installProxyHook(module, f, cl);
        installProfileModelHooks(module, f, cl);
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
     * 只在 EN-01 开启时安装，关闭后宿主恢复原 RN 分支。
     */
    private static void installProxyHook(XposedModule module, Feature f, ClassLoader cl) {
        Class<?> proxy = HookKit.findClass(module, f, cl,
                "com.netease.cloudmusic.fragment.MyMusicProxyFragment");
        if (proxy == null) {
            return;
        }
        try {
            Method oc = proxy.getDeclaredMethod("oc");
            HookKit.install(module, f, "MyMusicProxyFragment#oc", oc, chain -> {
                HookKit.hit(module, f, "forced native Mine page");
                return false;
            });
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " MyMusicProxyFragment#oc hook failed: " + t.getMessage());
        }
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
            if (sForcedFullScreen && !hostFullScreen) {
                Activity activity = findActivity(container.getContext());
                Object fragment = findMineFragment(activity);
                Object vm = fragment == null ? null : HookKit.invokeNoArg(fragment, "pe");
                if (vm != null) {
                    invoke(vm, "W1", new Class<?>[]{boolean.class}, false);
                    sForcedFullScreen = false;
                }
            }

            View fl = findFlHeaderImage(container);
            if (fl != null && !hostFullScreen) {
                fl.setAlpha(0f);
            }
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

    private static void applyFullScreen(XposedModule module, Feature f,
                                        Object manager, ViewGroup container) {
        try {
            Activity activity = findActivity(container.getContext());
            Object fragment = findMineFragment(activity);
            if (fragment == null) {
                return;
            }
            Object vm = HookKit.invokeNoArg(fragment, "pe");
            if (vm != null) {
                Object was = HookKit.invokeNoArg(vm, "D1");
                if (Boolean.FALSE.equals(was)) {
                    invoke(vm, "W1", new Class<?>[]{boolean.class}, true);
                    sForcedFullScreen = true;
                }
            }
            invoke(fragment, "kd", new Class<?>[]{String.class}, "custom_bg");
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " full-screen state failed: " + t.getMessage());
        }
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
                if (fragment != null && MINE_FRAGMENT.equals(fragment.getClass().getName())) {
                    return fragment;
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
