package com.cloudmusictool.app;

/**
 * BitmapFactory 下采样倍率计算。
 *
 * 纯 Java 类、不引用任何 Android 类型，方便在 JVM 单测里直接验证边界。
 */
public final class SampleSize {

    private SampleSize() {
    }

    /**
     * 标准 inSampleSize 算法。
     *
     * 返回值一定是 2 的幂，并保证按该倍率解码后的宽高都不小于请求尺寸
     * （即宁可多保留像素，也不把图片缩得比请求值更小）。
     * 输入尺寸或请求尺寸非法时返回 1，表示不额外下采样。
     */
    public static int calculateInSampleSize(int outW, int outH, int reqW, int reqH) {
        if (outW <= 0 || outH <= 0 || reqW <= 0 || reqH <= 0) {
            return 1;
        }
        int inSampleSize = 1;
        if (outW > reqW || outH > reqH) {
            int halfW = outW / 2;
            int halfH = outH / 2;
            while (halfW / inSampleSize >= reqW && halfH / inSampleSize >= reqH) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }
}
