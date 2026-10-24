package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Config;
import com.cloudmusictool.app.Feature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedModule;

/**
 * UI-03 首页模块净化。
 *
 * 9.6.08 的“推荐”页走 DSL / MixContainer 链路，模块顺序由
 * MixContainerData.getBlockCodeOrderList() 返回的 JSON 数组决定；
 * 旧的 DiscoveryBlockData.getBlockList() 仅覆盖经典发现页。
 * 两个数据源都做过滤，并按 key 前缀兜底处理 MMKV 中的旧缓存。
 */
public final class HomeBlockHooks {

    private static final int MAX_JSON_CHARS = 16 * 1024 * 1024;
    private static final AtomicInteger sScanLogs = new AtomicInteger();

    private HomeBlockHooks() {
    }

    public static void install(XposedModule module, ClassLoader cl, Config config) {
        Feature f = Feature.HOME_BLOCKS;

        hookMixContainer(module, cl, f, config);
        hookDiscoveryBlockList(module, cl, f, config);
        hookMmkv(module, cl, f, config);
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
        if (hideSet == null || hideSet.isEmpty() || json == null || json.isEmpty()) {
            return json;
        }
        int[] removed = new int[]{0};
        String out = JsonFilter.filterCodeListString(json, hideSet, removed);
        if (removed[0] > 0) {
            HookKit.hit(module, f, "blockCodeOrderList filtered " + removed[0] + "/" + countJsonArray(json));
            return out;
        }
        if (sScanLogs.getAndIncrement() < 8) {
            HookKit.info(module, f.id + " blockCodeOrderList scan len=" + countJsonArray(json) + " no-match");
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

    /** resources 列表的元素可能是 JSONObject 或带 getBlockCode/getCode 的对象。 */
    private static Object filterResourceList(XposedModule module, Feature f, Config config, List<?> list) {
        Set<String> hideSet = config.getFilterSet(Config.KEY_HOME_BLOCKS_FILTER);
        if (hideSet == null || hideSet.isEmpty() || list.isEmpty()) {
            return list;
        }
        List<Object> out = new ArrayList<>(list.size());
        int removed = 0;
        for (Object item : list) {
            if (matchesHide(item, hideSet)) {
                removed++;
                continue;
            }
            out.add(item);
        }
        if (removed > 0) {
            HookKit.hit(module, f, "resources filtered " + removed + "/" + list.size());
            return out;
        }
        return list;
    }

    private static boolean matchesHide(Object item, Set<String> hideSet) {
        java.util.Set<String> tokens = collectCodes(item);
        collectModuleKeys(item, tokens);
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
     * 9.6.08 推荐页部分模块没有 PAGE_ 代码，身份在 DSL 模块 key（home_*_module_*）里。
     * 这里只登记真机逐块核对过的映射，未验证的一律不动。
     */
    private static final java.util.Map<String, String[]> ALIASES = new java.util.HashMap<>();

    static {
        // 快捷入口行（每日推荐/心动模式/听点灵感）
        ALIASES.put("PAGE_RECOMMEND_SHORTCUT", new String[]{"PAGE_DAILY_REC", "template_2354454"});
        // 雷达歌单行
        ALIASES.put("PAGE_RECOMMEND_RADAR", new String[]{"home_radar_playlist_module"});
        // 排行榜
        ALIASES.put("PAGE_RECOMMEND_RANK", new String[]{"PAGE_BLOCK_TOPLIST"});
    }

    private static void collectModuleKeys(Object item, java.util.Set<String> out) {
        Object model = HookKit.invokeNoArg(item, "getDslWrapperModel");
        if (model == null) {
            return;
        }
        String text = String.valueOf(HookKit.invokeNoArg(model, "getOriginData"));
        java.util.regex.Matcher mk = MODULE_KEY.matcher(text);
        if (mk.find()) {
            out.add(mk.group());
        }
    }

    private static final java.util.regex.Pattern MODULE_KEY =
            java.util.regex.Pattern.compile("home_[a-z0-9_]{6,}");

    /**
     * DslBlock -> getDslWrapperModel() 的 originData 里能找到 PAGE_* 代码
     * （blockCode / s_cid / 目录项），优先取这些；找不到时退化到模板 id。
     */
    private static java.util.Set<String> collectCodes(Object item) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (item == null) {
            return out;
        }
        if (item instanceof JSONObject) {
            JSONObject obj = (JSONObject) item;
            addIfPresent(out, obj.optString("blockCode", null));
            addIfPresent(out, obj.optString("block_code", null));
            return out;
        }

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
        }

        if (out.isEmpty()) {
            String[] getters = {"getBlockCode", "getCode", "getBlockCodeStr"};
            for (String name : getters) {
                String v = asString(HookKit.invokeNoArg(item, name));
                if (v != null && !v.isEmpty()) {
                    addIfPresent(out, v);
                    break;
                }
            }
        }
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
        String text = String.valueOf(node);
        if (text.length() > MAX_JSON_CHARS) {
            return;
        }
        java.util.regex.Matcher m = PAGE_CODE.matcher(text);
        while (m.find() && out.size() < max) {
            out.add(m.group());
        }
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
                    if (sScanLogs.getAndIncrement() < 8) {
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
            if (sScanLogs.getAndIncrement() < 8) {
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
