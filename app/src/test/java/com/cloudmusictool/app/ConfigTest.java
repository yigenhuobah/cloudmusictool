package com.cloudmusictool.app;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ConfigTest {

    @Test
    public void loadSet_readsStringSet() {
        FakePrefs prefs = new FakePrefs();
        prefs.values.put("k", new HashSet<>(java.util.Arrays.asList("a", "b")));
        Map<String, Set<String>> out = new HashMap<>();
        Config.loadSet(prefs, "k", out);
        assertEquals(new HashSet<>(java.util.Arrays.asList("a", "b")), out.get("k"));
    }

    @Test
    public void loadSet_fallsBackToCsvString() {
        FakePrefs prefs = new FakePrefs();
        prefs.values.put("k", " a , b ,, c ");
        Map<String, Set<String>> out = new HashMap<>();
        Config.loadSet(prefs, "k", out);
        assertEquals(new HashSet<>(java.util.Arrays.asList("a", "b", "c")), out.get("k"));
    }

    @Test
    public void loadSet_missingKeyAddsNothing() {
        FakePrefs prefs = new FakePrefs();
        Map<String, Set<String>> out = new HashMap<>();
        Config.loadSet(prefs, "k", out);
        assertFalse(out.containsKey("k"));
    }

    @Test
    public void loadSet_emptyStringAddsNothing() {
        FakePrefs prefs = new FakePrefs();
        prefs.values.put("k", "");
        Map<String, Set<String>> out = new HashMap<>();
        Config.loadSet(prefs, "k", out);
        assertFalse(out.containsKey("k"));
    }

    @Test
    public void dexKitSelfTest_defaultsOff() {
        assertFalse(Config.DEFAULT_DEXKIT_SELFTEST);
        assertTrue(Config.KEY_DEXKIT_SELFTEST.contains("dexkit"));
    }

    // ------------------------------------------------------------ Config.load 容错

    @Test
    public void load_malformedBooleanKeyDoesNotAbortRemainingKeys() {
        FakePrefs prefs = new FakePrefs();
        Feature earlier = Feature.values()[0];
        Feature broken = Feature.values()[1];
        Feature later = Feature.values()[Feature.values().length - 1];
        prefs.throwingBooleanKeys.add(broken.key);
        prefs.values.put(earlier.key, !earlier.defaultEnabled);
        prefs.values.put(later.key, !later.defaultEnabled);
        Set<String> blocks = new HashSet<>(java.util.Arrays.asList("a", "b"));
        prefs.values.put(Config.KEY_HOME_BLOCKS_FILTER, blocks);
        prefs.values.put(Config.KEY_ABTEST_FORCE_MAP, "{\"tabMerge\":\"t1\"}");
        List<String> logs = new ArrayList<>();

        Config config = Config.load(fakeModule(prefs, logs));

        assertEquals("坏 key 之前已读到的值必须保留",
                !earlier.defaultEnabled, config.enabled(earlier));
        assertEquals("坏 key 只回退自身默认值",
                broken.defaultEnabled, config.enabled(broken));
        assertEquals("坏 key 之后的开关必须继续加载",
                !later.defaultEnabled, config.enabled(later));
        assertEquals("set 型 key 不受布尔坏值影响",
                blocks, config.getFilterSet(Config.KEY_HOME_BLOCKS_FILTER));
        assertEquals("abtest 表不受布尔坏值影响",
                "t1", config.getAbTestForceMap().get("tabMerge"));
        assertTrue("必须记录包含 key 与异常类型的 WARN",
                logs.stream().anyMatch(s -> s.contains(broken.key)
                        && s.contains("IllegalStateException")));
    }

    @Test
    public void load_malformedSetKeyDoesNotAbortOtherKeys() {
        FakePrefs prefs = new FakePrefs();
        String brokenSet = Config.KEY_HOME_BLOCKS_FILTER;
        prefs.throwingSetKeys.add(brokenSet);
        Set<String> kept = new HashSet<>(java.util.Arrays.asList("x", "y"));
        prefs.values.put(Config.KEY_TOP_CHANNELS_KEEP, kept);
        prefs.values.put(Config.KEY_ABTEST_FORCE_MAP, "{\"tabMerge\":\"t1\"}");
        Feature feature = Feature.SPLASH_AD;
        prefs.values.put(feature.key, !feature.defaultEnabled);
        List<String> logs = new ArrayList<>();

        Config config = Config.load(fakeModule(prefs, logs));

        assertEquals("坏 set key 回退空集",
                Collections.emptySet(), config.getFilterSet(brokenSet));
        assertEquals("后续 set key 必须继续加载",
                kept, config.getFilterSet(Config.KEY_TOP_CHANNELS_KEEP));
        assertEquals("abtest 表必须继续加载",
                "t1", config.getAbTestForceMap().get("tabMerge"));
        assertEquals(!feature.defaultEnabled, config.enabled(feature));
        assertTrue("set 读取失败也必须记录 WARN",
                logs.stream().anyMatch(s -> s.contains(brokenSet)
                        && s.contains("IllegalStateException")));
    }

    @Test
    public void load_configVersionDefaultsToOneWhenAbsent() {
        List<String> logs = new ArrayList<>();

        Config config = Config.load(fakeModule(new FakePrefs(), logs));

        assertEquals(1, Config.CURRENT_CONFIG_VERSION);
        assertEquals(Config.CURRENT_CONFIG_VERSION, config.configVersion());
    }

    @Test
    public void load_futureConfigVersionContinuesInCompatibilityMode() {
        FakePrefs prefs = new FakePrefs();
        prefs.values.put(Config.KEY_CONFIG_VERSION, 2);
        Feature feature = Feature.SPLASH_AD;
        prefs.values.put(feature.key, !feature.defaultEnabled);
        List<String> logs = new ArrayList<>();

        Config config = Config.load(fakeModule(prefs, logs));

        assertEquals("高版本仍需按原值读入，不能丢数据", 2, config.configVersion());
        assertEquals("高版本必须继续加载开关",
                !feature.defaultEnabled, config.enabled(feature));
        assertTrue("必须记录兼容模式 WARN",
                logs.stream().anyMatch(s -> s.contains("配置版本 v2 高于模块支持 v1")));
    }

    @Test
    public void load_malformedConfigVersionFallsBackToCurrentVersion() {
        FakePrefs prefs = new FakePrefs();
        prefs.throwingIntKeys.add(Config.KEY_CONFIG_VERSION);
        List<String> logs = new ArrayList<>();

        Config config = Config.load(fakeModule(prefs, logs));

        assertEquals(Config.CURRENT_CONFIG_VERSION, config.configVersion());
        assertTrue(logs.stream().anyMatch(s -> s.contains(Config.KEY_CONFIG_VERSION)
                && s.contains("IllegalStateException")));
    }

    // ------------------------------------------------------------ XposedModule fake

    /** 只承接 Config.load 需要的框架能力：remote prefs、capability 与日志。 */
    private static XposedModule fakeModule(FakePrefs prefs, List<String> logs) {
        XposedInterface framework = (XposedInterface) java.lang.reflect.Proxy.newProxyInstance(
                XposedInterface.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getFrameworkProperties":
                            return XposedInterface.PROP_CAP_REMOTE;
                        case "getRemotePreferences":
                            return prefs;
                        case "log":
                            addLog(logs, args);
                            return null;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
        XposedModule module = new XposedModule() {
        };
        module.attachFramework(framework, () -> {
        });
        return module;
    }

    private static void addLog(List<String> logs, Object[] args) {
        StringBuilder sb = new StringBuilder();
        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof Throwable) {
                    Throwable error = (Throwable) arg;
                    sb.append(" | ").append(error.getClass().getName())
                            .append(": ").append(error.getMessage());
                } else {
                    sb.append(' ').append(arg);
                }
            }
        }
        logs.add(sb.toString());
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        return null;
    }

    // ------------------------------------------------------------ PrefsSetCodec

    @Test
    public void prefsSetCodec_readStringSet() {
        FakePrefs prefs = new FakePrefs();
        prefs.values.put("k", new HashSet<>(java.util.Arrays.asList("a", "b")));
        assertEquals(new HashSet<>(java.util.Arrays.asList("a", "b")),
                PrefsSetCodec.read(prefs, "k"));
    }

    @Test
    public void prefsSetCodec_missingKeyReturnsNull() {
        FakePrefs prefs = new FakePrefs();
        assertNull(PrefsSetCodec.read(prefs, "k"));
    }

    @Test
    public void prefsSetCodec_emptyStringReturnsNull() {
        // 空串=未设置：返回 null，由调用方回退默认值。
        FakePrefs prefs = new FakePrefs();
        prefs.values.put("k", "");
        assertNull(PrefsSetCodec.read(prefs, "k"));
    }

    @Test
    public void prefsSetCodec_explicitEmptySetIsAValue() {
        // 显式空 Set 是有效值（覆盖默认值），与空串不同。
        FakePrefs prefs = new FakePrefs();
        prefs.values.put("k", new HashSet<String>());
        assertEquals(Collections.emptySet(), PrefsSetCodec.read(prefs, "k"));
    }

    @Test
    public void prefsSetCodec_csvTrimsAndSkipsBlanks() {
        FakePrefs prefs = new FakePrefs();
        prefs.values.put("k", " a , b ,, c ");
        assertEquals(new HashSet<>(java.util.Arrays.asList("a", "b", "c")),
                PrefsSetCodec.read(prefs, "k"));
    }

    @Test
    public void prefsSetCodec_csvDedupesRepeatedItems() {
        FakePrefs prefs = new FakePrefs();
        prefs.values.put("k", "a,a,b,a");
        assertEquals(new HashSet<>(java.util.Arrays.asList("a", "b")),
                PrefsSetCodec.read(prefs, "k"));
    }

    @Test
    public void prefsSetCodec_nonEmptyCsvOfBlankItemsYieldsEmptySet() {
        // 非空串即使逐项皆空也算“已设置”：返回空集而非 null（既有两侧语义）。
        FakePrefs prefs = new FakePrefs();
        prefs.values.put("k", " , , ");
        assertEquals(Collections.emptySet(), PrefsSetCodec.read(prefs, "k"));
    }

    @Test
    public void prefsSetCodec_toCsvEmptySetRoundTripsAsEmptySet() {
        // 空集是有效值（见 getStringSet 契约）：兜底字符串不能编码成空串，
        // 否则读取侧会把空串当“未设置”回退默认值。
        FakePrefs prefs = new FakePrefs();
        prefs.values.put("k", PrefsSetCodec.toCsv(new HashSet<String>()));
        assertEquals(Collections.emptySet(), PrefsSetCodec.read(prefs, "k"));
    }

    @Test
    public void prefsSetCodec_toCsvRoundTripsThroughRead() {
        FakePrefs prefs = new FakePrefs();
        prefs.values.put("k",
                PrefsSetCodec.toCsv(new HashSet<>(java.util.Arrays.asList("a", "b"))));
        assertEquals(new HashSet<>(java.util.Arrays.asList("a", "b")),
                PrefsSetCodec.read(prefs, "k"));
    }

    // ------------------------------------------------------------ ForceMapCodec

    @Test
    public void forceMapCodec_parseKeepsCandidates() {
        Map<String, String> out = ForceMapCodec.parse("{\"b\":\"t1\",\"a\":\"t2\"}");
        assertEquals(new HashSet<>(java.util.Arrays.asList("b", "a")), out.keySet());
        assertEquals("t1", out.get("b"));
        assertEquals("t2", out.get("a"));
    }

    @Test
    public void forceMapCodec_parseDropsEmptyKeyOrEmptyGroup() {
        assertTrue(ForceMapCodec.parse("{\"\":\"t1\"}").isEmpty());
        assertTrue(ForceMapCodec.parse("{\"k\":\"\"}").isEmpty());
    }

    @Test
    public void forceMapCodec_blankOrInvalidJsonReturnsEmpty() {
        assertTrue(ForceMapCodec.parse(null).isEmpty());
        assertTrue(ForceMapCodec.parse("").isEmpty());
        assertTrue(ForceMapCodec.parse("   ").isEmpty());
        assertTrue(ForceMapCodec.parse("{}").isEmpty());
        assertTrue(ForceMapCodec.parse("{oops").isEmpty());
        assertTrue(ForceMapCodec.parse("[1,2]").isEmpty());
    }

    @Test
    public void forceMapCodec_parseResultIsMutable() {
        // 实验页 readForceMap 之后还要 remove/put，候选表必须可变。
        Map<String, String> out = ForceMapCodec.parse("{\"k\":\"t1\"}");
        out.remove("k");
        assertTrue(out.isEmpty());
    }

    @Test
    public void forceMapCodec_applyFilterUsesPredicateAndKeepsOrder() {
        Map<String, String> candidates =
                ForceMapCodec.parse("{\"a\":\"t1\",\"b\":\"t2\",\"c\":\"t3\"}");
        Map<String, String> out = ForceMapCodec.applyFilter(candidates,
                (key, group) -> !"b".equals(key));
        assertEquals(2, out.size());
        java.util.List<String> expected = new ArrayList<>(candidates.keySet());
        expected.remove("b");
        assertEquals(expected, new ArrayList<>(out.keySet()));
        assertEquals("t1", out.get("a"));
        assertEquals("t3", out.get("c"));
    }

    /** 只实现 loadSet / Config.load 用到的读取方法，其余返回默认值。 */
    private static final class FakePrefs implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();
        /** 模拟单个 key 类型/内容损坏：读取该 key 时抛错。 */
        final Set<String> throwingBooleanKeys = new HashSet<>();
        final Set<String> throwingIntKeys = new HashSet<>();
        /** getStringSet 与 getString 都抛错，模拟无法读取的 set 型 key。 */
        final Set<String> throwingSetKeys = new HashSet<>();

        @Override
        public Map<String, ?> getAll() {
            return values;
        }

        @Override
        public String getString(String key, String defValue) {
            if (throwingSetKeys.contains(key)) {
                throw new IllegalStateException("malformed string: " + key);
            }
            Object v = values.get(key);
            return v instanceof String ? (String) v : defValue;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Set<String> getStringSet(String key, Set<String> defValues) {
            if (throwingSetKeys.contains(key)) {
                throw new IllegalStateException("malformed string set: " + key);
            }
            Object v = values.get(key);
            return v instanceof Set ? (Set<String>) v : defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            if (throwingIntKeys.contains(key)) {
                throw new IllegalStateException("malformed int: " + key);
            }
            Object v = values.get(key);
            return v instanceof Integer ? (Integer) v : defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            return defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            return defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            if (throwingBooleanKeys.contains(key)) {
                throw new IllegalStateException("malformed boolean: " + key);
            }
            Object v = values.get(key);
            return v instanceof Boolean ? (Boolean) v : defValue;
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public SharedPreferences.Editor edit() {
            return new FakeEditor(values);
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }
    }

    private static final class FakeEditor implements SharedPreferences.Editor {
        private final Map<String, Object> values;

        FakeEditor(Map<String, Object> values) {
            this.values = values;
        }

        @Override
        public SharedPreferences.Editor putString(String key, String value) {
            values.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putStringSet(String key, Set<String> values) {
            this.values.put(key, values);
            return this;
        }

        @Override
        public SharedPreferences.Editor putInt(String key, int value) {
            values.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putLong(String key, long value) {
            values.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putFloat(String key, float value) {
            values.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putBoolean(String key, boolean value) {
            values.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor remove(String key) {
            values.remove(key);
            return this;
        }

        @Override
        public SharedPreferences.Editor clear() {
            values.clear();
            return this;
        }

        @Override
        public boolean commit() {
            return true;
        }

        @Override
        public void apply() {
        }
    }
}
