package com.cloudmusictool.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 诊断回传协议：结构白名单、条数与长度边界、主进程门禁、会话身份与新鲜度判定、
 * 失败功能计数。
 *
 * 合法形态夹具对齐真实生产者字段面（HookState.buildJson / ExperimentState.buildJson，
 * 见 HookStateJsonContractTest 的生产侧钉子）；篡改用例覆盖：
 * 未知根键、非 Feature 名的 features 键、超长字符串、items 超 MAX_ENTRIES、类型错位。
 */
public class ReportProtocolTest {

    @Test
    public void mountsPayloadAcceptsProducerShape() throws Exception {
        // HookState.buildJson 的字段面：根级汇总 + 每个 Feature 的完整 feature 对象。
        JSONObject features = new JSONObject();
        for (Feature f : Feature.values()) {
            JSONObject o = new JSONObject();
            o.put("id", f.id);
            o.put("enabled", true);
            o.put("expected", 3);
            o.put("installed", 2);
            o.put("failed", 1);
            o.put("criticalFailed", 1);
            o.put("entryCount", 20);
            o.put("hit", true);
            o.put("hitCount", 12);
            o.put("degraded", false);
            o.put("unavailable", false);
            o.put("lastFailure", "方法未找到");
            features.put(f.name(), o);
        }
        JSONObject root = new JSONObject();
        root.put("updatedAt", System.currentTimeMillis());
        root.put("session", "1700000000000");
        root.put("process", ReportProtocol.MAIN_PROCESS);
        root.put("enabledFeatures", Feature.values().length);
        root.put("installed", 18);
        root.put("expected", 30);
        root.put("failed", 1);
        root.put("noHitFeatures", 0);
        root.put("degradedFeatures", 0);
        root.put("unavailableFeatures", 0);
        root.put("features", features);

        assertNull(ReportProtocol.validateMountsPayload(root));
    }

    @Test
    public void mountsTruncatedVariantIsAccepted() throws Exception {
        // HookState.buildJson 超 64KB 时退回摘要：无 features，truncated=true，session 保留。
        JSONObject root = new JSONObject();
        root.put("updatedAt", System.currentTimeMillis());
        root.put("session", "1700000000000");
        root.put("enabledFeatures", 3);
        root.put("installed", 5);
        root.put("expected", 9);
        root.put("failed", 1);
        root.put("noHitFeatures", 0);
        root.put("degradedFeatures", 0);
        root.put("unavailableFeatures", 0);
        root.put("truncated", true);

        assertNull(ReportProtocol.validateMountsPayload(root));
    }

    @Test
    public void mountsPayloadAcceptsAbtestRootKey() throws Exception {
        // abtest 是新版本将新增的根键，当前版本没有也合法；其字符串同样受长度约束。
        JSONObject abtest = new JSONObject();
        abtest.put("ad_01_splash", "group_a");
        JSONObject root = mountsRoot();
        root.put("abtest", abtest);

        assertNull(ReportProtocol.validateMountsPayload(root));
    }

    @Test
    public void mountsPayloadAcceptsCrashesRootKey() throws Exception {
        // crashes = 内置诊断最近崩溃（HookState crashes 通道），字符串数组、[0] 为最新。
        JSONObject root = mountsRoot();
        root.put("crashes", new JSONArray()
                .put("2026-11-01 12:00:00 main java.lang.NullPointerException "
                        + "at com.a.b(C.java:10); com.a.d(D.java:22)")
                .put("2026-11-01 11:59:00 main java.lang.Error at com.x.y(E.java:1)"));

        assertNull("crashes 根键必须在 mounts 白名单内",
                ReportProtocol.validateMountsPayload(root));
    }

    @Test
    public void mountsPayloadRejectsMoreThanThreeCrashes() throws Exception {
        JSONObject root = mountsRoot();
        JSONArray crashes = new JSONArray();
        for (int i = 0; i <= ReportProtocol.MAX_CRASH_ENTRIES; i++) {
            crashes.put("crash " + i);
        }
        root.put("crashes", crashes);

        assertNotNull("crashes 超过 MAX_CRASH_ENTRIES 必须整体拒绝",
                ReportProtocol.validateMountsPayload(root));
    }

