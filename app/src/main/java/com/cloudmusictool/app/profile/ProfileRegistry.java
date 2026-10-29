package com.cloudmusictool.app.profile;

/** 版本 -> Profile 的注册表，并保存当前进程解析出的活动 Profile。 */
public final class ProfileRegistry {

    /** 当前宿主版本对应的 Profile；null 表示未适配，HookKit 回退硬编码路径。 */
    private static volatile TargetProfile sActive;

    private ProfileRegistry() {
    }

    public static TargetProfile forVersionCode(long versionCode) {
        if (versionCode == 9006015L) {
            return Profile9615.PROFILE;
        }
        if (versionCode == 9006013L) {
            return Profile9613.PROFILE;
        }
        if (versionCode == 9006008L) {
            return Profile9608.PROFILE;
        }
        return null;
    }

    public static TargetProfile forVersionName(String versionName) {
        if ("9.6.15".equals(versionName)) {
            return Profile9615.PROFILE;
        }
        if ("9.6.13".equals(versionName)) {
            return Profile9613.PROFILE;
        }
        if ("9.6.08".equals(versionName)) {
            return Profile9608.PROFILE;
        }
        return null;
    }

    /** 由 VersionGate 在版本校验通过后调用；传 null 表示 best-effort（回退硬编码）。 */
    public static void setActive(TargetProfile profile) {
        sActive = profile;
    }

    /** 当前活动 Profile；未适配/未知版本时为 null。 */
    public static TargetProfile active() {
        return sActive;
    }
}
