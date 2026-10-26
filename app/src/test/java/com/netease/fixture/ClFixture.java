package com.netease.fixture;

/** MountResolver ClassLoader 隔离测试用的最小目标类（包名满足 com.netease.* 归属校验）。 */
public final class ClFixture {

    public String ping() {
        return "pong";
    }
}
