package com.netease.cloudmusic.process.task.app.create;

/**
 * 单测夹具：模拟宿主 NeedLogToFileTask，但故意不提供 init() 方法，
 * 用于验证 LogTaskHooks 在 quiet 定位失败时走 markMountMissing 显式记账。
 */
public class NeedLogToFileTask {

    public void execute() {
    }
}
