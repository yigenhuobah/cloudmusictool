package com.cloudmusictool.app.hooks;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * EN-01 缓存指纹决策：external 与 Provider 兜底路径共用同一套纯函数判定。
 * 关键约束是 fail-open：任何一侧拿不到指纹（null）都不得丢弃可用缓存。
 */
public class MineBackgroundFingerprintTest {

    @Test
    public void noCachedFingerprint_neverStale() {
        // 旧缓存 / Provider 不支持 query 时没有指纹可比，维持旧行为直接用缓存。
        assertFalse(MineBackgroundHooks.fingerprintStale(null, new long[]{100L, 10L}));
        assertFalse(MineBackgroundHooks.fingerprintStale(null, null));
    }

    @Test
    public void fingerprintUnavailable_failsOpen() {
        // stat/查询异常时当前指纹为 null：不能因此判定过期。
        assertFalse(MineBackgroundHooks.fingerprintStale(new long[]{100L, 10L}, null));
    }

    @Test
    public void missingSource_isStale() {
        // Provider 明确返回 0 行（文件已删除）→ 缓存必须过期。
        assertTrue(MineBackgroundHooks.fingerprintStale(
                new long[]{100L, 10L}, new long[0]));
    }

    @Test
    public void unchangedFingerprint_notStale() {
        assertFalse(MineBackgroundHooks.fingerprintStale(
                new long[]{100L, 10L}, new long[]{100L, 10L}));
    }

    @Test
    public void modifiedTimeChanged_isStale() {
        assertTrue(MineBackgroundHooks.fingerprintStale(
                new long[]{100L, 10L}, new long[]{101L, 10L}));
    }

    @Test
    public void lengthChanged_isStale() {
        assertTrue(MineBackgroundHooks.fingerprintStale(
                new long[]{100L, 10L}, new long[]{100L, 11L}));
    }

    @Test
    public void malformedCachedFingerprint_isStale() {
        assertTrue(MineBackgroundHooks.fingerprintStale(
                new long[]{100L}, new long[]{100L, 10L}));
    }
}
