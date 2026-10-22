package com.cloudmusictool.app;

/**
 * 功能清单。key 同时是模块 App 内 remote prefs 的键，也是目标进程
 * getRemotePreferences("config") 的键，两处必须一致。
 *
 * id 是统一编号（AD/UI/PV/EN/EX-NN），只用于文档、日志、诊断页与验收，
 * 不参与 remote key。编号一旦发布不复用。
 *
 * blockedReason 非空表示本版本未实装或属于后续阶段：只登记、不注册 hook。
 * 默认值策略：AD/UI 默认开，PV/EN/EX 默认关，blocked 一律默认关。
 */
public enum Feature {

    // ---------------------------------------------------------------- AD 广告净化
    SPLASH_AD("AD-01", R.string.f_ad_01_title, R.string.f_ad_01_summary,
            "ad_01_splash", true, Group.AD, null),
    LAUNCH_DIALOG("AD-02", R.string.f_ad_02_title, R.string.f_ad_02_summary,
            "ad_02_launch_dialog", true, Group.AD, null),
    AD_REPORT_JUMP("AD-03", R.string.f_ad_08_title, R.string.f_ad_08_summary,
            "ad_08_report_jump", true, Group.AD, null),
    SEARCH_AD("AD-04", R.string.f_ad_09_title, R.string.f_ad_09_summary,
            "ad_09_search_ad", true, Group.AD, null),
    AD_PRELOAD("AD-05", R.string.f_ad_10_title, R.string.f_ad_10_summary,
            "ad_10_ad_preload", true, Group.AD, null),
    FEED_AD("AD-06", R.string.f_ad_04_title, R.string.f_ad_04_summary,
            "ad_04_feed", true, Group.AD, null),
    COMMENT_AD("AD-07", R.string.f_ad_05_title, R.string.f_ad_05_summary,
            "ad_05_comment", true, Group.AD, null),
    PLAYER_AD("AD-08", R.string.f_ad_06_title, R.string.f_ad_06_summary,
            "ad_06_player", true, Group.AD, null),
    AUDIO_CLIP_AD("AD-09", R.string.f_ad_07_title, R.string.f_ad_07_summary,
            "ad_07_audio_clip", true, Group.AD, null),

    // ---------------------------------------------------------------- UI 界面精简
    UPDATE_DIALOG("UI-01", R.string.f_ad_03_title, R.string.f_ad_03_summary,
            "ad_03_update_dialog", true, Group.UI, null),
    STARTUP_DELAY("UI-02", R.string.f_ui_05_title, R.string.f_ui_05_summary,
            "ui_05_startup_delay", true, Group.UI, null),
    HOME_BLOCKS("UI-03", R.string.f_ui_01_title, R.string.f_ui_01_summary,
            "ui_01_home_blocks", true, Group.UI, null),
    TOP_CHANNELS("UI-04", R.string.f_ui_02_title, R.string.f_ui_02_summary,
            "ui_02_top_channel", true, Group.UI, null),
    BOTTOM_NAV("UI-05", R.string.f_ui_03_title, R.string.f_ui_03_summary,
            "ui_03_bottom_nav", true, Group.UI, null),
    DRAWER("UI-06", R.string.f_ui_04_title, R.string.f_ui_04_summary,
            "ui_04_drawer", false, Group.UI,
            "9.6.08 未找到可靠的抽屉数据挂载点"),
    HINTS("UI-07", R.string.f_ui_06_title, R.string.f_ui_06_summary,
            "ui_06_hints", true, Group.UI, null),
    AUTOPLAY("UI-08", R.string.f_ui_07_title, R.string.f_ui_07_summary,
            "ui_07_autoplay", true, Group.UI, null),
    PENDANT("UI-09", R.string.f_ui_09_title, R.string.f_ui_09_summary,
            "ui_09_pendant", true, Group.UI, null),
    PLAYER_HINTS("UI-10", R.string.f_ui_10_title, R.string.f_ui_10_summary,
            "ui_10_player_hints", true, Group.UI, null),
    SEARCH_GUIDE("UI-11", R.string.f_ui_11_title, R.string.f_ui_11_summary,
            "ui_11_search_guide", true, Group.UI, null),
    PODCAST_GUIDE("UI-12", R.string.f_ui_12_title, R.string.f_ui_12_summary,
            "ui_12_podcast_guide", true, Group.UI, null),
    WIDGET_PUSH_GUIDE("UI-13", R.string.f_ui_13_title, R.string.f_ui_13_summary,
            "ui_13_widget_push_guide", true, Group.UI, null),
    LYRICS_HINT("UI-14", R.string.f_ui_14_title, R.string.f_ui_14_summary,
            "ui_14_lyrics_hint", true, Group.UI, null),
    COMMUNITY_LIVE("UI-15", R.string.f_ui_15_title, R.string.f_ui_15_summary,
            "ui_15_community_live", true, Group.UI, null),

    // ---------------------------------------------------------------- PV 隐私保护
    OAID_REPORT("PV-01", R.string.f_pv_01_title, R.string.f_pv_01_summary,
            "pv_01_oaid", false, Group.PV, null),
    LOG_TASKS("PV-02", R.string.f_pv_02_title, R.string.f_pv_02_summary,
            "pv_02_log_tasks", false, Group.PV, null),
    STARTUP_BLACKLIST("PV-03", R.string.f_pv_03_title, R.string.f_pv_03_summary,
            "pv_03_startup_blacklist", false, Group.PV, null),
    APM("PV-04", R.string.f_pv_04_title, R.string.f_pv_04_summary,
            "pv_04_apm", false, Group.PV, null),
    STATS_OBSERVE("PV-05", R.string.f_pv_05_title, R.string.f_pv_05_summary,
            "pv_05_stats_observe", false, Group.PV, null),
    CLIPBOARD("PV-06", R.string.f_pv_06_title, R.string.f_pv_06_summary,
            "pv_06_clipboard", false, Group.PV, null),

    // ---------------------------------------------------------------- EN 功能增强
    MINE_BACKGROUND("EN-01", R.string.f_ui_16_title, R.string.f_ui_16_summary,
            "ui_16_mine_background", false, Group.EN, null),

    // ---------------------------------------------------------------- EX 实验功能
    DARK_MODE("EX-01", R.string.f_ui_08_title, R.string.f_ui_08_summary,
            "ui_08_dark_mode", false, Group.EX, null),
    STARTUP_ORCHESTRATION("EX-02", R.string.f_pv_07_title, R.string.f_pv_07_summary,
            "pv_07_startup_orchestration", false, Group.EX, null);

    public enum Group {
        AD,
        UI,
        PV,
        EN,
        EX
    }

    public final String id;
    public final int titleRes;
    public final int summaryRes;
    public final String key;
    /** 编译期内置默认值：无 PROP_CAP_REMOTE 时用。 */
    public final boolean defaultEnabled;
    public final Group group;
    /** 非空 = 本版本不注册任何 hook；同时是“暂不支持”的原因。 */
    public final String blockedReason;

    Feature(String id, int titleRes, int summaryRes, String key,
            boolean defaultEnabled, Group group, String blockedReason) {
        this.id = id;
        this.titleRes = titleRes;
        this.summaryRes = summaryRes;
        this.key = key;
        this.defaultEnabled = defaultEnabled;
        this.group = group;
        this.blockedReason = blockedReason;
    }

    public boolean blocked() {
        return blockedReason != null;
    }
}