    @Test
    public void mountsPayloadRejectsOverlongCrashEntry() throws Exception {
        // MAX_VALUE_CHARS=256 对 crashes 的每个元素单独生效。
        JSONObject root = mountsRoot();
        root.put("crashes", new JSONArray()
                .put(repeat('x', ReportProtocol.MAX_VALUE_CHARS + 1)));

        assertNotNull("崩溃条目超过 MAX_VALUE_CHARS 必须拒绝",
                ReportProtocol.validateMountsPayload(root));
    }

    @Test
    public void mountsPayloadRejectsNonStringCrashEntry() throws Exception {
        JSONObject root = mountsRoot();
        root.put("crashes", new JSONArray().put(new JSONObject().put("time", 1)));

        assertNotNull("崩溃条目必须是字符串",
                ReportProtocol.validateMountsPayload(root));
    }

    @Test
    public void mountsPayloadRejectsNonArrayCrashes() throws Exception {
        JSONObject root = mountsRoot();
        root.put("crashes", "not-an-array");

        assertNotNull("crashes 必须是数组",
                ReportProtocol.validateMountsPayload(root));
    }

    @Test
    public void mountsPayloadAcceptsStringAtLengthBoundary() throws Exception {
        JSONObject root = mountsRoot();
        root.getJSONObject("features").put("SPLASH_AD",
                feature().put("lastFailure", repeat('x', ReportProtocol.MAX_VALUE_CHARS)));

        assertNull(ReportProtocol.validateMountsPayload(root));
    }

    @Test
    public void mountsPayloadAcceptsEntryCountField() throws Exception {
        JSONObject root = mountsRoot();
        root.getJSONObject("features").put("SPLASH_AD",
                feature().put("entryCount", 7).put("hitCount", 2));

        assertNull("entryCount 是 HookState 新增的合法字段",
                ReportProtocol.validateMountsPayload(root));
    }

    @Test
    public void mountsPayloadRejectsUnknownRootKey() throws Exception {
        JSONObject root = mountsRoot();
        root.put("sneaky", "any value");

        assertNotNull("未知根键必须整体拒绝",
                ReportProtocol.validateMountsPayload(root));
    }

    @Test
    public void mountsPayloadRejectsNonFeatureKey() throws Exception {
        JSONObject root = mountsRoot();
        root.getJSONObject("features").put("NOT_A_FEATURE", feature());

        assertNotNull("features 键必须是 Feature 枚举名",
                ReportProtocol.validateMountsPayload(root));
    }

    @Test
    public void mountsPayloadRejectsUnknownFeatureField() throws Exception {
        JSONObject root = mountsRoot();
        root.getJSONObject("features").put("SPLASH_AD",
                feature().put("junk", 1));

        assertNotNull("feature 对象键超出白名单必须拒绝",
                ReportProtocol.validateMountsPayload(root));
    }

    @Test
    public void mountsPayloadRejectsOverlongString() throws Exception {
        JSONObject root = mountsRoot();
        root.getJSONObject("features").put("SPLASH_AD",
                feature().put("lastFailure", repeat('x', ReportProtocol.MAX_VALUE_CHARS + 1)));

        assertNotNull("字符串值超过 MAX_VALUE_CHARS 必须拒绝",
                ReportProtocol.validateMountsPayload(root));
    }

    @Test
    public void mountsPayloadRejectsTypeMismatch() throws Exception {
        JSONObject notObject = mountsRoot();
        notObject.put("features", "not-an-object");
        assertNotNull("features 必须是对象", ReportProtocol.validateMountsPayload(notObject));

        JSONObject entryNotObject = mountsRoot();
        entryNotObject.getJSONObject("features").put("SPLASH_AD", "not-an-object");
        assertNotNull("feature 条目必须是对象",
                ReportProtocol.validateMountsPayload(entryNotObject));
    }

