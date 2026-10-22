package com.cloudmusictool.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * {@link SampleSize#calculateInSampleSize} 的纯函数边界测试。
 *
 * 只引用纯 Java 类，不触发 Android 类型加载，可直接在 JVM 单测运行。
 */
public class SampleSizeTest {

    @Test
    public void exactMatch_noDownsample() {
        assertEquals(1, SampleSize.calculateInSampleSize(1440, 2560, 1440, 2560));
    }

    @Test
    public void smallerThanRequest_noDownsample() {
        assertEquals(1, SampleSize.calculateInSampleSize(800, 600, 1440, 2560));
    }

    @Test
    public void twiceOfRequest_returnsTwo() {
        assertEquals(2, SampleSize.calculateInSampleSize(4000, 3000, 1000, 1000));
    }

    @Test
    public void fourTimesOfRequest_returnsFour() {
        assertEquals(4, SampleSize.calculateInSampleSize(8000, 6000, 1000, 1000));
    }

    @Test
    public void unequalDimensions_limitedByShorterSide() {
        // 宽度足够 4 倍，高度只够 2 倍，取 2。
        assertEquals(2, SampleSize.calculateInSampleSize(8000, 3000, 1000, 1000));
        // 高度方向无法再下采样，保持 1。
        assertEquals(1, SampleSize.calculateInSampleSize(800, 3000, 400, 3000));
    }

    @Test
    public void largeImage_returnsPowerOfTwoNotLessThanRequest() {
        assertEquals(64, SampleSize.calculateInSampleSize(10000, 10000, 100, 100));
    }

    @Test
    public void nonPositiveRequest_returnsOne() {
        assertEquals(1, SampleSize.calculateInSampleSize(4000, 3000, 0, 1000));
        assertEquals(1, SampleSize.calculateInSampleSize(4000, 3000, 1000, 0));
        assertEquals(1, SampleSize.calculateInSampleSize(4000, 3000, -1, -1));
    }

    @Test
    public void nonPositiveSource_returnsOne() {
        assertEquals(1, SampleSize.calculateInSampleSize(0, 3000, 1000, 1000));
        assertEquals(1, SampleSize.calculateInSampleSize(4000, -1, 1000, 1000));
    }
}
