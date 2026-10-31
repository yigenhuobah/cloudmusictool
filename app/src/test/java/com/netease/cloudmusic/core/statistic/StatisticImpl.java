package com.netease.cloudmusic.core.statistic;

/**
 * 单测夹具：模拟宿主 StatisticImpl，但故意不提供 log / logJSON / logApm / forceApmUpload，
 * 用于验证 ApmHooks 与 StatisticHooks 在 quiet 定位失败时走 markMountMissing 显式记账。
 */
public class StatisticImpl {

    public void unrelated() {
    }
}
