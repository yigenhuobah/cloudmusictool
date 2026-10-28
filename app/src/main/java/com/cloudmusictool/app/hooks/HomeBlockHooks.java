package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Config;
import com.cloudmusictool.app.Feature;
import com.cloudmusictool.app.HookState;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * UI-03 首页模块净化。
 *
 * 9.6.13 推荐页走 DSL / MixContainer 链路：MixContainerData.getResources() 返回
 * List&lt;com.netease.luna.cm.container.Block&gt;，模块身份在 Block.getPositionCode()；
 * getBlockCodeOrderList() 只是分页请求参数，过滤它不会移除当前已渲染模块。
 * 因此主链路是 getResources()，getBlockCodeOrderList() 仅按当前 hideSet 过滤，
 * 不再维护跨请求的「已删除 code」全局状态（多容器/分页会串数据）。
 *
 * 9.6.13 运行时使用 Tinker DelegateLastClassLoader，启动阶段拿到的 ClassLoader
 * 与运行时不同，安装统一交给 {@link RuntimeHookBootstrap}，在首个 Activity#onCreate
 * 用运行时 ClassLoader 执行。
 */
public final class HomeBlockHooks {

    static final int MAX_JSON_CHARS = 16 * 1024 * 1024;
    /** 同一 Feature 的扫描日志（无命中）最短间隔，避免宿主高频调用刷屏。 */
    private static final long SCAN_LOG_INTERVAL_MS = 10_000L;

    /** 运行时 ClassLoader，仅用于扫描日志诊断 Tinker loader 身份。 */
    private static volatile ClassLoader sRuntimeLoader;
    /** 限流键 = Feature + 日志点，各日志点独立 10 秒一次，避免互相饿死。 */
    private static final java.util.Map<String, Long> sScanLogAt = new ConcurrentHashMap<>();

    private HomeBlockHooks() {
    }

    /**
     * 只登记 installer 并确保 Activity#onCreate 引导钩子存在；真正的类/方法 hook
     * 在运行时 ClassLoader 下执行（9.6.13 Tinker 设备必需）。
     */
    public static void install(XposedModule module, ClassLoader cl, Config config) {
        RuntimeHookBootstrap.register(Feature.HOME_BLOCKS,
                HomeBlockHooks::installRuntimeHooks, config);
        RuntimeHookBootstrap.ensureInstalled(module, cl);
    }

    private static void installRuntimeHooks(XposedModule module, ClassLoader cl, Config config) {
        sRuntimeLoader = cl;
        Feature f = Feature.HOME_BLOCKS;

        hookMixContainer(module, cl, f, config);
        hookDiscoveryBlockList(module, cl, f, config);
        hookMmkv(module, cl, f, config);
        HookState.finish(f);
    }

    private static boolean shouldLogScan(Feature f, String site) {
        String key = f.id + "|" + site;
        long now = System.currentTimeMillis();
        Long prev = sScanLogAt.get(key);
        if (prev == null) {
            return sScanLogAt.putIfAbsent(key, now) == null;
        }
        if (now - prev < SCAN_LOG_INTERVAL_MS) {
            return false;
        }
        return sScanLogAt.replace(key, prev, now);
    }

    // ------------------------------------------------------------------
    // 1. DSL / MixContainer（9.6.08 推荐页真实链路）
    // ------------------------------------------------------------------

    private static void hookMixContainer(XposedModule module, ClassLoader cl, Feature f, Config config) {
        try {
            Class<?> dataCls = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.dsl.container.data.MixContainerData");
            if (dataCls == null) {
                return;
            }

            Method order = HookKit.findMethodQuiet(dataCls, "getBlockCodeOrderList");
            if (order != null) {
                HookKit.install(module, f, "MixContainerData#getBlockCodeOrderList", order, chain -> {
                    HookState.entry(f);
                    Object res = chain.proceed();
                    try {
                        if (res instanceof String) {
                            return filterCodeListString(module, f, config, (String) res);
                        }
                    } catch (Throwable t) {
                        HookKit.error(module, f.id + " filter blockCodeOrderList failed", t);
                    }
                    return res;
                });
            }

            Method resources = HookKit.findMethodQuiet(dataCls, "getResources");
            if (resources != null) {
                HookKit.install(module, f, "MixContainerData#getResources", resources, chain -> {
                    HookState.entry(f);
                    Object res = chain.proceed();
                    try {
                        if (res instanceof List) {
                            return filterResourceList(module, f, config, (List<?>) res);
                        }
                    } catch (Throwable t) {
                        HookKit.error(module, f.id + " filter resources failed", t);
                    }
                    return res;
                });
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " MixContainerData hook failed", t);
        }
    }

