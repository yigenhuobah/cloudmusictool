package com.cloudmusictool.app;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
    public void prefsSetCodec_toCsvEmptySetIsEmptyString() {
        assertEquals("", PrefsSetCodec.toCsv(new HashSet<String>()));
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

    /** 只实现 loadSet 用到的两个方法，其余返回默认值。 */
    private static final class FakePrefs implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();

        @Override
        public Map<String, ?> getAll() {
            return values;
        }

        @Override
        public String getString(String key, String defValue) {
            Object v = values.get(key);
            return v instanceof String ? (String) v : defValue;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Set<String> getStringSet(String key, Set<String> defValues) {
            Object v = values.get(key);
            return v instanceof Set ? (Set<String>) v : defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            return defValue;
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
