package com.netease.cloudmusic.music.biz.setting.activity;

/**
 * 单测夹具：模拟宿主 SettingActivity，但故意不提供 V8()。
 * 用于验证 Profile 标记 critical 的 quiet 挂载点缺失会进入 criticalFailed。
 */
public class SettingActivity {

    public void unrelated() {
    }
}