    @Test
    public void mountsPayloadRejectsNonMainProcess() throws Exception {
        JSONObject root = mountsRoot();
        root.put("process", "com.netease.cloudmusic:play");

        assertNotNull(ReportProtocol.validateMountsPayload(root));
    }

    @Test
    public void experimentsPayloadAcceptsProducerShape() throws Exception {
        // ExperimentState.buildJson 的字段面：updatedAt + items[{key, group, source, count, lastAt}]。
        JSONObject root = new JSONObject();
        root.put("updatedAt", System.currentTimeMillis());
        JSONArray items = new JSONArray();
        items.put(new JSONObject()
                .put("key", "ad_01_splash")
                .put("group", "c")
                .put("source", "unknown")
                .put("count", 3)
                .put("lastAt", System.currentTimeMillis()));
        root.put("items", items);

        assertNull(ReportProtocol.validateExperimentsPayload(root));
    }

    @Test
    public void experimentsPayloadAcceptsExactlyMaxEntries() throws Exception {
        JSONObject root = new JSONObject();
        root.put("updatedAt", System.currentTimeMillis());
        JSONArray items = new JSONArray();
        for (int i = 0; i < ReportProtocol.MAX_ENTRIES; i++) {
            items.put(new JSONObject().put("key", "k" + i).put("count", i));
        }
        root.put("items", items);

        assertNull(ReportProtocol.validateExperimentsPayload(root));
    }

    @Test
    public void experimentsPayloadRejectsMoreThanMaxEntries() throws Exception {
        JSONObject root = new JSONObject();
        root.put("updatedAt", System.currentTimeMillis());
        JSONArray items = new JSONArray();
        for (int i = 0; i <= ReportProtocol.MAX_ENTRIES; i++) {
            items.put(new JSONObject().put("key", "k" + i).put("count", i));
        }
        root.put("items", items);

        assertNotNull("items 超过 MAX_ENTRIES 必须整体拒绝",
                ReportProtocol.validateExperimentsPayload(root));
    }

    @Test
    public void experimentsPayloadRejectsUnknownRootKey() throws Exception {
        JSONObject root = new JSONObject();
        root.put("updatedAt", System.currentTimeMillis());
        root.put("items", new JSONArray());
        root.put("sneaky", 1);

        assertNotNull("未知根键必须整体拒绝",
                ReportProtocol.validateExperimentsPayload(root));
    }

    @Test
    public void experimentsPayloadRejectsOverlongString() throws Exception {
        JSONObject root = new JSONObject();
        root.put("updatedAt", System.currentTimeMillis());
        root.put("items", new JSONArray().put(new JSONObject()
                .put("key", "k")
                .put("group", repeat('g', ReportProtocol.MAX_VALUE_CHARS + 1))));

        assertNotNull("字符串值超过 MAX_VALUE_CHARS 必须拒绝",
                ReportProtocol.validateExperimentsPayload(root));
    }

    @Test
    public void experimentsPayloadRejectsTypeMismatch() throws Exception {
        JSONObject itemsNotArray = new JSONObject();
        itemsNotArray.put("updatedAt", 1L);
        itemsNotArray.put("items", new JSONObject().put("k", 1));
        assertNotNull("items 必须是数组",
                ReportProtocol.validateExperimentsPayload(itemsNotArray));

        JSONObject itemNotObject = new JSONObject();
        itemNotObject.put("updatedAt", 1L);
        itemNotObject.put("items", new JSONArray().put("not-an-object"));
        assertNotNull("数组每条必须是对象",
                ReportProtocol.validateExperimentsPayload(itemNotObject));
    }

    @Test
    public void payloadLengthIsBounded() {
        assertFalse(ReportProtocol.isValidPayload(null));
        assertFalse(ReportProtocol.isValidPayload(""));
        assertTrue(ReportProtocol.isValidPayload("{}"));
        StringBuilder payload = new StringBuilder();
        for (int i = 0; i < ReportProtocol.MAX_PAYLOAD_CHARS; i++) {
            payload.append('x');
        }
        assertTrue(ReportProtocol.isValidPayload(payload.toString()));
        payload.append('x');
        assertFalse(ReportProtocol.isValidPayload(payload.toString()));
    }

