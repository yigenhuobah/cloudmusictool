package com.cloudmusictool.app;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * ConfigStore remote prefs 解析状态测试。
 *
 * 回归点：resolveRemote() 重新解析失败（remotePreferences 返回 null）时不能
 * 保留上一次的 prefs 代理，否则 remote() 仍为 true：UI 据此放开开关，读取却
 * 继续走已失效的 service 代理。remote() 必须与最近一次解析结果一致。
 */
public class ConfigStoreRemoteTest {

    @Test
    public void failedResolution_clearsStaleRemoteProxy() {
        FakePrefs stale = new FakePrefs();
        stale.values.put("switch", true);
        ConfigStore store = new ConfigStore(stale, null);
        assertTrue(store.remote());
        assertTrue(store.get("switch", false));

        assertFalse("解析失败必须返回 false", store.applyRemote(null, null));

        assertFalse("remote() 必须与解析结果一致", store.remote());
        assertFalse("读取不能再走旧代理", store.get("switch", false));
    }

    @Test
    public void resolveRemote_withoutBoundService_clearsStaleRemoteProxy() {
        FakePrefs stale = new FakePrefs();
        ConfigStore store = new ConfigStore(stale, null);

        assertFalse(store.resolveRemote());

        assertFalse("service 断开后 remote() 必须如实为 false", store.remote());
    }

    @Test
    public void successfulResolution_bindsNewProxy() {
        FakePrefs fresh = new FakePrefs();
        fresh.values.put("switch", true);
        ConfigStore store = new ConfigStore(null, null);

        assertTrue(store.applyRemote(null, fresh));

        assertTrue(store.remote());
        assertTrue(store.get("switch", false));
    }

    /** 只实现读取路径用到的方法，其余返回默认值（模式与 ConfigStoreSetTest 一致）。 */
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
        public Editor edit() {
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
        public SharedPreferences.Editor putStringSet(String key, Set<String> value) {
            values.put(key, value);
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
