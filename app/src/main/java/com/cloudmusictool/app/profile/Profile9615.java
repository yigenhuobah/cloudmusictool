package com.cloudmusictool.app.profile;

/**
 * 网易云 9.6.15（versionCode 9006015）的挂载点集合。
 *
 * <p>2026-10-29 用 9.6.13 的 Profile 对 9.6.15 base.apk 做全量体检：145/145 OK，
 * 说明两个版本的类/方法坐标一致，因此直接复用 {@link Profile9613} 的 mounts，
 * 只把 versionCode/versionName 换成 9.6.15，避免复制一份会漂移的清单。
 */
public final class Profile9615 {

    public static final TargetProfile PROFILE =
            new TargetProfile(9006015L, "9.6.15", Profile9613.PROFILE.mounts);

    private Profile9615() {
    }
}
