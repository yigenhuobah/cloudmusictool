package com.netease.cloudmusic.statistic.monitor;

/**
 * 单测夹具：模拟宿主 LogMonitorConfigManager，但故意不提供 b/c/d/e 方法，
 * 用于验证 StatisticHooks 在 quiet 定位失败时对每个挂载点显式记账。
 */
public class LogMonitorConfigManager {

    public void unrelated() {
    }
}