    @Test
    public void mainProcessReportDetection() throws Exception {
        JSONObject main = new JSONObject();
        main.put("process", ReportProtocol.MAIN_PROCESS);
        assertTrue(ReportProtocol.isMainProcessReport(main));
        assertTrue("旧版无 process 字段按主进程兼容",
                ReportProtocol.isMainProcessReport(new JSONObject()));

        JSONObject play = new JSONObject();
        play.put("process", "com.netease.cloudmusic:play");
        assertFalse(ReportProtocol.isMainProcessReport(play));
        assertFalse(ReportProtocol.isMainProcessReport("not-json"));
        assertFalse(ReportProtocol.isMainProcessReport((JSONObject) null));
    }

    @Test
    public void mountsPayloadAcceptsSessionRootKey() throws Exception {
        // session = 目标进程会话标识 "s<启动毫秒>-<8 位随机 hex>"，旧版缺省也合法。
        JSONObject root = mountsRoot();
        root.put("session", "s1700000000123-3fa4c2d1");

        assertNull(ReportProtocol.validateMountsPayload(root));
    }

    @Test
    public void experimentsPayloadAcceptsSessionRootKey() throws Exception {
        // experiments 通道同样允许进程会话标识；旧生产者不写该键也合法。
        JSONObject root = new JSONObject();
        root.put("updatedAt", System.currentTimeMillis());
        root.put("session", "s1700000000123-3fa4c2d1");
        root.put("items", new JSONArray());

        assertNull("experiments 通道必须接受 session 根键",
                ReportProtocol.validateExperimentsPayload(root));
    }

    @Test
    public void failedFeatureCountCountsEnabledFailuresOnly() throws Exception {
        JSONObject features = new JSONObject();
        features.put("SPLASH_AD", feature(true, 0, "类未找到"));          // installed<=0 -> 失败
        features.put("FEED_AD", feature(true, 3, "方法未找到"));           // 仅普通失败 -> 诊断
        features.put("COMMENT_AD", feature(true, 2, "")
                .put("criticalFailed", 1));                                // critical -> 失败
        features.put("CLIPBOARD", feature(false, 0, "类未找到"));          // 未启用 -> 忽略

        JSONObject root = new JSONObject();
        root.put("process", ReportProtocol.MAIN_PROCESS);
        root.put("updatedAt", System.currentTimeMillis());
        root.put("features", features);

        assertEquals("已启用且 installed<=0 或 criticalFailed>0 计为失败", 2,
                ReportProtocol.failedFeatureCount(root));
    }

    @Test
    public void failedFeatureCountIgnoresOrdinaryLastFailure() throws Exception {
        JSONObject features = new JSONObject();
        features.put("FEED_AD", feature(true, 3, "方法未找到"));

        JSONObject root = new JSONObject();
        root.put("process", ReportProtocol.MAIN_PROCESS);
        root.put("updatedAt", System.currentTimeMillis());
        root.put("features", features);

        assertEquals("普通 lastFailure 只作诊断，不计入横幅",
                0, ReportProtocol.failedFeatureCount(root));
    }

    @Test
    public void failedFeatureCountIgnoresStaleOrMissingReportTime() throws Exception {
        final long now = 1_700_000_000_000L;
        long ttl = ReportProtocol.DEFAULT_TTL_MS;
        JSONObject features = new JSONObject().put("SPLASH_AD", feature(true, 0, "类未找到"));

        JSONObject root = new JSONObject();
        root.put("process", ReportProtocol.MAIN_PROCESS);
        root.put("features", features);

        root.put("updatedAt", now - ttl - 1L);
        assertEquals("超过 TTL 视为未知", 0,
                ReportProtocol.failedFeatureCount(root, now, ttl));

        root.put("updatedAt", now - ttl);
        assertEquals("TTL 边界内仍有效", 1,
                ReportProtocol.failedFeatureCount(root, now, ttl));

        root.put("updatedAt", 0L);
        assertEquals("updatedAt<=0 视为未知", 0,
                ReportProtocol.failedFeatureCount(root, now, ttl));

        root.remove("updatedAt");
        assertEquals("缺少 report_time 视为未知", 0,
                ReportProtocol.failedFeatureCount(root, now, ttl));
    }

