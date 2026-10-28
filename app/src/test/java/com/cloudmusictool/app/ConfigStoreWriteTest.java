package com.cloudmusictool.app;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * ConfigStore 写入结果判定的契约测试。
 *
 * <p>覆盖三类曾经会“UI 显示已保存、目标进程读不到”的路径：
 * <ul>
 *   <li>remote prefs 不可用时必须返回 false，且绝不回退写本地；</li>
 *   <li>remote commit() 返回 false / 抛异常时不能当作保存成功；</li>
 *   <li>写失败时 UI 决策函数必须保留旧值（回滚），只有 SAVED 才采用新值。</li>
 * </ul>
 */
public class ConfigStoreWriteTest {

    // ------------------------------------------------------- 纯决策函数

    @Test
    public void writeResult_remoteUnavailableIsNeverSaved() {
        assertEquals(ConfigStore.WriteResult.UNAVAILABLE,
                ConfigStore.WriteResult.of(false, true));
        assertEquals(ConfigStore.WriteResult.UNAVAILABLE,
                ConfigStore.WriteResult.of(false, false));
        assertFalse("remote 不可用时即使 commit 误报 true 也不能算保存成功",
                ConfigStore.WriteResult.of(false, true).saved());
    }

    @Test
    public void writeResult_commitRejectedIsNotSaved() {
        ConfigStore.WriteResult result = ConfigStore.WriteResult.of(true, false);
        assertEquals(ConfigStore.WriteResult.REJECTED, result);
        assertFalse(result.saved());
    }

    @Test
    public void writeResult_commitOkIsSaved() {
        ConfigStore.WriteResult result = ConfigStore.WriteResult.of(true, true);
        assertEquals(ConfigStore.WriteResult.SAVED, result);
        assertTrue(result.saved());
    }

    @Test
    public void valueAfterWrite_keepsOldValueUnlessSaved() {
        Set<String> before = new LinkedHashSet<>(Arrays.asList("a", "b"));
        Set<String> attempted = new LinkedHashSet<>(Arrays.asList("c"));
        assertSame("commit 被拒绝时必须回滚到旧值",
                before, ConfigStore.WriteResult.valueAfterWrite(
                        ConfigStore.WriteResult.REJECTED, before, attempted));
        assertSame("remote 不可用时必须回滚到旧值",
                before, ConfigStore.WriteResult.valueAfterWrite(
                        ConfigStore.WriteResult.UNAVAILABLE, before, attempted));
        assertSame("写异常时必须回滚到旧值",
                before, ConfigStore.WriteResult.valueAfterWrite(
                        ConfigStore.WriteResult.FAILED, before, attempted));
        assertEquals("只有 SAVED 才采用新值",
                attempted, ConfigStore.WriteResult.valueAfterWrite(
                        ConfigStore.WriteResult.SAVED, before, attempted));
    }

    // ------------------------------------------------ remote 不可用回退

    @Test
    public void writesReturnFalseAndNeverTouchLocalWhenRemoteUnavailable() {
        FakePrefs local = new FakePrefs();
        ConfigStore store = new ConfigStore(local);

        assertFalse(store.put("bool_key", true));
        assertFalse(store.putString("string_key", "value"));
        assertFalse(store.putStringSet("set_key", Collections.singleton("a")));

        assertTrue("框架未绑定时不能静默写本地", local.values.isEmpty());
        assertEquals("框架未绑定时不能发起任何 commit", 0, local.commitCount.get());
        assertFalse(store.remote());
    }

    @Test
    public void putStringBlocking_remoteUnavailableReturnsUnavailable() {
        FakePrefs local = new FakePrefs();
        ConfigStore store = new ConfigStore(local);

        ConfigStore.WriteResult result = store.putStringBlocking("k", "v");

        assertEquals(ConfigStore.WriteResult.UNAVAILABLE, result);
        assertFalse(result.saved());
        assertTrue(local.values.isEmpty());
        assertEquals(0, local.commitCount.get());
    }

    // ------------------------------------------------------- commit 判定

    @Test
    public void putStringBlocking_commitOkIsSaved() {
        FakePrefs remote = new FakePrefs();
        ConfigStore store = new ConfigStore(remote, null);

        ConfigStore.WriteResult result = store.putStringBlocking("k", "v");

        assertEquals(ConfigStore.WriteResult.SAVED, result);
        assertTrue(result.saved());
        assertEquals("v", remote.values.get("k"));
        assertEquals(1, remote.commitCount.get());
    }

    @Test
    public void putStringBlocking_commitRejectedIsNotSaved() {
        FakePrefs remote = new FakePrefs();
        remote.commitResult = false;
        ConfigStore store = new ConfigStore(remote, null);

        ConfigStore.WriteResult result = store.putStringBlocking("k", "v");

        assertEquals(ConfigStore.WriteResult.REJECTED, result);
        assertFalse(result.saved());
        assertFalse("commit 被拒绝时不能留下写入值", remote.values.containsKey("k"));
    }

