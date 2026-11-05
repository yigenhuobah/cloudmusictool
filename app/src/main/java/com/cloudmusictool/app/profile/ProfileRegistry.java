package com.cloudmusictool.app.profile;

import com.cloudmusictool.app.anchor.AnchorTable;
import com.cloudmusictool.app.anchor.SupportedVersions;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 版本 -> Profile 的注册表，并保存当前进程解析出的活动 Profile。
 *
 * <p>版本身份（versionCode/versionName）来自 anchors.json（{@link SupportedVersions}），
 * 这里只负责把版本名映射到编译期运行时 Profile 类。运行时仍使用
 * {@link Profile9608}/{@link Profile9613}/{@link Profile9615}/{@link Profile9620}
 * （保守策略），ProfileAnchorEquivalenceTest 锁定它们与 anchors.json 派生表逐项一致。
 */
public final class ProfileRegistry {

    /** 版本名 -> 运行时 Profile；键取自 Profile 自身，避免在注册表里重复写版本号。 */
    private static final Map<String, TargetProfile> RUNTIME = new LinkedHashMap<>();

    static {
        for (TargetProfile profile : new TargetProfile[]{
                Profile9608.PROFILE, Profile9613.PROFILE, Profile9615.PROFILE,
                Profile9620.PROFILE}) {
            RUNTIME.put(profile.versionName, profile);
        }
    }

    /** 当前宿主版本对应的 Profile；null 表示未适配，HookKit 回退硬编码路径。 */
    private static volatile TargetProfile sActive;

    private ProfileRegistry() {
    }

    public static TargetProfile forVersionCode(long versionCode) {
        return runtime(SupportedVersions.byCode(versionCode));
    }

    public static TargetProfile forVersionName(String versionName) {
        return runtime(SupportedVersions.byName(versionName));
    }

    private static TargetProfile runtime(AnchorTable.Version version) {
        return version == null ? null : RUNTIME.get(version.versionName);
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