    /** blockCodeOrderList 是形如 ["PAGE_RECOMMEND_X", ...] 的 JSON 数组字符串。 */
    private static String filterCodeListString(XposedModule module, Feature f, Config config, String json) {
        Set<String> hideSet = config.getFilterSet(Config.KEY_HOME_BLOCKS_FILTER);
        if (hideSet == null || hideSet.isEmpty()) {
            return json;
        }
        if (json == null || json.isEmpty()) {
            return json;
        }
        int[] removed = new int[]{0};
        String out = JsonFilter.filterCodeListString(json, hideSet, removed);
        if (removed[0] > 0) {
            HookKit.hit(module, f, "blockCodeOrderList filtered " + removed[0] + "/" + countJsonArray(json));
            return out;
        }
        if (shouldLogScan(f, "order")) {
            HookKit.info(module, f.id + " blockCodeOrderList scan len=" + countJsonArray(json)
                    + " loader=" + loaderId(sRuntimeLoader) + " no-match");
        }
        return json;
    }

    private static int countJsonArray(String json) {
        try {
            return new JSONArray(json).length();
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * getResources() 的元素是 com.netease.luna.cm.container.Block（DslBlock /
     * NativeBlock / RnBlock / 普通 Block）。主匹配用 Block.getPositionCode()，
     * DslBlock 再用 originData 正则补充，NativeBlock 用 nativeData/originServerData
     * 兜底。removed=0 时也按 10 秒限流打印一次扫描日志，便于真机核对 block 类型与 code。
     */
    private static Object filterResourceList(XposedModule module, Feature f, Config config, List<?> list) {
        Set<String> hideSet = config.getFilterSet(Config.KEY_HOME_BLOCKS_FILTER);
        if (list.isEmpty()) {
            return list;
        }
        if (hideSet == null || hideSet.isEmpty()) {
            logResourceScan(module, f, list);
            return list;
        }
        List<Object> out = new ArrayList<>(list.size());
        int removed = 0;
        for (Object item : list) {
            Set<String> tokens = collectTokens(item);
            if (matchesTokens(tokens, hideSet)) {
                removed++;
                continue;
            }
            out.add(item);
        }
        if (removed > 0) {
            HookKit.hit(module, f, "resources filtered " + removed + "/" + list.size());
            return out;
        }
        logResourceScan(module, f, list);
        return list;
    }

    private static void logResourceScan(XposedModule module, Feature f, List<?> list) {
        if (!shouldLogScan(f, "resources")) {
            return;
        }
        StringBuilder sb = new StringBuilder(192);
        sb.append(f.id).append(" resources scan loader=").append(loaderId(sRuntimeLoader))
                .append(" size=").append(list.size()).append(" no-match");
        int count = Math.min(5, list.size());
        for (int i = 0; i < count; i++) {
            Object item = list.get(i);
            sb.append("; [").append(i).append("]=");
            if (item == null) {
                sb.append("null");
                continue;
            }
            sb.append(item.getClass().getName())
                    .append(" code=").append(positionCodeOf(item));
        }
        HookKit.info(module, sb.toString());
    }

    private static String loaderId(ClassLoader cl) {
        if (cl == null) {
            return "null";
        }
        String s = String.valueOf(cl);
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    private static String positionCodeOf(Object item) {
        return asString(HookKit.invokeNoArg(item, "getPositionCode"));
    }

    private static Set<String> collectTokens(Object item) {
        if (item == null) {
            return Collections.emptySet();
        }
        synchronized (sTokenCache) {
            Set<String> cached = sTokenCache.get(item);
            if (cached != null) {
                return cached;
            }
        }
        Set<String> tokens = collectCodes(item);
        collectModuleKeys(item, tokens);
        Set<String> result = tokens.isEmpty()
                ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(tokens);
        synchronized (sTokenCache) {
            sTokenCache.put(item, result);
        }
        return result;
    }

    /**
     * 按 block 身份缓存已提取的 token。WeakHashMap 的弱键不会阻止宿主回收
     * block；Block 未重写 equals/hashCode 时即按引用身份命中。值只是 String，
     * 不反向引用 key，弱键可正常回收。缓存读写都在 synchronized 内，
     * 避免主线程与测试/其他调用方并发修改。
     */
    private static final Map<Object, Set<String>> sTokenCache = new WeakHashMap<>();

    /** 纯函数：item 是否命中 hideSet。包私有便于 JVM 单测。 */
    static boolean matchesHide(Object item, Set<String> hideSet) {
        if (item == null || hideSet == null || hideSet.isEmpty()) {
            return false;
        }
        return matchesTokens(collectTokens(item), hideSet);
    }

    private static boolean matchesTokens(Set<String> tokens, Set<String> hideSet) {
        if (tokens.isEmpty()) {
            return false;
        }
        for (String code : hideSet) {
            if (tokens.contains(code)) {
                return true;
            }
            String[] alias = ALIASES.get(code);
            if (alias == null) {
                continue;
            }
            for (String a : alias) {
                for (String t : tokens) {
                    if (t.equals(a) || t.startsWith(a)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 9.6.08/9.6.13 推荐页部分模块没有 PAGE_ 代码，身份在 DSL 模块 key
     * （home_*_module_*）或旧 code 里。这里只登记核对过的映射；
     * 9.6.13 疑似改名项用别名并存，不删除旧项。
     */
    private static final java.util.Map<String, String[]> ALIASES = new java.util.HashMap<>();

    static {
        // 快捷入口行（每日推荐/心动模式/听点灵感）
        ALIASES.put("PAGE_RECOMMEND_SHORTCUT", new String[]{"PAGE_DAILY_REC", "template_2354454"});
        // 雷达歌单行
        ALIASES.put("PAGE_RECOMMEND_RADAR", new String[]{"home_radar_playlist_module"});
        // 排行榜
        ALIASES.put("PAGE_RECOMMEND_RANK", new String[]{"PAGE_BLOCK_TOPLIST"});
        // 9.6.13 疑似改名：BANNER_1 / BANNER_6 -> BANNER_0
        ALIASES.put("PAGE_RECOMMEND_BANNER_1", new String[]{"PAGE_RECOMMEND_BANNER_0"});
        ALIASES.put("PAGE_RECOMMEND_BANNER_6", new String[]{"PAGE_RECOMMEND_BANNER_0"});
        // 9.6.13 播客模块疑似改名：SUB_UPDATE -> NARROW_SCENE
        ALIASES.put("PAGE_RECOMMEND_PODCAST_SUB_UPDATE",
                new String[]{"PAGE_RECOMMEND_PODCAST_NARROW_SCENE"});
    }

    private static void collectModuleKeys(Object item, java.util.Set<String> out) {
        Object model = HookKit.invokeNoArg(item, "getDslWrapperModel");
        if (model == null) {
            return;
        }
        String text = serializableText(HookKit.invokeNoArg(model, "getOriginData"), MAX_JSON_CHARS);
        if (text == null) {
            return;
        }
        java.util.regex.Matcher mk = MODULE_KEY.matcher(text);
        if (mk.find()) {
            out.add(mk.group());
        }
    }

    private static final java.util.regex.Pattern MODULE_KEY =
            java.util.regex.Pattern.compile("home_[a-z0-9_]{6,}");

    /**
     * 9.6.13 的 Block（DslBlock / NativeBlock / RnBlock / 普通 Block）统一带
     * getPositionCode()，这是 PAGE_RECOMMEND_* code 的主载体。
     * DslBlock 额外从 getDslWrapperModel() 的 originData/extData/dslContent
     * 提取 blockCode / PAGE_* 正则作为补充；NativeBlock 始终合并
     * nativeData / originServerData 的 positionCode/blockCode（positionCode
     * 可能只对应外层容器，模块 code 仍藏在原生数据里）。
     */
    private static java.util.Set<String> collectCodes(Object item) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (item == null) {
            return out;
        }
        if (item instanceof JSONObject) {
            JSONObject obj = (JSONObject) item;
            addIfPresent(out, obj.optString("positionCode", null));
            addIfPresent(out, obj.optString("blockCode", null));
            addIfPresent(out, obj.optString("block_code", null));
            return out;
        }

        // 1) 主匹配：Block.getPositionCode()（9.6.13 推荐页 NativeBlock 也适用）。
        addIfPresent(out, positionCodeOf(item));

        // 2) DslBlock 补充：originData/extData/dslContent 中的 code。
        Object model = HookKit.invokeNoArg(item, "getDslWrapperModel");
        if (model != null) {
            Object origin = HookKit.invokeNoArg(model, "getOriginData");
            addIfPresent(out, jsonString(origin, "blockCode"));
            collectPageCodes(out, origin, 8);
            Object ext = HookKit.invokeNoArg(model, "getExtData");
            addIfPresent(out, jsonString(ext, "blockCode"));
            collectPageCodes(out, ext, 8);
            Object dsl = HookKit.invokeNoArg(model, "getDslContent");
            collectPageCodes(out, dsl, 8);
            return out;
        }

        // 3) NativeBlock 兜底：始终合并原生数据里的 code，positionCode 存在时
        //    也照样检查（外层容器 code 与模块 code 可能不同）。
        Object nativeData = HookKit.invokeNoArg(item, "getNativeData");
        addIfPresent(out, jsonString(nativeData, "positionCode"));
        addIfPresent(out, jsonString(nativeData, "blockCode"));
        addIfPresent(out, jsonString(nativeData, "block_code"));
        Object originServer = HookKit.invokeNoArg(item, "getOriginServerData");
        addIfPresent(out, jsonString(originServer, "positionCode"));
        addIfPresent(out, jsonString(originServer, "blockCode"));
        addIfPresent(out, jsonString(originServer, "block_code"));
        return out;
    }

    private static void addIfPresent(java.util.Set<String> out, String code) {
        if (code != null && !code.isEmpty()) {
            out.add(code);
        }
    }

    private static void collectPageCodes(java.util.Set<String> out, Object node, int max) {
        if (node == null || out.size() >= max) {
            return;
        }
        String text = serializableText(node, MAX_JSON_CHARS);
        if (text == null) {
            return;
        }
        java.util.regex.Matcher m = PAGE_CODE.matcher(text);
        while (m.find() && out.size() < max) {
            out.add(m.group());
        }
    }

    /**
     * 提取可扫描的文本。只有已经序列化的 String 和可序列化的 JSON 容器
     * （org.json / fastjson 等 Map/Collection）才取文本；任意宿主对象直接
     * 跳过，避免为每个 block 调用不可控的 toString 分配大字符串。
     * 超过 {@code maxChars} 的文本同样跳过，保持原有 16MB 上限语义。
     *
     * @return 可扫描文本；类型不支持或超长时返回 null（不分配新字符串）。
     */
    static String serializableText(Object node, int maxChars) {
        if (node == null) {
            return null;
        }
        String text;
        if (node instanceof String) {
            text = (String) node;
        } else if (node instanceof JSONObject || node instanceof JSONArray
                || node instanceof Map || node instanceof Collection) {
            text = String.valueOf(node);
        } else {
            return null;
        }
        return text.length() > maxChars ? null : text;
    }

    private static String asString(Object o) {
        return o instanceof String ? (String) o : null;
    }

    /** 兼容 fastjson JSONObject，避免在 hook 里直接依赖 fastjson。 */
    private static String jsonString(Object json, String key) {
        if (json == null) {
            return null;
        }
        try {
            Method m = json.getClass().getMethod("getString", String.class);
            m.setAccessible(true);
            Object r = m.invoke(json, key);
            return r instanceof String ? (String) r : null;
        } catch (Throwable ignored) {
        }
        try {
            Method m = json.getClass().getMethod("get", String.class);
            m.setAccessible(true);
            Object r = m.invoke(json, key);
            return r != null ? r.toString() : null;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static final java.util.regex.Pattern PAGE_CODE =
            java.util.regex.Pattern.compile("PAGE_[A-Z0-9_]{3,}");

    // ------------------------------------------------------------------
    // 2. 经典发现页
    // ------------------------------------------------------------------

    private static void hookDiscoveryBlockList(XposedModule module, ClassLoader cl, Feature f, Config config) {
        try {
            Class<?> blockDataCls = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.discovery.data.meta.DiscoveryBlockData");
            if (blockDataCls == null) {
                return;
            }
            Method getBlockList = HookKit.findMethod(module, f, blockDataCls, "getBlockList");
            HookKit.install(module, f, "DiscoveryBlockData#getBlockList", getBlockList, chain -> {
                HookState.entry(f);
                Object res = chain.proceed();
                try {
                    if (!(res instanceof List)) {
                        return res;
                    }
                    Set<String> hideSet = config.getFilterSet(Config.KEY_HOME_BLOCKS_FILTER);
                    if (hideSet == null || hideSet.isEmpty()) {
                        return res;
                    }
                    List<?> list = (List<?>) res;
                    List<Object> out = new ArrayList<>(list.size());
                    int removed = 0;
                    String firstCls = null;
                    for (Object item : list) {
                        if (item != null && firstCls == null) {
                            firstCls = item.getClass().getName();
                        }
                        String code = getBlockCode(item);
                        if (code != null && hideSet.contains(code)) {
                            removed++;
                            continue;
                        }
                        out.add(item);
                    }
                    if (removed > 0) {
                        HookKit.hit(module, f, "getBlockList filtered " + removed + " blocks");
                        return out;
                    }
                    if (shouldLogScan(f, "blockList")) {
                        HookKit.info(module, f.id + " getBlockList scan size=" + list.size()
                                + " first=" + firstCls + " no-match");
                    }
                    return res;
                } catch (Throwable t) {
                    HookKit.error(module, f.id + " intercept error getBlockList", t);
                }
                return res;
            });
        } catch (Throwable t) {
            HookKit.error(module, f.id + " DiscoveryBlockData hook failed", t);
        }
    }

    private static String getBlockCode(Object item) {
        if (item == null) {
            return null;
        }
        try {
            Method m = item.getClass().getMethod("getBlockCode");
            m.setAccessible(true);
            Object r = m.invoke(item);
            return r != null ? r.toString() : null;
        } catch (Throwable t1) {
            try {
                Method m = item.getClass().getDeclaredMethod("getBlockCode");
                m.setAccessible(true);
                Object r = m.invoke(item);
                return r != null ? r.toString() : null;
            } catch (Throwable t2) {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------
    // 3. MMKV 旧缓存兜底
    // ------------------------------------------------------------------

    private static void hookMmkv(XposedModule module, ClassLoader cl, Feature f, Config config) {
        try {
            Class<?> mmkvCls = HookKit.findClass(module, f, cl, "com.tencent.mmkv.MMKV");
            if (mmkvCls == null) {
                return;
            }
            Method mmapIdMethod = null;
            try {
                mmapIdMethod = mmkvCls.getMethod("mmapID");
            } catch (Throwable ignored) {
            }

            hookMmkvReader(module, f, config, mmkvCls, mmapIdMethod, "decodeString",
                    new Class<?>[]{String.class, String.class});
            hookMmkvReader(module, f, config, mmkvCls, mmapIdMethod, "decodeString",
                    new Class<?>[]{String.class});
            hookMmkvReader(module, f, config, mmkvCls, mmapIdMethod, "getString",
                    new Class<?>[]{String.class, String.class});
        } catch (Throwable t) {
            HookKit.error(module, f.id + " MMKV hook failed", t);
        }
    }

    private static void hookMmkvReader(XposedModule module, Feature f, Config config, Class<?> mmkvCls,
                                       Method mmapIdMethod, String name, Class<?>[] sig) {
        Method m = HookKit.findMethodQuiet(mmkvCls, name, sig);
        if (m == null) {
            return;
        }
        String label = "MMKV#" + name + "(" + sig.length + ")";
        HookKit.install(module, f, label, m, chain -> {
            Object res = chain.proceed();
            try {
                if (!(res instanceof String)) {
                    return res;
                }
                // hideSet 未配置(过滤集为空)时无须识别来源,直接放行;
                // 该判定与 filterMmkvString 内同源(Config 装载后只读),提前到这里
                // 可避免宿主每次 MMKV 字符串读都支付 mmapID 反射成本。
                if (config.getFilterSet(Config.KEY_HOME_BLOCKS_FILTER).isEmpty()) {
                    return res;
                }
                String store = mmapIdOf(mmapIdMethod, chain.getThisObject());
                List<Object> args = chain.getArgs();
                String key = (args != null && !args.isEmpty() && args.get(0) instanceof String)
                        ? (String) args.get(0) : "";
                return filterMmkvString(module, f, config, store, key, (String) res);
            } catch (Throwable t) {
                HookKit.error(module, f.id + " intercept error " + label, t);
            }
            return res;
        });
    }

    /**
     * mmapID 结果按 MMKV 实例缓存。MMKV 未重写 equals/hashCode,实例身份即语义身份,
     * 故用 IdentityHashMap 以引用相等作 key,避免同一实例每次读都反射 invoke mmapID()
     * (宿主进程 MMKV 字符串读极高频)。MMKV 自身按静态表持有实例(进程级单例),
     * 条目数以实例数为上界,不会无界增长;IdentityHashMap 非线程安全,
     * 读写均在 synchronized 块内完成。反射失败时不缓存,后续读取保持原样重试。
     */
    private static final java.util.IdentityHashMap<Object, String> sMmapIdCache =
            new java.util.IdentityHashMap<>();

    /** 识别 MMKV 实例的 mmapID;无法识别时返回 ""(与原内联实现一致)。 */
    private static String mmapIdOf(Method mmapIdMethod, Object self) {
        if (mmapIdMethod == null || self == null) {
            return "";
        }
        String cached;
        synchronized (sMmapIdCache) {
            cached = sMmapIdCache.get(self);
        }
        if (cached != null) {
            return cached;
        }
        String id;
        try {
            id = String.valueOf(mmapIdMethod.invoke(self));
        } catch (Throwable ignored) {
            return "";
        }
        synchronized (sMmapIdCache) {
            sMmapIdCache.put(self, id);
        }
        return id;
    }

    private static Object filterMmkvString(XposedModule module, Feature f, Config config,
                                           String store, String key, String str) {
        if (str == null || str.isEmpty() || str.length() > MAX_JSON_CHARS) {
            return str;
        }
        Set<String> hideSet = config.getFilterSet(Config.KEY_HOME_BLOCKS_FILTER);
        if (hideSet == null || hideSet.isEmpty()) {
            return str;
        }
        boolean relevant = containsIgnoreCase(store, "HOME_RECOMMEND")
                || containsIgnoreCase(key, "HOME_RECOMMEND")
                || containsIgnoreCase(store, "MIX_CONTAINER")
                || containsIgnoreCase(key, "MIX_CONTAINER");
        if (!relevant) {
            return str;
        }
        String trimmed = str.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return str;
        }
        if (!str.contains("\"blockCode\"") && !str.contains("\\\"blockCode\\\"")) {
            return str;
        }
        try {
            int[] removed = new int[]{0};
            Object root = trimmed.startsWith("{") ? new JSONObject(trimmed) : new JSONArray(trimmed);
            Object filtered = JsonFilter.filterValue(root, hideSet, removed, 0);
            if (removed[0] > 0) {
                HookKit.hit(module, f, "cache filtered " + removed[0] + " blocks ("
                        + shortName(store) + "/" + shortName(key) + ")");
                return filtered.toString();
            }
            if (shouldLogScan(f, "cache")) {
                HookKit.info(module, f.id + " cache scan " + shortName(store) + "/" + shortName(key)
                        + " len=" + str.length() + " no-match");
            }
        } catch (Throwable ignored) {
        }
        return str;
    }

    /**
     * 无分配的大小写不敏感包含判断:首字符预筛后逐偏移 regionMatches(true,...)。
     * 替代 toUpperCase(Locale.ROOT) 实现,避免每次调用分配一份整串大写副本
     * (MMKV 读回调内 store/key 最多各判 2 次)。needle 均为纯大写 ASCII 常量,
     * 该实现对 ASCII 输入与旧 toUpperCase 路径逐字符等价(基准已校验 2 万组)。
     */
    private static boolean containsIgnoreCase(String s, String needle) {
        if (s == null) {
            return false;
        }
        int nLen = needle.length();
        if (nLen == 0) {
            return true;
        }
        int limit = s.length() - nLen;
        char firstUpper = Character.toUpperCase(needle.charAt(0));
        char firstLower = Character.toLowerCase(needle.charAt(0));
        for (int i = 0; i <= limit; i++) {
            char c = s.charAt(i);
            // 预筛条件与 regionMatches 的首字符必要条件一致,不会漏判
            if (Character.toUpperCase(c) != firstUpper
                    && Character.toLowerCase(c) != firstLower) {
                continue;
            }
            if (s.regionMatches(true, i, needle, 0, nLen)) {
                return true;
            }
        }
        return false;
    }

    private static String shortName(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 48 ? s : s.substring(0, 48) + "…";
    }
}