    @Test
    public void failedFeatureCountIgnoresFeatureWithoutMounts() throws Exception {
        JSONObject features = new JSONObject();
        features.put("AB_TEST_PANEL", new JSONObject()
                .put("enabled", true)
                .put("expected", 0)
                .put("installed", 0)
                .put("failed", 0)
                .put("criticalFailed", 0)
                .put("unavailable", true));

        JSONObject root = new JSONObject();
        root.put("process", ReportProtocol.MAIN_PROCESS);
        root.put("updatedAt", System.currentTimeMillis());
        root.put("features", features);

        assertEquals("expected=0 的零挂载点功能不应计入失败横幅",
                0, ReportProtocol.failedFeatureCount(root));
    }

    @Test
    public void failedFeatureCountIgnoresSecondaryProcess() throws Exception {
        JSONObject features = new JSONObject();
        features.put("AUDIO_CLIP_AD", feature(true, 0, "类未找到"));
        JSONObject root = new JSONObject();
        root.put("process", "com.netease.cloudmusic:play");
        root.put("updatedAt", System.currentTimeMillis());
        root.put("features", features);

        assertEquals(0, ReportProtocol.failedFeatureCount(root));
    }

    @Test
    public void failedFeatureCountHandlesMissingData() {
        assertEquals(0, ReportProtocol.failedFeatureCount(null));
        assertEquals(0, ReportProtocol.failedFeatureCount(new JSONObject()));
    }

    // ---------------------------------------------------------- 会话与新鲜度（纯逻辑）

    @Test
    public void isFreshHonoursTtlAndBoundaries() {
        final long now = 1_700_000_000_000L;
        final long ttl = ReportProtocol.DEFAULT_TTL_MS;

        assertEquals("TTL 固定 10 分钟", 10 * 60 * 1000L, ttl);
        assertFalse("updatedAt<=0 表示从未回传", ReportProtocol.isFresh(0L, now, ttl));
        assertFalse(ReportProtocol.isFresh(-1L, now, ttl));
        assertTrue(ReportProtocol.isFresh(now, now, ttl));
        assertTrue("TTL 边界内（含边界）仍新鲜", ReportProtocol.isFresh(now - ttl, now, ttl));
        assertFalse(ReportProtocol.isFresh(now - ttl - 1L, now, ttl));
        assertTrue("少量时钟抖动仍按新鲜处理", ReportProtocol.isFresh(now + 5_000L, now, ttl));
        assertTrue("未来时间戳在允许偏差边界内仍新鲜",
                ReportProtocol.isFresh(now + ReportProtocol.MAX_FUTURE_SKEW_MS, now, ttl));
        assertFalse("未来时间戳超过 5 分钟视为过期（回拨后的旧快照不能长期新鲜）",
                ReportProtocol.isFresh(now + ReportProtocol.MAX_FUTURE_SKEW_MS + 1L, now, ttl));
        assertFalse("时钟回拨：updatedAt 远在未来必须过期",
                ReportProtocol.isFresh(now + ttl * 10, now, ttl));
        assertFalse("ttl<=0 视为过期", ReportProtocol.isFresh(now, now, 0L));
    }

