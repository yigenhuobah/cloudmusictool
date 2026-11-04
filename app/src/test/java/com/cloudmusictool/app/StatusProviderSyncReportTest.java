package com.cloudmusictool.app;

import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * StatusProvider 落盘语义（ALL-CRASH-01 / Stage 2 medium #2）：
 *
 * <ul>
 *   <li>{@code sync=true} 必须走 {@code commit()}，只有 commit 成功才返回非 null
 *       Bundle（目标进程据此把 flushNow 判为成功）；</li>
 *   <li>commit 返回 false 或抛异常时返回 null，且不能留下半写入的数据；</li>
 *   <li>普通回传仍走 {@code apply()}；结构拒绝在落盘前发生。</li>
 * </ul>
 *
 * <p>单测走包内可见的 {@link StatusProvider#storeReport}（校验通过后的落盘主体）
 * 与 {@link StatusProvider#prefs()} seam：JVM 单测无法构造可读的 Bundle，
 * 也无法设置真实 Binder UID；payload 仍由真实 {@link ReportProtocol} 校验。
 */
public class StatusProviderSyncReportTest {

    private static final String PAYLOAD = "{\"updatedAt\":1700000000000,"
            + "\"session\":\"s1-deadbeef\","
            + "\"process\":\"com.netease.cloudmusic\","
            + "\"features\":{}}";

    private FakePrefs mPrefs;

    @Before
    public void setUp() {
        mPrefs = new FakePrefs();
    }

    @Test
    public void syncReportCommitsBeforeAck() {
        Object out = provider().storeReport(PAYLOAD, StatusProvider.TYPE_MOUNTS, true);

        assertNotNull("sync commit 成功必须返回非 null Bundle", out);
        assertEquals("sync 路径必须恰好 commit 一次", 1, mPrefs.commitCount);
        assertEquals("sync 路径不得走 apply", 0, mPrefs.applyCount);
        assertEquals(PAYLOAD, mPrefs.values.get(StatusProvider.KEY_STATE));
    }

    @Test
    public void syncReportReturnsNullWhenCommitRejected() {
        mPrefs.commitResult = false;

        assertNull("commit=false 时 StatusProvider 必须返回 null",
                provider().storeReport(PAYLOAD, StatusProvider.TYPE_MOUNTS, true));

        assertEquals(1, mPrefs.commitCount);
        assertFalse("commit 失败不能留下半写入数据",
                mPrefs.values.containsKey(StatusProvider.KEY_STATE));
    }

    @Test
    public void syncReportReturnsNullWhenCommitThrows() {
        mPrefs.commitThrows = true;

        assertNull(provider().storeReport(PAYLOAD, StatusProvider.TYPE_MOUNTS, true));

        assertEquals(1, mPrefs.commitCount);
        assertFalse(mPrefs.values.containsKey(StatusProvider.KEY_STATE));
    }

    @Test
    public void syncReportRejectedBeforeCommitForUnknownPayload() {
        assertNull(provider().storeReport("{\"unknown\":1}",
                StatusProvider.TYPE_MOUNTS, true));

        assertEquals("结构拒绝必须发生在落盘前", 0, mPrefs.commitCount);
        assertEquals(0, mPrefs.applyCount);
        assertTrue(mPrefs.values.isEmpty());
    }

    @Test
    public void asyncReportUsesApplyAndAcks() {
        Object out = provider().storeReport(PAYLOAD, StatusProvider.TYPE_MOUNTS, false);

        assertNotNull(out);
        assertEquals("普通回传必须走 apply", 1, mPrefs.applyCount);
        assertEquals("普通回传不得同步 commit", 0, mPrefs.commitCount);
        assertEquals(PAYLOAD, mPrefs.values.get(StatusProvider.KEY_STATE));
    }

    private StatusProvider provider() {
        return new StatusProvider() {
            @Override
            SharedPreferences prefs() {
                return mPrefs;
            }
        };
    }

    /** 可配置 commit/apply 结果的内存 SharedPreferences；apply 与 commit 分开计数。 */
    private static final class FakePrefs implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();
        volatile boolean commitResult = true;
        volatile boolean commitThrows;
        int commitCount;
        int applyCount;

        @Override
        public Map<String, ?> getAll() {
            return new HashMap<>(values);
        }

        @Override
        public String getString(String key, String defValue) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : defValue;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Set<String> getStringSet(String key, Set<String> defValues) {
            Object value = values.get(key);
            return value instanceof Set ? (Set<String>) value : defValues;
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
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : defValue;
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
        private final FakePrefs mPrefs;
        private final Map<String, Object> mPuts = new LinkedHashMap<>();
        private final Set<String> mRemovals = new HashSet<>();

        FakeEditor(FakePrefs prefs) {
            mPrefs = prefs;
        }

        @Override
        public SharedPreferences.Editor putString(String key, String value) {
            mPuts.put(key, value);
            mRemovals.remove(key);
            return this;
        }

        @Override
        public SharedPreferences.Editor putStringSet(String key, Set<String> values) {
            mPuts.put(key, values == null ? null : new HashSet<>(values));
            mRemovals.remove(key);
            return this;
        }

        @Override
        public SharedPreferences.Editor putInt(String key, int value) {
            mPuts.put(key, value);
            mRemovals.remove(key);
            return this;
        }

        @Override
        public SharedPreferences.Editor putLong(String key, long value) {
            mPuts.put(key, value);
            mRemovals.remove(key);
            return this;
        }

        @Override
        public SharedPreferences.Editor putFloat(String key, float value) {
            mPuts.put(key, value);
            mRemovals.remove(key);
            return this;
        }

        @Override
        public SharedPreferences.Editor putBoolean(String key, boolean value) {
            mPuts.put(key, value);
            mRemovals.remove(key);
            return this;
        }

        @Override
        public SharedPreferences.Editor remove(String key) {
            mRemovals.add(key);
            mPuts.remove(key);
            return this;
        }

        @Override
        public SharedPreferences.Editor clear() {
            mPuts.clear();
            mRemovals.clear();
            return this;
        }

        @Override
        public boolean commit() {
            mPrefs.commitCount++;
            if (mPrefs.commitThrows) {
                throw new IllegalStateException("commit failed");
            }
            if (!mPrefs.commitResult) {
                return false;
            }
            applyOps();
            return true;
        }

        @Override
        public void apply() {
            mPrefs.applyCount++;
            applyOps();
        }

        private void applyOps() {
            for (String key : mRemovals) {
                mPrefs.values.remove(key);
            }
            mPrefs.values.putAll(mPuts);
        }
    }
}
