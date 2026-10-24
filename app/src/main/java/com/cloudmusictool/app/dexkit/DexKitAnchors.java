package com.cloudmusictool.app.dexkit;

/**
 * 逻辑名 -> 真实 dex 字符串字面量表。
 *
 * 重要：DexKit 的 ClassMatcher.addUsingString 匹配的是「该类方法里实际使用的
 * dex 字符串字面量」，不是类名/方法名。本表所有值均用 dexsql 的 method_strings
 * 在 9.6.08（versionCode 9006008）上验证过，并确认只命中目标类。
 *
 * 维护方式：
 *   SELECT method_class, value FROM method_strings
 *   WHERE value='<anchor>' GROUP BY method_class;
 * 若出现新的候选类，必须补充第二个 anchor 或改为结构匹配。
 */
public final class DexKitAnchors {

    private DexKitAnchors() {
    }

    /** AD-01 开屏广告管理器。 */
    public static final String[] LOADING_AD_MANAGER = {
            "adInfoCacheEmpty",
            "onAdGet, getFactLoadingAd addAdActivity"
    };

    /** AD-01 免广 AB 实验开关（q60.w0）。 */
    public static final String[] Q60_W0 = {
            "loading_ad_judge"
    };

    /** UI-05 底栏数据仓库（th0.h）。 */
    public static final String[] TH0_H = {
            "BottomNavigationRepository"
    };

    /** UI-05 底栏列表构造（rh0.c）；两个 anchor 组合唯一。 */
    public static final String[] RH0_C = {
            "tabCodeList",
            "context"
    };

    /** UI-08 蓝牙/车载自动播放配置（tt0.d）。 */
    public static final String[] TT0_D = {
            "bluetoothAutoPlay2History"
    };

    /** UI-08 云村自动播放配置（vt0.r）。 */
    public static final String[] VT0_R = {
            "HOMEPAGE_BOTTOM_TAB_PUBLISH_GUIDE"
    };

    /** UI-08 自动播放总开关（lv0.j）。 */
    public static final String[] LV0_J = {
            "key_start_music_after_launch_mode_enable_"
    };

    /** UI-01 应用内更新入口（fw0.e）。 */
    public static final String[] FW0_E = {
            "MarketUpdate [logMarketStage] fail, stage="
    };

    /** UI-01 更新状态机（appupdate.d）。 */
    public static final String[] APPUPDATE_D = {
            "apkRemindVersionCode"
    };

    /** UI-08 / UI-09 用户设置聚合类（jn）。 */
    public static final String[] JN = {
            "MESSAGE_CENTER_NEW_SONG_VERSION_CODE_TEMP"
    };

    /** UI-04 顶部频道 ViewPager2 适配器（dr.h，继承 FragmentStateAdapter）。 */
    public static final String[] DR_H = {
            "RecommendTwoFlowAdapter"
    };
}
