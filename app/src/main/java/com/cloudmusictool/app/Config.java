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

    private static volatile boolean sDexKitSelfTest = DEFAULT_DEXKIT_SELFTEST;

    private static final String TAG = "CloudMusicTool";

    private final Map<Feature, Boolean> mEnabled = new EnumMap<>(Feature.class);
    private final Map<String, Set<String>> mCustomSets = new java.util.HashMap<>();
    private Map<String, String> mAbTestForceMap = Collections.emptyMap();

    private Config() {
        for (Feature f : Feature.values()) {
            mEnabled.put(f, f.defaultEnabled);
        }
    }

    public static Config load(XposedModule module) {
        Config config = new Config();
        sDexKitSelfTest = DEFAULT_DEXKIT_SELFTEST;
        boolean remote = (module.getFrameworkProperties() & XposedInterface.PROP_CAP_REMOTE) != 0;
        if (!remote) {
            module.log(Log.WARN, TAG, "no PROP_CAP_REMOTE: use built-in defaults");
            return config;
        }
        try {
            SharedPreferences sp = module.getRemotePreferences(GROUP);
            if (sp != null) {
                for (Feature f : Feature.values()) {
                    config.mEnabled.put(f, sp.getBoolean(f.key, f.defaultEnabled));
                }
                sDexKitSelfTest = sp.getBoolean(KEY_DEXKIT_SELFTEST, DEFAULT_DEXKIT_SELFTEST);
                loadSet(sp, KEY_HOME_BLOCKS_FILTER, config.mCustomSets);
                loadSet(sp, KEY_TOP_CHANNELS_KEEP, config.mCustomSets);
                loadSet(sp, KEY_BOTTOM_NAV_KEEP, config.mCustomSets);
                loadSet(sp, KEY_STARTUP_TASK_BLOCKLIST, config.mCustomSets);
                try {
                    config.mAbTestForceMap = parseForceMap(
                            sp.getString(KEY_ABTEST_FORCE_MAP, "{}"));
                } catch (Throwable t) {
                    module.log(Log.WARN, TAG, "abtest_force_map unreadable, ignored", t);
                }
            }
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "remote prefs unavailable: use built-in defaults", t);
        }
        return config;
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
