package com.cloudmusictool.app;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * ConfigStore 恢复默认的契约测试。
 *
 * <p>恢复后的状态必须与全新安装一致：所有 Feature 写入编译期默认值，
 * set/aux key 被移除（缺失即默认），config_version 写回当前版本；
 * 写入必须是一次 editor 事务，且以读回结果判定成功。
 */
public class ConfigStoreResetTest {

    @Test
    public void resetPlan_coversEveryFeatureDefaultAndFreshInstallRemovals() {
        ConfigStore.ResetPlan plan = ConfigStore.buildResetPlan();

        assertEquals(Feature.values().length, plan.booleans.size());
        for (Feature f : Feature.values()) {
            assertEquals("缺少默认值或值不正确: " + f.id,
                    f.defaultEnabled, plan.booleans.get(f.key));
        }
        assertTrue(plan.removals.contains(Config.KEY_HOME_BLOCKS_FILTER));
        assertTrue(plan.removals.contains(Config.KEY_TOP_CHANNELS_KEEP));
        assertTrue(plan.removals.contains(Config.KEY_BOTTOM_NAV_KEEP));
        assertTrue(plan.removals.contains(Config.KEY_STARTUP_TASK_BLOCKLIST));
        assertTrue(plan.removals.contains(Config.KEY_ABTEST_FORCE_MAP));
        assertTrue(plan.removals.contains(Config.KEY_DEXKIT_SELFTEST));
        assertEquals(Config.CURRENT_CONFIG_VERSION, plan.configVersion);
    }

    @Test
    public void resetToDefaults_writesOneTransactionAndVerifiesReadBack() throws Exception {
        FakePrefs remote = new FakePrefs();
        for (Feature f : Feature.values()) {
            remote.values.put(f.key, !f.defaultEnabled);
        }
        remote.values.put(Config.KEY_HOME_BLOCKS_FILTER, new HashSet<>(Arrays.asList("a", "b")));
        remote.values.put(Config.KEY_TOP_CHANNELS_KEEP, "a,b");
        remote.values.put(Config.KEY_BOTTOM_NAV_KEEP, new HashSet<>(Arrays.asList("c")));
        remote.values.put(Config.KEY_STARTUP_TASK_BLOCKLIST, "d,e");
        remote.values.put(Config.KEY_ABTEST_FORCE_MAP, "{\"tabMerge\":\"t1\"}");
        remote.values.put(Config.KEY_DEXKIT_SELFTEST, true);
        remote.values.put(Config.KEY_CONFIG_VERSION, 9);
        // 读回必须走 commit 之外的句柄；这里给一个共享底层存储的独立实例。
        FakePrefs fresh = remote.freshHandle();
        ConfigStore store = new ConfigStore(remote, null, () -> fresh);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);

        assertTrue(store.resetToDefaults(ok -> {
            success.set(ok);
            latch.countDown();
        }));
        assertTrue("回调未执行", latch.await(5, TimeUnit.SECONDS));

