package com.cloudmusictool.app;

import android.content.SharedPreferences;
import android.util.Log;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 开关读取：有 PROP_CAP_REMOTE 才读 getRemotePreferences("config")，
 * 否则（含读取抛异常）回退到 Feature 的编译期内置默认值。
 * 必须在 onModuleLoaded 之后调用，之前任何 API 都会抛 IllegalStateException。
 */
public final class Config {

    public static final String GROUP = "config";

    /** 目标应用包名；UI 与目标进程共用，避免引用 Xposed 入口类。 */
    public static final String TARGET_PACKAGE = "com.netease.cloudmusic";

    public static final String KEY_HOME_BLOCKS_FILTER = "home_blocks_filter";
    public static final String KEY_TOP_CHANNELS_KEEP = "top_channels_keep";
    public static final String KEY_BOTTOM_NAV_KEEP = "bottom_nav_keep";
    public static final String KEY_STARTUP_TASK_BLOCKLIST = "startup_task_blocklist";

    /** ABTest 强制分组：JSON 对象 {"<key>":"<group>"}，只存用户显式开启的 key。 */
    public static final String KEY_ABTEST_FORCE_MAP = "abtest_force_map";

    /** 隐藏自检开关：不进入设置页，仅通过 remote prefs 手动开启。开启后 AD-01 强制走 DexKit。 */
    public static final String KEY_DEXKIT_SELFTEST = "dexkit_selftest";
    public static final boolean DEFAULT_DEXKIT_SELFTEST = false;

    /** 配置格式版本：写入侧每次提交都携带，读取侧只做兼容性提示，不阻断加载。 */
    public static final String KEY_CONFIG_VERSION = "config_version";
    public static final int CURRENT_CONFIG_VERSION = 1;

    private static volatile boolean sDexKitSelfTest = DEFAULT_DEXKIT_SELFTEST;

    private static final String TAG = "CloudMusicTool";

    private final Map<Feature, Boolean> mEnabled = new EnumMap<>(Feature.class);
    private final Map<String, Set<String>> mCustomSets = new java.util.HashMap<>();
    private Map<String, String> mAbTestForceMap = Collections.emptyMap();
    private int mConfigVersion = CURRENT_CONFIG_VERSION;

    private Config() {
        for (Feature f : Feature.values()) {
            mEnabled.put(f, f.defaultEnabled);
        }
    }

    /**
     * 读取配置。每个 key 独立读取、独立容错：单个坏值只回退该 key 的默认值，
     * 已经读到的值和后续 key（含 set 与 abtest 表）继续加载。
     */
    public static Config load(XposedModule module) {
        Config config = new Config();
        sDexKitSelfTest = DEFAULT_DEXKIT_SELFTEST;
        boolean remote = (module.getFrameworkProperties() & XposedInterface.PROP_CAP_REMOTE) != 0;
        if (!remote) {
            module.log(Log.WARN, TAG, "no PROP_CAP_REMOTE: use built-in defaults");
            return config;
        }
        final SharedPreferences sp;
        try {
            sp = module.getRemotePreferences(GROUP);
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "remote prefs unavailable: use built-in defaults", t);
            return config;
        }
        if (sp == null) {
            module.log(Log.WARN, TAG, "remote prefs unavailable: use built-in defaults");
            return config;
        }

