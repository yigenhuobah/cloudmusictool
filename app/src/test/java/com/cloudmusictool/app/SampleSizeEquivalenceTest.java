package com.cloudmusictool.app;

import com.cloudmusictool.app.hooks.MineBackgroundHooks;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;

/**
 * MineBackgroundHooks.sampleSize（目标进程侧的手工复制实现）与
 * SampleSize.calculateInSampleSize（模块 App 侧标准实现）的逐点等价性。
 *
 * 两处循环体写法不同（(a/2)/s vs a/(2s)），数学上 floor(floor(a/2)/s) ==
 * floor(a/(2s))，但等价性只靠注释承诺（MineBackgroundStore.java:253 起同型
 * 注释）。同仓库两份实现、一份有测试一份没有——这里对边界输入逐点断言两份
 * 实现永远相等，防止单侧被单独改动后行为漂移。MineBackgroundHooks.sampleSize
 * 为包私有 static，反射调用，不为测试放宽可见性。绝对值锚点（2 倍 -> 2 等）
 * 已由 SampleSizeTest 钉住，本测试只钉等价性。
 */
public class SampleSizeEquivalenceTest {

    private static int hooksSampleSize(int outW, int outH, int reqW, int reqH)
            throws Exception {
        Method m = MineBackgroundHooks.class.getDeclaredMethod(
                "sampleSize", int.class, int.class, int.class, int.class);
        m.setAccessible(true);
        return (Integer) m.invoke(null, outW, outH, reqW, reqH);
    }

    private static void assertEquivalent(int outW, int outH, int reqW, int reqH)
            throws Exception {
        int standard = SampleSize.calculateInSampleSize(outW, outH, reqW, reqH);
        assertEquals("out=" + outW + "x" + outH + " req=" + reqW + "x" + reqH,
                standard, hooksSampleSize(outW, outH, reqW, reqH));
    }

    @Test
    public void explicitBoundaries_equivalent() throws Exception {
        // 非法输入（0/负数）两边都返回 1。
        assertEquivalent(0, 3000, 1000, 1000);
        assertEquivalent(4000, -1, 1000, 1000);
        assertEquivalent(4000, 3000, 0, 1000);
        assertEquivalent(4000, 3000, 1000, -1);
        // 恰等于请求 -> 1。
        assertEquivalent(1000, 1000, 1000, 1000);
        assertEquivalent(63, 63, 64, 64);
        // 2 倍 / 4 倍。
        assertEquivalent(2000, 2000, 1000, 1000);
        assertEquivalent(4000, 3000, 1000, 1000);
        // 非 2 幂比例、短边受限。
        assertEquivalent(3000, 1000, 500, 500);
        assertEquivalent(10000, 10000, 100, 100);
        assertEquivalent(1080, 1920, 360, 640);
        // 紧边界：a/2 差 1 不够再降采样。
        assertEquivalent(1999, 1999, 1000, 1000);
    }

    @Test
    public void gridSweep_alwaysEquivalent() throws Exception {
        int[] outSizes = {0, -7, 1, 63, 64, 65, 999, 1000, 1001, 1999, 2000, 4000, 10000};
        int[] reqSizes = {0, -7, 1, 63, 64, 65, 500, 1000};
        for (int outW : outSizes) {
            for (int outH : outSizes) {
                for (int reqW : reqSizes) {
                    for (int reqH : reqSizes) {
                        assertEquivalent(outW, outH, reqW, reqH);
                    }
                }
            }
        }
    }
}
