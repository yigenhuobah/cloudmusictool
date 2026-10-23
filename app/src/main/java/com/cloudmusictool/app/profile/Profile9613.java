package com.cloudmusictool.app.profile;

import com.cloudmusictool.app.dexkit.DexKitAnchors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 9.6.13 的挂载点总表：由 AD/PV 分片 + UI 分片（hint 第三参 = u）拼装。
 *
 * 9.6.13 相对 9.6.08 的混淆漂移通过 {@link #overrides()} 按 mount id
 * （{@code featureId/logicalName}）覆盖：同 id 的 MountSpec 整体替换，
 * 不修改 9.6.08（Profile9608）的任何坐标。所有覆盖坐标均用 dexsql 在
 * 9.6.13（versionCode 9006013）的 base.apk 上逐条核对（classes/methods/
 * method_strings/decode 交叉验证），并和 9.6.08 的对照缓存比对过语义。
 *
 * 类改名的覆盖里 classCandidates 保留 [新类, 旧类] 两个候选：第一个是
 * 9.6.13 的真实坐标；第二个仅用于让 HookKit.findClassSpec(旧名) 仍能命中
 * 该 spec（旧类在 9.6.13 中不存在，resolver 会按顺序落到新类）。
 * 方法名也改名的（如 LoadingAdManager#E -> H），hook 文件按旧方法名查询时
 * 仍会走各自的签名扫描/DexKit 兜底，profile 覆盖不影响其正确性。
 */
public final class Profile9613 {

    public static final long VERSION_CODE = 9006013L;
    public static final String VERSION_NAME = "9.6.13";

    private static final String[] NONE = new String[0];

    public static final TargetProfile PROFILE = build();

    private Profile9613() {
    }

    /**
     * 9.6.13 坐标覆盖表：key = mount id，value = 替换后的 MountSpec。
     * 未覆盖的 mount 直接沿用 9.6.08 分片里的坐标。
     */
    private static Map<String, MountSpec> overrides() {
        Map<String, MountSpec> out = new LinkedHashMap<>();

        // AD-01 开屏：判定 E(AdInfo)Z -> H；热启动 G(Activity)V -> J
        // （"showAdIfAppForeground"）；数据源 o(Z)ArrayList -> p。
        out.put("AD-01/LoadingAdManager#E",
                MountSpec.method("AD-01", "LoadingAdManager#E", true,
                        "com.netease.cloudmusic.module.ad.LoadingAdManager", "H", "Z",
                        new String[]{"Lcom/netease/cloudmusic/module/ad/meta/AdInfo;"},
                        DexKitAnchors.LOADING_AD_MANAGER));
        out.put("AD-01/LoadingAdManager#G",
                MountSpec.method("AD-01", "LoadingAdManager#G", false,
                        "com.netease.cloudmusic.module.ad.LoadingAdManager", "J", "V",
                        new String[]{"Landroid/app/Activity;"},
                        DexKitAnchors.LOADING_AD_MANAGER));
        out.put("AD-01/LoadingAdManager#o",
                MountSpec.method("AD-01", "LoadingAdManager#o", true,
                        "com.netease.cloudmusic.module.ad.LoadingAdManager", "p",
                        "Ljava/util/ArrayList;", new String[]{"Z"},
                        DexKitAnchors.LOADING_AD_MANAGER));

        // AD-01 免广 AB 开关：q60.w0 -> l40.x0（anchor "loading_ad_judge" 仍唯一命中）。
        out.put("AD-01/q60.w0#g",
                MountSpec.method("AD-01", "q60.w0#g", false,
                        new String[]{"l40.x0", "q60.w0"}, "g", "Z", NONE,
                        DexKitAnchors.Q60_W0));

        // UI-01 更新入口：fw0.e -> gu0.e（anchor 字符串仍在该类，方法 q 签名不变）。
        out.put("UI-01/fw0.e#q",
                MountSpec.method("UI-01", "fw0.e#q", false,
                        new String[]{"gu0.e", "fw0.e"}, "q", "V",
                        new String[]{"Landroid/content/Context;", "Z", "Z"},
                        DexKitAnchors.FW0_E));

        // AD-08 播放页视频广告：k(Ad,ViewGroup,ViewGroup) -> j（renderVideo 不变）。
        out.put("AD-08/PlayerAdJarDelegateImpl#k",
                MountSpec.method("AD-08", "PlayerAdJarDelegateImpl#k", true,
                        "com.netease.cloudmusic.module.ad.playerjar.PlayerAdJarDelegateImpl",
                        "j", "V",
                        new String[]{"Lcom/netease/cloudmusic/meta/Ad;",
                                "Landroid/view/ViewGroup;", "Landroid/view/ViewGroup;"}));

        // AD-09 播客视频广告：z(J,J,Ad,ViewGroup) -> B（render 不变）。
        out.put("AD-09/PodcastVideoAdHelperImpl#z",
                MountSpec.method("AD-09", "PodcastVideoAdHelperImpl#z", false,
                        "com.netease.cloudmusic.module.ad.podcast.PodcastVideoAdHelperImpl",
                        "B", "V",
                        new String[]{"J", "J", "Lcom/netease/cloudmusic/meta/Ad;",
                                "Landroid/view/ViewGroup;"}));

        // PV-06 剪贴板：utils.h2 -> utils.i2（ClipboardUtils，d(Context) 不变）。
        out.put("PV-06/utils.h2#d",
                MountSpec.method("PV-06", "utils.h2#d", true,
                        new String[]{"com.netease.cloudmusic.utils.i2",
                                "com.netease.cloudmusic.utils.h2"},
                        "d", "Landroid/content/ClipData$Item;",
                        new String[]{"Landroid/content/Context;"}));

        // UI-04 顶部频道：hc -> dc；Tc(写 o 字段) -> Pc；Lc(写 q 字段) -> Hc。
        out.put("UI-04/BaseArkV3Fragment#hc",
                MountSpec.method("UI-04", "BaseArkV3Fragment#hc", false,
                        "com.netease.cloudmusic.discovery.view.newframework.BaseArkV3Fragment",
                        "dc", "V",
                        new String[]{
                                "Ljava/util/List;",
                                "Landroidx/viewpager2/widget/ViewPager2;",
                                "Lcom/netease/cloudmusic/discovery/view/widget/"
                                        + "DiscoveryCustomThemeRightIconTabLayout;"
                        }));
        out.put("UI-04/BaseArkV3Fragment#Tc",
                MountSpec.method("UI-04", "BaseArkV3Fragment#Tc", false,
                        "com.netease.cloudmusic.discovery.view.newframework.BaseArkV3Fragment",
                        "Pc", "V", new String[]{"Ljava/util/List;"}));
        out.put("UI-04/BaseArkV3Fragment#Lc",
                MountSpec.method("UI-04", "BaseArkV3Fragment#Lc", false,
                        "com.netease.cloudmusic.discovery.view.newframework.BaseArkV3Fragment",
                        "Hc", "V", new String[]{"Ljava/util/List;"}));

        // UI-04 顶部频道适配器：dr.h -> iq.h（anchor "RecommendTwoFlowAdapter" 仍唯一），T0 -> O0。
        out.put("UI-04/dr.h#T0",
                MountSpec.method("UI-04", "dr.h#T0", true,
                        new String[]{"iq.h", "dr.h"}, "O0", "V",
                        new String[]{"Ljava/util/List;"}, DexKitAnchors.DR_H));

        // UI-05 底栏开关：T8(showNoteAtBottomTab) -> P8；U8(showFMAtBottomTab) -> O8。
        out.put("UI-05/SettingActivity#T8",
                MountSpec.method("UI-05", "SettingActivity#T8", false,
                        "com.netease.cloudmusic.music.biz.setting.activity.SettingActivity",
                        "P8", "Z", NONE));
        out.put("UI-05/SettingActivity#U8",
                MountSpec.method("UI-05", "SettingActivity#U8", false,
                        "com.netease.cloudmusic.music.biz.setting.activity.SettingActivity",
                        "O8", "Z", NONE));

        // UI-05 底栏数据仓库：th0.h -> tf0.h（anchor "BottomNavigationRepository" 仍唯一），h()List 不变。
        out.put("UI-05/th0.h#h",
                MountSpec.method("UI-05", "th0.h#h", true,
                        new String[]{"tf0.h", "th0.h"}, "h", "Ljava/util/List;", NONE,
                        DexKitAnchors.TH0_H));

        // UI-05 底栏列表构造：rh0.c -> rf0.c（anchor "tabCodeList" 仍在），c 签名不变。
        out.put("UI-05/rh0.c#c",
                MountSpec.method("UI-05", "rh0.c#c", false,
                        new String[]{"rf0.c", "rh0.c"}, "c", "Ljava/util/List;",
                        new String[]{
                                "Z",
                                "Ljava/util/List;",
                                "Lcom/netease/cloudmusic/module/strategy/meta/MainTabContext;"
                        },
                        DexKitAnchors.RH0_C));

        // UI-07 青少年弹窗：nr2.c -> en2.c（38 个方法一一对应），checkAndShowYouthDialog 不变。
        out.put("UI-07/nr2.c#checkAndShowYouthDialog",
                MountSpec.method("UI-07", "nr2.c#checkAndShowYouthDialog", false,
                        new String[]{"en2.c", "nr2.c"}, "checkAndShowYouthDialog", "Z",
                        new String[]{"Landroid/content/Context;"}));

        // UI-08 自动播放总开关：V8(double_flow_auto_play_switch) -> Q8。
        out.put("UI-08/SettingActivity#V8",
                MountSpec.method("UI-08", "SettingActivity#V8", true,
                        "com.netease.cloudmusic.music.biz.setting.activity.SettingActivity",
                        "Q8", "Z", NONE));

        // UI-08 蓝牙/车载自动播放：tt0.d -> ur0.d（anchor "bluetoothAutoPlay2History" 仍在），a/d 不变。
        out.put("UI-08/tt0.d#a",
                MountSpec.method("UI-08", "tt0.d#a", false,
                        new String[]{"ur0.d", "tt0.d"}, "a", "Z", NONE, DexKitAnchors.TT0_D));
        out.put("UI-08/tt0.d#d",
                MountSpec.method("UI-08", "tt0.d#d", false,
                        new String[]{"ur0.d", "tt0.d"}, "d", "Z", NONE, DexKitAnchors.TT0_D));

        // UI-08 云村自动播放：vt0.r -> wr0.r（anchor "HOMEPAGE_BOTTOM_TAB_PUBLISH_GUIDE" 仍在），j()Z 不变。
        out.put("UI-08/vt0.r#j",
                MountSpec.method("UI-08", "vt0.r#j", false,
                        new String[]{"wr0.r", "vt0.r"}, "j", "Z", NONE, DexKitAnchors.VT0_R));

        // UI-08 自动播放总开关：lv0.j -> mt0.j（anchor 仍在），e()AutoPlayConfig -> c。
        out.put("UI-08/lv0.j#e",
                MountSpec.method("UI-08", "lv0.j#e", false,
                        new String[]{"mt0.j", "lv0.j"}, "c",
                        "Lcom/netease/cloudmusic/module/strategy/meta/AutoPlayConfig;",
                        NONE, DexKitAnchors.LV0_J));

        // UI-14 歌词视频提示弹窗：oc(Activity,LyricDialogData) -> kc（类内签名唯一）。
        out.put("UI-14/LyricVideoTipsDialog#oc",
                MountSpec.method("UI-14", "LyricVideoTipsDialog#oc", false,
                        "com.netease.cloudmusic.fragment.LyricVideoTipsDialog", "kc", "V",
                        new String[]{
                                "Landroidx/fragment/app/FragmentActivity;",
                                "Lcom/netease/cloudmusic/meta/LyricDialogData;"
                        }));

        return out;
    }

    private static TargetProfile build() {
        List<MountSpec> all = new ArrayList<>();
        all.addAll(ProfileAdPv.mounts());
        all.addAll(ProfileUi.mounts(true));
        applyOverrides(all, overrides());
        return new TargetProfile(VERSION_CODE, VERSION_NAME, all);
    }

    /** 用同 id 的覆盖 spec 替换基础坐标；overrides 里多余的 id 会被忽略。 */
    private static void applyOverrides(List<MountSpec> mounts, Map<String, MountSpec> overrides) {
        if (overrides.isEmpty()) {
            return;
        }
        for (int i = 0; i < mounts.size(); i++) {
            MountSpec override = overrides.get(mounts.get(i).id);
            if (override != null) {
                mounts.set(i, override);
            }
        }
    }
}