        // 每个 key 只告警一次；坏 key 不能中断整个加载流程。
        Set<String> warned = new HashSet<>();
        config.mConfigVersion = readInt(module, sp, KEY_CONFIG_VERSION,
                CURRENT_CONFIG_VERSION, warned);
        if (config.mConfigVersion > CURRENT_CONFIG_VERSION) {
            module.log(Log.WARN, TAG, "配置版本 v" + config.mConfigVersion
                    + " 高于模块支持 v" + CURRENT_CONFIG_VERSION + "，按兼容模式继续");
        }
        for (Feature f : Feature.values()) {
            config.mEnabled.put(f, readBoolean(module, sp, f.key, f.defaultEnabled, warned));
        }
        sDexKitSelfTest = readBoolean(module, sp, KEY_DEXKIT_SELFTEST,
                DEFAULT_DEXKIT_SELFTEST, warned);
        loadSetTolerant(module, sp, KEY_HOME_BLOCKS_FILTER, config.mCustomSets, warned);
        loadSetTolerant(module, sp, KEY_TOP_CHANNELS_KEEP, config.mCustomSets, warned);
        loadSetTolerant(module, sp, KEY_BOTTOM_NAV_KEEP, config.mCustomSets, warned);
        loadSetTolerant(module, sp, KEY_STARTUP_TASK_BLOCKLIST, config.mCustomSets, warned);
        config.mAbTestForceMap = readForceMap(module, sp, warned);
        return config;
    }

    private static boolean readBoolean(XposedModule module, SharedPreferences sp, String key,
                                       boolean def, Set<String> warned) {
        try {
            return sp.getBoolean(key, def);
        } catch (Throwable t) {
            warnKey(module, warned, key, t);
            return def;
        }
    }

    private static int readInt(XposedModule module, SharedPreferences sp, String key,
                               int def, Set<String> warned) {
        try {
            return sp.getInt(key, def);
        } catch (Throwable t) {
            warnKey(module, warned, key, t);
            return def;
        }
    }

    private static void loadSetTolerant(XposedModule module, SharedPreferences sp, String key,
                                        Map<String, Set<String>> out, Set<String> warned) {
        try {
            Set<String> parsed = PrefsSetCodec.read(sp, key,
                    t -> warnKey(module, warned, key, t));
            if (parsed != null) {
                out.put(key, parsed);
            }
        } catch (Throwable t) {
            warnKey(module, warned, key, t);
        }
    }

    private static Map<String, String> readForceMap(XposedModule module, SharedPreferences sp,
                                                    Set<String> warned) {
        try {
            return parseForceMap(sp.getString(KEY_ABTEST_FORCE_MAP, "{}"));
        } catch (Throwable t) {
            warnKey(module, warned, KEY_ABTEST_FORCE_MAP, t);
            return Collections.emptyMap();
        }
    }

    /** 每个 key 名只记录一次 WARN，消息包含 key 与异常类型/信息。 */
    private static void warnKey(XposedModule module, Set<String> warned, String key,
                                Throwable t) {
        if (!warned.add(key)) {
            return;
        }
        String detail = t.getClass().getName()
                + (t.getMessage() != null ? ": " + t.getMessage() : "");
        module.log(Log.WARN, TAG, "config key '" + key + "' unreadable (" + detail
                + "), use default", t);
    }

    static void loadSet(SharedPreferences sp, String key, Map<String, Set<String>> out) {
        Set<String> parsed = PrefsSetCodec.read(sp, key);
        if (parsed != null) {
            out.put(key, parsed);
        }
    }

    public boolean enabled(Feature f) {
        return Boolean.TRUE.equals(mEnabled.get(f));
    }

    /**
     * 已过滤的强制分组表。非法 JSON、非法 key/group、黑名单 key 一律丢弃；
     * key 必须精确命中 curated 或已提升集合（提升集合 = 表内 key - curated）。
     */
    public Map<String, String> getAbTestForceMap() {
        return mAbTestForceMap;
    }

    /** 本次加载读到的配置版本；缺失或坏值时回退 {@link #CURRENT_CONFIG_VERSION}。 */
    int configVersion() {
        return mConfigVersion;
    }

    static Map<String, String> parseForceMap(String raw) {
        Map<String, String> candidates = ForceMapCodec.parse(raw);
        if (candidates.isEmpty()) {
            return Collections.emptyMap();
        }
        // promoted = 表内 key - curated；需基于全部候选计算后再逐条校验。
        Set<String> promoted = new HashSet<>(candidates.keySet());
        promoted.removeAll(AbTestPolicy.CURATED);
        Map<String, String> filtered = ForceMapCodec.applyFilter(candidates,
                (key, group) -> AbTestPolicy.isAllowed(key, promoted)
                        && AbTestPolicy.isValidGroup(group));
        if (filtered.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(filtered);
    }

    public boolean anyEnabled() {
        for (Map.Entry<Feature, Boolean> e : mEnabled.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue()) && !e.getKey().blocked()) {
                return true;
            }
        }
        return false;
    }

    public Set<String> getFilterSet(String key) {
        Set<String> s = mCustomSets.get(key);
        return s != null ? s : Collections.emptySet();
    }

    /** 目标进程内共享的隐藏自检开关（由 Config.load 写入）。 */
    public static boolean dexKitSelfTest() {
        return sDexKitSelfTest;
    }
}