    @Test
    public void sessionIdentityDecidesNewSession() {
        final String current = "s1700000000000-3fa4c2d1";

        assertFalse("同一会话重复上报：仍属同一进程",
                ReportProtocol.isNewSession(current, current));
        assertFalse("两边都没有会话标识：无从判断",
                ReportProtocol.isNewSession(null, null));
        assertFalse(ReportProtocol.isNewSession("", ""));
        assertTrue("首次带会话标识的回传即新会话",
                ReportProtocol.isNewSession(null, current));
        assertTrue(ReportProtocol.isNewSession("", current));
        assertTrue("重启后随机后缀不同即新会话",
                ReportProtocol.isNewSession(current, "s1700000000000-9b8e7f60"));
        assertTrue("时钟回拨后新会话毫秒值更小，按身份仍判为新会话（不整包丢弃）",
                ReportProtocol.isNewSession(current, "s1699999990000-3fa4c2d1"));
        assertTrue("旧格式（纯数字）与新旧格式混合：字符串不同即新会话",
                ReportProtocol.isNewSession("1700000000000", current));
        assertTrue(ReportProtocol.isNewSession(current, "1699999990000"));
        assertFalse("旧版目标进程没有 session：按同会话兼容（模块 App 可单独升级）",
                ReportProtocol.isNewSession(current, ""));
        assertFalse(ReportProtocol.isNewSession(current, null));
    }

    @Test
    public void failedFeatureCountIgnoresFutureReportTime() throws Exception {
        final long now = 1_700_000_000_000L;
        JSONObject features = new JSONObject().put("SPLASH_AD", feature(true, 0, "类未找到"));
        JSONObject root = new JSONObject();
        root.put("process", ReportProtocol.MAIN_PROCESS);
        root.put("features", features);

        root.put("updatedAt", now + ReportProtocol.MAX_FUTURE_SKEW_MS + 1L);
        assertEquals("时钟回拨导致的未来时间戳视为未知", 0,
                ReportProtocol.failedFeatureCount(root, now, ReportProtocol.DEFAULT_TTL_MS));

        root.put("updatedAt", now + 30_000L);
        assertEquals("允许偏差内的时钟抖动仍计入", 1,
                ReportProtocol.failedFeatureCount(root, now, ReportProtocol.DEFAULT_TTL_MS));
    }

    @Test
    public void malformedJsonIsRejected() {
        assertFalse(ReportProtocol.isMainProcessReport("{"));
        assertFalse(ReportProtocol.isMainProcessReport("not-json"));
        assertFalse(ReportProtocol.isMainProcessReport("[]"));
        assertFalse(ReportProtocol.isMainProcessReport("null"));
        assertFalse(ReportProtocol.isMainProcessReport("{\"process\":"));
        assertFalse(ReportProtocol.isMainProcessReport("   "));
    }

    @Test
    public void nullAndEmptyStringInputsAreRejected() {
        assertFalse(ReportProtocol.isMainProcessReport((String) null));
        assertFalse(ReportProtocol.isMainProcessReport(""));
        assertEquals(0, ReportProtocol.failedFeatureCount((JSONObject) null));
        assertNotNull(ReportProtocol.validateMountsPayload(null));
        assertNotNull(ReportProtocol.validateExperimentsPayload(null));
    }

    // ------------------------------------------------------------------ helpers

    /** mounts 通道的最小合法根（HookState.buildJson 字段面，features 留空由用例填充）。 */
    private JSONObject mountsRoot() throws Exception {
        JSONObject root = new JSONObject();
        root.put("updatedAt", System.currentTimeMillis());
        root.put("session", "1700000000000");
        root.put("process", ReportProtocol.MAIN_PROCESS);
        root.put("enabledFeatures", 1);
        root.put("installed", 1);
        root.put("expected", 1);
        root.put("failed", 0);
        root.put("noHitFeatures", 0);
        root.put("degradedFeatures", 0);
        root.put("unavailableFeatures", 0);
        root.put("features", new JSONObject());
        return root;
    }

    private JSONObject feature() throws Exception {
        return new JSONObject().put("enabled", true);
    }

    private String repeat(char c, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(c);
        }
        return builder.toString();
    }

    private JSONObject feature(boolean enabled, int installed, String skip) throws Exception {
        JSONObject o = new JSONObject();
        o.put("enabled", enabled);
        o.put("installed", installed);
        o.put("expected", installed + (skip == null || skip.isEmpty() ? 0 : 1));
        o.put("lastFailure", skip);
        return o;
    }
}
