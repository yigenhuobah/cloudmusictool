package com.netease.fixture;

/** MountResolver ClassLoader 隔离测试用的最小目标类（包名满足 com.netease.* 归属校验）。 */
public final class ClFixture {

    public String ping() {
        return "pong";
    }

    /** 与 ClFixture 同 ClassLoader、同名同签名方法，用于验证方法缓存键包含类名。 */
    public static final class Other {

        public String ping() {
            return "pong-other";
        }
    }
}
