package com.cloudmusictool.app;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;

/**
 * ConfigStore.getStringSet 的边界契约测试。
 * home_blocks_filter 等四个 set 型 key 的值格式（StringSet 优先、CSV 字符串兜底）
 * 是模块 App 写、目标进程 Config.loadSet 读的跨进程协议，两侧解析语义必须一致：
 * ConfigTest.loadSet_emptyStringAddsNothing 已钉住 loadSet 侧空串回退默认值，
 * 这里把 getStringSet 侧对齐到同一语义（空串视为未设置，返回调用方默认值）。
 */
public class ConfigStoreSetTest {

    @Test
    public void getStringSet_emptyStringReturnsDefault() {
        FakePrefs prefs = new FakePrefs();
        prefs.values.put("k", "");
        ConfigStore store = new ConfigStore(prefs);
        Set<String> def = new HashSet<>(Arrays.asList("a", "b"));
        assertEquals("key 存空串应与 Config.loadSet 一致回退默认值，而非返回空集吞掉默认值",
                def, store.getStringSet("k", def));
    }

    @Test
    public void getStringSet_missingKeyReturnsDefault() {
        FakePrefs prefs = new FakePrefs();
        ConfigStore store = new ConfigStore(prefs);
        Set<String> def = new HashSet<>(Arrays.asList("a", "b"));
        assertEquals(def, store.getStringSet("k", def));
    }

    @Test
    public void getStringSet_readsStringSet() {
        FakePrefs prefs = new FakePrefs();
        prefs.values.put("k", new HashSet<>(Arrays.asList("a", "b")));
        ConfigStore store = new ConfigStore(prefs);
        assertEquals(new HashSet<>(Arrays.asList("a", "b")),
                store.getStringSet("k", Collections.singleton("默认")));
    }

    @Test
    public void getStringSet_parsesCsvString() {
        FakePrefs prefs = new FakePrefs();
        prefs.values.put("k", " a , b ,, c ");
        ConfigStore store = new ConfigStore(prefs);
        assertEquals(new HashSet<>(Arrays.asList("a", "b", "c")),
                store.getStringSet("k", Collections.singleton("默认")));
    }

    @Test
    public void getStringSet_storedEmptySetWinsOverDefault() {
        // StringSet 分支两侧一致：显式写入的空 Set 是有效值，覆盖默认值（与空串不同）。
        FakePrefs prefs = new FakePrefs();
        prefs.values.put("k", new HashSet<String>());
        ConfigStore store = new ConfigStore(prefs);
        assertEquals(Collections.emptySet(),
                store.getStringSet("k", new HashSet<>(Arrays.asList("a", "b"))));
    }

    /** 只实现读取路径用到的两个方法，其余返回默认值（模式与 ConfigTest.FakePrefs 一致）。 */
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
