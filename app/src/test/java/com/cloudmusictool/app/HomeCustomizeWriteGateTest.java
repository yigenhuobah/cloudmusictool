package com.cloudmusictool.app;

import io.github.yigenhuobah.core.WriteGate;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 写回状态机的契约测试（{@link io.github.yigenhuobah.core.WriteGate}）。
 *
 * <p>锁住两条曾经会导致“界面与远端不一致”的语义：
 * <ul>
 *   <li>旧写入的失败回调不能覆盖用户更新的选择（连续两次修改的竞态）；</li>
 *   <li>失败回滚到最近一次确认落盘的值，而不是本次修改前的乐观值。</li>
 * </ul>
 * 同一状态机同时被设置页的功能开关/桌面图标与首页定制页的多选项复用。
 */
public class HomeCustomizeWriteGateTest {

    private static Set<String> set(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    @Test
    public void staleFailureCannotRollbackNewerSelection() {
        WriteGate<Set<String>> gate = new WriteGate<>();
        gate.seed("k", set("s0"));
        long first = gate.begin("k");
        long second = gate.begin("k");

        assertFalse("旧写入的失败结果不能应用到界面",
                gate.onResult("k", first, false, set("s1")));
        assertEquals("回滚目标应是最近确认落盘的 s0，而不是旧写入当时的 s1",
                set("s0"), gate.rollback("k", set("s1")));
        assertTrue("最新一次写入的结果仍要应用", gate.onResult("k", second, true, set("s2")));
        assertEquals("最新写入成功后确认值前进到 s2",
                set("s2"), gate.rollback("k", set("s1")));
    }

    @Test
    public void failureRollsBackToLastConfirmedNotOptimisticValue() {
        WriteGate<Set<String>> gate = new WriteGate<>();
        gate.seed("k", set("s0"));

        long first = gate.begin("k");
        assertTrue(gate.onResult("k", first, true, set("s1")));

        long second = gate.begin("k");
        assertTrue(gate.onResult("k", second, false, set("s2")));

        assertEquals("commit 失败时必须回到 s1（已确认落盘），不能用 before/乐观值",
                set("s1"), gate.rollback("k", set("s2")));
    }

    @Test
    public void supersededSuccessStillBecomesConfirmed() {
        WriteGate<Set<String>> gate = new WriteGate<>();
        long first = gate.begin("k");
        long second = gate.begin("k");

        assertFalse("旧写入结果被更新的写入取代，不改界面",
                gate.onResult("k", first, true, set("s1")));
        assertEquals("但它确实落盘了，后续失败必须回滚到它",
                set("s1"), gate.rollback("k", set("s0")));

        assertTrue(gate.onResult("k", second, false, set("s2")));
        assertEquals(set("s1"), gate.rollback("k", set("s0")));
    }

    @Test
    public void snapshotSeedNeverOverridesConfirmedValue() {
        WriteGate<Set<String>> gate = new WriteGate<>();
        long token = gate.begin("k");
        assertTrue(gate.onResult("k", token, true, set("confirmed")));

        // refresh 的旧快照晚到：可以重绘界面，但不能污染确认值。
        gate.seed("k", set("stale"));

        assertEquals(set("confirmed"), gate.rollback("k", set("fallback")));
    }

    @Test
    public void rollbackFallsBackToSnapshotThenCallerValue() {
        WriteGate<Set<String>> gate = new WriteGate<>();

        assertEquals("没有任何快照/确认值时才用调用方兜底值",
                set("fallback"), gate.rollback("k", set("fallback")));

        gate.seed("k", set("snapshot"));
        assertEquals(set("snapshot"), gate.rollback("k", set("fallback")));
    }

    @Test
    public void tokensAreMonotonicAndPerKeyIndependent() {
        WriteGate<Set<String>> gate = new WriteGate<>();
        long a1 = gate.begin("a");
        long b1 = gate.begin("b");
        long a2 = gate.begin("a");

        assertTrue("令牌必须单调递增（不依赖系统时钟回拨）", b1 > a1 && a2 > b1);
        assertTrue("b 的结果仍是最新", gate.onResult("b", b1, false, set("b")));
        assertFalse("a 的旧结果已被 a2 取代", gate.onResult("a", a1, false, set("a")));
    }

    @Test
    public void clearDropsInFlightResults() {
        WriteGate<Boolean> gate = new WriteGate<>();
        long token = gate.begin("app_hide_icon");
        gate.clear();

        assertFalse("Fragment 销毁后在途结果不能再改界面",
                gate.onResult("app_hide_icon", token, true, true));
        assertEquals("布尔开关的回滚兜底值", Boolean.TRUE,
                gate.rollback("app_hide_icon", Boolean.TRUE));
    }
}