    @Test
    public void putStringBlocking_exceptionIsFailed() {
        FakePrefs remote = new FakePrefs();
        remote.commitThrows = true;
        ConfigStore store = new ConfigStore(remote, null);

        ConfigStore.WriteResult result = store.putStringBlocking("k", "v");

        assertEquals(ConfigStore.WriteResult.FAILED, result);
        assertFalse(result.saved());
    }

    // ------------------------------------------------------- 异步写回调

    @Test
    public void asyncPut_reportsCommitFailureToCallback() throws Exception {
        FakePrefs remote = new FakePrefs();
        remote.commitResult = false;
        ConfigStore store = new ConfigStore(remote, null);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean saved = new AtomicBoolean(true);

        assertTrue("remote 可用时应入队", store.put("k", true, value -> {
            saved.set(value);
            latch.countDown();
        }));
        assertTrue("回调未执行", latch.await(5, TimeUnit.SECONDS));

        assertFalse("commit 失败必须回调 false，UI 才能回滚", saved.get());
        assertFalse(remote.values.containsKey("k"));
    }

    @Test
    public void asyncPut_reportsCommitSuccessToCallback() throws Exception {
        FakePrefs remote = new FakePrefs();
        ConfigStore store = new ConfigStore(remote, null);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean saved = new AtomicBoolean(false);

        assertTrue(store.put("k", true, value -> {
            saved.set(value);
            latch.countDown();
        }));
        assertTrue("回调未执行", latch.await(5, TimeUnit.SECONDS));

        assertTrue(saved.get());
        assertEquals(Boolean.TRUE, remote.values.get("k"));
    }

    @Test
    public void putStringSet_fallsBackToCsvWhenRemoteRejectsStringSet() throws Exception {
        FakePrefs remote = new FakePrefs();
        remote.stringSetThrows = true;
        ConfigStore store = new ConfigStore(remote, null);
        Set<String> value = new LinkedHashSet<>(Arrays.asList("a", "b"));
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean saved = new AtomicBoolean(false);

        assertTrue(store.putStringSet("k", value, ok -> {
            saved.set(ok);
            latch.countDown();
        }));
        assertTrue("回调未执行", latch.await(5, TimeUnit.SECONDS));

        assertTrue(saved.get());
        Object stored = remote.values.get("k");
        assertTrue("StringSet 不支持时必须回退 CSV 字符串", stored instanceof String);
        assertEquals("a,b", stored);
        assertEquals("读取侧必须能解析回同一集合",
                value, PrefsSetCodec.read(remote, "k"));
    }

    // ------------------------------------------------------------- fake

    /**
     * 可配置 commit 结果的内存 SharedPreferences。
     * commit=false 时丢弃本次编辑，模拟 remote prefs 拒绝写入。
     */
    private static final class FakePrefs implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();
        volatile boolean commitResult = true;
        volatile boolean commitThrows;
        volatile boolean stringSetThrows;
        final AtomicInteger commitCount = new AtomicInteger();

        @Override
        public Map<String, ?> getAll() {
            return new HashMap<>(values);
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
            return new FakeEditor(this);
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {
        }
    }

    private static final class FakeEditor implements SharedPreferences.Editor {
        private final FakePrefs prefs;
        private final Map<String, Object> puts = new LinkedHashMap<>();
        private final Set<String> removals = new HashSet<>();
        private boolean clear;

        FakeEditor(FakePrefs prefs) {
            this.prefs = prefs;
        }

        @Override
        public SharedPreferences.Editor putString(String key, String value) {
            puts.put(key, value);
            removals.remove(key);
            return this;
        }

        @Override
        public SharedPreferences.Editor putStringSet(String key, Set<String> values) {
            if (prefs.stringSetThrows) {
                throw new IllegalArgumentException("StringSet unsupported");
            }
            puts.put(key, values == null ? null : new HashSet<>(values));
            removals.remove(key);
            return this;
        }

        @Override
        public SharedPreferences.Editor putInt(String key, int value) {
            puts.put(key, value);
            removals.remove(key);
            return this;
        }

        @Override
        public SharedPreferences.Editor putLong(String key, long value) {
            puts.put(key, value);
            removals.remove(key);
            return this;
        }

        @Override
        public SharedPreferences.Editor putFloat(String key, float value) {
            puts.put(key, value);
            removals.remove(key);
            return this;
        }

        @Override
        public SharedPreferences.Editor putBoolean(String key, boolean value) {
            puts.put(key, value);
            removals.remove(key);
            return this;
        }

        @Override
        public SharedPreferences.Editor remove(String key) {
            removals.add(key);
            puts.remove(key);
            return this;
        }

        @Override
        public SharedPreferences.Editor clear() {
            clear = true;
            return this;
        }

        @Override
        public boolean commit() {
            prefs.commitCount.incrementAndGet();
            if (prefs.commitThrows) {
                throw new IllegalStateException("commit failed");
            }
            if (!prefs.commitResult) {
                return false;
            }
            if (clear) {
                prefs.values.clear();
            }
            for (String key : removals) {
                prefs.values.remove(key);
            }
            prefs.values.putAll(puts);
            return true;
        }

        @Override
        public void apply() {
            commit();
        }
    }
}