        assertTrue("读回值全部匹配时必须报告成功", success.get());
        assertEquals("必须只有一次 commit", 1, remote.commitCount.get());
        for (Feature f : Feature.values()) {
            assertEquals("开关默认值不正确: " + f.id,
                    f.defaultEnabled, remote.values.get(f.key));
        }
        assertEquals(Config.CURRENT_CONFIG_VERSION, remote.values.get(Config.KEY_CONFIG_VERSION));
        for (String key : Arrays.asList(
                Config.KEY_HOME_BLOCKS_FILTER,
                Config.KEY_TOP_CHANNELS_KEEP,
                Config.KEY_BOTTOM_NAV_KEEP,
                Config.KEY_STARTUP_TASK_BLOCKLIST,
                Config.KEY_ABTEST_FORCE_MAP,
                Config.KEY_DEXKIT_SELFTEST)) {
            assertFalse("全新安装不存在的 key 必须移除: " + key, remote.values.containsKey(key));
        }
    }

    @Test
    public void resetToDefaults_remoteUnavailableReportsFailureWithoutTouchingLocal() throws Exception {
        FakePrefs local = new FakePrefs();
        local.values.put("keep_me", "local");
        ConfigStore store = new ConfigStore(local);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(true);

        boolean enqueued = store.resetToDefaults(ok -> {
            success.set(ok);
            latch.countDown();
        });

        assertTrue("未解析 remote 时也应先入队，由 IO 线程解析后报告结果", enqueued);
        assertTrue("回调未执行", latch.await(5, TimeUnit.SECONDS));
        assertFalse("remote 不可用时必须报告失败", success.get());
        assertTrue("不能回退写模块本地 prefs", local.values.containsKey("keep_me"));
        assertEquals(1, local.values.size());
        assertEquals("不能对本地 prefs 发起 commit", 0, local.commitCount.get());
    }

    @Test
    public void resetToDefaults_resolvesUnboundRemoteBeforeWriting() throws Exception {
        FakePrefs remote = new FakePrefs();
        FakePrefs fresh = remote.freshHandle();
        AtomicBoolean resolved = new AtomicBoolean(false);
        ConfigStore[] holder = new ConfigStore[1];
        ConfigStore store = new ConfigStore(null, null,
                () -> {
                    resolved.set(true);
                    return holder[0].applyRemote(null, remote);
                },
                () -> fresh);
        holder[0] = store;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);

        assertFalse("入队前 remote 尚未解析", store.remote());
        assertTrue("未解析时也必须先入队", store.resetToDefaults(ok -> {
            success.set(ok);
            latch.countDown();
        }));
        assertTrue("回调未执行", latch.await(5, TimeUnit.SECONDS));

        assertTrue("reset 必须在 IO 线程先解析 remote", resolved.get());
        assertTrue("解析成功后应完成恢复", success.get());
        assertEquals(Config.CURRENT_CONFIG_VERSION, remote.values.get(Config.KEY_CONFIG_VERSION));
    }

    @Test
    public void resetToDefaults_readBackMismatchReportsFailure() throws Exception {
        FakePrefs remote = new FakePrefs();
        remote.dropWrites = true;
        FakePrefs fresh = remote.freshHandle();
        ConfigStore store = new ConfigStore(remote, null, () -> fresh);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(true);

        assertTrue(store.resetToDefaults(ok -> {
            success.set(ok);
            latch.countDown();
        }));
        assertTrue("回调未执行", latch.await(5, TimeUnit.SECONDS));

        assertFalse("commit 返回 true 但读回不一致时必须报告失败", success.get());
        assertEquals(1, remote.commitCount.get());
    }

    @Test
    public void resetToDefaults_verifiesOnFreshHandleNotCommitHandle() throws Exception {
        FakePrefs remote = new FakePrefs();
        // commit 会修改 remote；fresh 是 commit 前的快照，只能证明读回没有复用 remote。
        FakePrefs staleFresh = new FakePrefs(new HashMap<>(remote.values));
        ConfigStore store = new ConfigStore(remote, null, () -> staleFresh);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(true);

        assertTrue(store.resetToDefaults(ok -> {
            success.set(ok);
            latch.countDown();
        }));
        assertTrue("回调未执行", latch.await(5, TimeUnit.SECONDS));

        assertFalse("读回必须使用新句柄，不能拿 commit 代理自证", success.get());
        assertEquals("commit 本身已成功写入 remote",
                Config.CURRENT_CONFIG_VERSION, remote.values.get(Config.KEY_CONFIG_VERSION));
    }

    @Test
    public void matchesResetPlan_requiresEveryRemovedKeyAbsent() {
        ConfigStore.ResetPlan plan = ConfigStore.buildResetPlan();
        FakePrefs sp = new FakePrefs();
        for (Map.Entry<String, Boolean> e : plan.booleans.entrySet()) {
            sp.values.put(e.getKey(), e.getValue());
        }
        sp.values.put(Config.KEY_CONFIG_VERSION, plan.configVersion);
        assertTrue("完全重置后的状态必须匹配", ConfigStore.matchesResetPlan(sp, plan));

        for (String key : plan.removals) {
            sp.values.put(key, "leftover");
            assertFalse("残留 key 必须判为不一致: " + key,
                    ConfigStore.matchesResetPlan(sp, plan));
            sp.values.remove(key);
        }
        assertTrue("移除残留后必须重新匹配", ConfigStore.matchesResetPlan(sp, plan));
    }

    @Test
    public void put_writesConfigVersionInSameEditorTransaction() throws Exception {
        FakePrefs remote = new FakePrefs();
        remote.values.put(Config.KEY_CONFIG_VERSION, 5);
        ConfigStore store = new ConfigStore(remote, null);
        Feature feature = Feature.SPLASH_AD;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean saved = new AtomicBoolean(false);

        assertTrue(store.put(feature.key, !feature.defaultEnabled, ok -> {
            saved.set(ok);
            latch.countDown();
        }));
        assertTrue("回调未执行", latch.await(5, TimeUnit.SECONDS));

        assertTrue(saved.get());
        assertEquals(!feature.defaultEnabled, remote.values.get(feature.key));
        assertEquals(Config.CURRENT_CONFIG_VERSION, remote.values.get(Config.KEY_CONFIG_VERSION));
        assertEquals("开关与版本必须同一次提交", 1, remote.commitCount.get());
    }

    /** 可配置 commit 结果的内存 SharedPreferences（模式与 ConfigStoreWriteTest 一致）。 */
    private static final class FakePrefs implements SharedPreferences {
        final Map<String, Object> values;
        volatile boolean commitResult = true;
        volatile boolean commitThrows;
        /** commit 返回 true 但丢弃写入，模拟读回不一致。 */
        volatile boolean dropWrites;
        final AtomicInteger commitCount = new AtomicInteger();

        FakePrefs() {
            this(new HashMap<>());
        }

        FakePrefs(Map<String, Object> values) {
            this.values = values;
        }

        /** 模拟重新拿到的 remote 句柄：独立实例，共享底层存储。 */
        FakePrefs freshHandle() {
            return new FakePrefs(values);
        }

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
            throw new AssertionError("reset must not use Editor.clear()");
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
            if (prefs.dropWrites) {
                return true;
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
