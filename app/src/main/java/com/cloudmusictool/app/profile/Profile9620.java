package com.cloudmusictool.app.profile;

import com.cloudmusictool.app.dexkit.DexKitAnchors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网易云 9.6.20（versionCode 9006020）的挂载点集合。
 *
 * <p>基础表复用 {@link Profile9615}（145 个挂载点），随后：
 * <ol>
 *   <li>删除 PV-05 的 {@code LogMonitorConfigManager#d} / {@code #e}：9.6.20 该类
 *       只剩 a/b/c，c()Z 与原 d/e 的 127 opcodes/callee 序列一致，单挂 c 即可
 *       覆盖两条解析路径（与 anchors.json 的 profile.remove 语义一致）；</li>
 *   <li>按 mount id 原位替换 27 个 9.6.20 漂移坐标（{@link #overrides()}）。
 *       类改名的条目 candidates[0] 是新类，其后保留历史类名，保证
 *       {@code HookKit.findClassSpec(旧名)} 仍能命中并重定向到新类。</li>
 * </ol>
 *
 * <p>2026-11-05 对 9.6.20 base.apk 做全量静态体检：143/143 挂载点 OK、
 * 16/16 DexKit 锚点 OK；未做真机逐功能回归。所有坐标均用 dexsql 在
 * 9.6.20（versionCode 9006020）上逐条核对（classes/methods/method_strings/
 * method_calls/opcode_count 与 9.6.15 对照）。
 */
public final class Profile9620 {

    public static final long VERSION_CODE = 9006020L;
    public static final String VERSION_NAME = "9.6.20";

    private static final String[] NONE = new String[0];

    private static final String LOG_MONITOR_D = "PV-05/LogMonitorConfigManager#d";
    private static final String LOG_MONITOR_E = "PV-05/LogMonitorConfigManager#e";

    public static final TargetProfile PROFILE = build();

    private Profile9620() {
    }

    /**
     * 9.6.20 坐标覆盖表：key = mount id，value = 替换后的 MountSpec。
     * 未覆盖的 mount 直接沿用 9.6.15（= 9.6.13）的坐标。
     */
    private static Map<String, MountSpec> overrides() {
        Map<String, MountSpec> out = new LinkedHashMap<>();

        // UI-01 更新入口：fw0.e/gu0.e -> lu0.e（FW0_E anchor 仍在，q 签名不变）。
        out.put("UI-01/fw0.e#q",
                MountSpec.method("UI-01", "fw0.e#q", false,
                        new String[]{"lu0.e", "gu0.e", "fw0.e"}, "q", "V",
                        new String[]{"Landroid/content/Context;", "Z", "Z"},
                        DexKitAnchors.FW0_E));

        // AD-07 评论区广告：y1(CommentListEntry) -> w1（put 字段 j 不变）。
        out.put("AD-07/CommentAdapter#y1",
                MountSpec.method("AD-07", "CommentAdapter#y1", true,
                        "com.netease.cloudmusic.adapter.CommentAdapter", "w1", "V",
                        new String[]{
                                "Lcom/netease/cloudmusic/meta/virtual/CommentListEntry;"
                        }));

        // AD-08 播放页视频广告：q(Ad) -> p（callee/字段访问不变）。
        out.put("AD-08/PlayerAdJarDelegateImpl#q",
                MountSpec.method("AD-08", "PlayerAdJarDelegateImpl#q", true,
                        "com.netease.cloudmusic.module.ad.playerjar."
                                + "PlayerAdJarDelegateImpl",
                        "p", "V", new String[]{"Lcom/netease/cloudmusic/meta/Ad;"}));

        // AD-09 播客视频广告：9.6.13 的 B 在 9.6.20 回退为 z。
        out.put("AD-09/PodcastVideoAdHelperImpl#z",
                MountSpec.method("AD-09", "PodcastVideoAdHelperImpl#z", false,
                        "com.netease.cloudmusic.module.ad.podcast."
                                + "PodcastVideoAdHelperImpl",
                        "z", "V",
                        new String[]{"J", "J", "Lcom/netease/cloudmusic/meta/Ad;",
                                "Landroid/view/ViewGroup;"}));

        // PV-01 OAID 上传入口：o(String) -> m(String)（语义迁移，置信度中）。
        out.put("PV-01/MusicOaidUploadManager#o",
                MountSpec.method("PV-01", "MusicOaidUploadManager#o", true,
                        "com.netease.cloudmusic.oaid.MusicOaidUploadManager", "m", "V",
                        new String[]{"Ljava/lang/String;"}));

        // PV-06 剪贴板：utils.i2 -> utils.t2（d(Context) 返回 ClipData$Item 不变）。
        out.put("PV-06/utils.h2#d",
                MountSpec.method("PV-06", "utils.h2#d", true,
                        new String[]{"com.netease.cloudmusic.utils.t2",
                                "com.netease.cloudmusic.utils.i2",
                                "com.netease.cloudmusic.utils.h2"},
                        "d", "Landroid/content/ClipData$Item;",
                        new String[]{"Landroid/content/Context;"}));

        // ABTEST：abtest2.b#x -> v（同签名、76 opcodes/callee 一一对应）。
        out.put("ABTEST/abtest2.b#x",
                MountSpec.method("ABTEST", "abtest2.b#x", true,
                        "com.netease.cloudmusic.abtest2.b", "v",
                        "Lcom/netease/cloudmusic/abtest2/ABTestConfig;",
                        new String[]{"Ljava/lang/String;", "Z"}));

        // UI-04 顶部频道：hc -> cc；Tc(写 o 字段) -> Oc；Lc(写 q 字段) -> Gc。
        out.put("UI-04/BaseArkV3Fragment#hc",
                MountSpec.method("UI-04", "BaseArkV3Fragment#hc", false,
                        "com.netease.cloudmusic.discovery.view.newframework."
                                + "BaseArkV3Fragment",
                        "cc", "V",
                        new String[]{
                                "Ljava/util/List;",
                                "Landroidx/viewpager2/widget/ViewPager2;",
                                "Lcom/netease/cloudmusic/discovery/view/widget/"
                                        + "DiscoveryCustomThemeRightIconTabLayout;"
                        }));
        out.put("UI-04/BaseArkV3Fragment#Tc",
                MountSpec.method("UI-04", "BaseArkV3Fragment#Tc", false,
                        "com.netease.cloudmusic.discovery.view.newframework."
                                + "BaseArkV3Fragment",
                        "Oc", "V", new String[]{"Ljava/util/List;"}));
        out.put("UI-04/BaseArkV3Fragment#Lc",
                MountSpec.method("UI-04", "BaseArkV3Fragment#Lc", false,
                        "com.netease.cloudmusic.discovery.view.newframework."
                                + "BaseArkV3Fragment",
                        "Gc", "V", new String[]{"Ljava/util/List;"}));

        // UI-04 顶部频道适配器：iq.h -> kq.h（DR_H anchor 在 kq.h），O0 不变。
        out.put("UI-04/dr.h#T0",
                MountSpec.method("UI-04", "dr.h#T0", true,
                        new String[]{"kq.h", "iq.h", "dr.h"}, "O0", "V",
                        new String[]{"Ljava/util/List;"}, DexKitAnchors.DR_H));

        // UI-05 底栏数据仓库：tf0.h -> wf0.h（TH0_H anchor 在 wf0.h），h()List 不变。
        out.put("UI-05/th0.h#h",
                MountSpec.method("UI-05", "th0.h#h", true,
                        new String[]{"wf0.h", "tf0.h", "th0.h"}, "h",
                        "Ljava/util/List;", NONE, DexKitAnchors.TH0_H));

        // UI-05 底栏 drawable 工厂：rf0.c -> uf0.c（RH0_C 两个 anchor 均在），签名不变。
        out.put("UI-05/rh0.c#c",
                MountSpec.method("UI-05", "rh0.c#c", false,
                        new String[]{"uf0.c", "rf0.c", "rh0.c"}, "c",
                        "Ljava/util/List;",
                        new String[]{
                                "Z",
                                "Ljava/util/List;",
                                "Lcom/netease/cloudmusic/module/strategy/meta/"
                                        + "MainTabContext;"
                        },
                        DexKitAnchors.RH0_C));

        // UI-05 9.6.13+ 专属：tf0.o$a -> wf0.o$a；保留旧类候选供
        // BottomTabHooks.tabListBuildClass 的 findClassSpec(tf0.o$a) 门控继续命中。
        out.put("UI-05/tf0.o$a#y",
                MountSpec.method("UI-05", "tf0.o$a#y", false,
                        new String[]{"wf0.o$a", "tf0.o$a"}, "y", "V",
                        new String[]{"Ljava/util/List;", "Z"}));

        // UI-07 青少年弹窗：en2.c -> on2.c（callee utils.vn.I0 + v2.P0 不变）。
        out.put("UI-07/nr2.c#checkAndShowYouthDialog",
                MountSpec.method("UI-07", "nr2.c#checkAndShowYouthDialog", false,
                        new String[]{"on2.c", "en2.c", "nr2.c"},
                        "checkAndShowYouthDialog", "Z",
                        new String[]{"Landroid/content/Context;"}));

        // UI-08 自动播放总开关：utils.jn -> utils.vn（JN anchor 在 vn），m0 不变。
        out.put("UI-08/jn#m0",
                MountSpec.method("UI-08", "jn#m0", false,
                        new String[]{"com.netease.cloudmusic.utils.vn",
                                "com.netease.cloudmusic.utils.jn"},
                        "m0", "Z", NONE, DexKitAnchors.JN));

        // UI-08 蓝牙/车载自动播放：ur0.d -> zr0.d（TT0_D anchor 在 zr0.d），a/d 不变。
        out.put("UI-08/tt0.d#a",
                MountSpec.method("UI-08", "tt0.d#a", false,
                        new String[]{"zr0.d", "ur0.d", "tt0.d"}, "a", "Z", NONE,
                        DexKitAnchors.TT0_D));
        out.put("UI-08/tt0.d#d",
                MountSpec.method("UI-08", "tt0.d#d", false,
                        new String[]{"zr0.d", "ur0.d", "tt0.d"}, "d", "Z", NONE,
                        DexKitAnchors.TT0_D));

        // UI-08 云村自动播放：wr0.r -> bs0.r（VT0_R anchor 在 bs0.r），j()Z 不变。
        out.put("UI-08/vt0.r#j",
                MountSpec.method("UI-08", "vt0.r#j", false,
                        new String[]{"bs0.r", "wr0.r", "vt0.r"}, "j", "Z", NONE,
                        DexKitAnchors.VT0_R));

        // UI-08 自动播放总开关：mt0.j -> rt0.j（LV0_J anchor 在 rt0.j），e -> c。
        out.put("UI-08/lv0.j#e",
                MountSpec.method("UI-08", "lv0.j#e", false,
                        new String[]{"rt0.j", "mt0.j", "lv0.j"}, "c",
                        "Lcom/netease/cloudmusic/module/strategy/meta/AutoPlayConfig;",
                        NONE, DexKitAnchors.LV0_J));

        // UI-09 头像挂件：utils.jn -> utils.vn（JN anchor 在 vn），L()Z 不变。
        out.put("UI-09/jn#L",
                MountSpec.method("UI-09", "jn#L", true,
                        new String[]{"com.netease.cloudmusic.utils.vn",
                                "com.netease.cloudmusic.utils.jn"},
                        "L", "Z", NONE, DexKitAnchors.JN));

        // UI-14 歌词视频提示弹窗：kc -> jc（类内 kc(LyricVideoTipsDialog,View)V 需靠签名排除）。
        out.put("UI-14/LyricVideoTipsDialog#oc",
                MountSpec.method("UI-14", "LyricVideoTipsDialog#oc", false,
                        "com.netease.cloudmusic.fragment.LyricVideoTipsDialog", "jc", "V",
                        new String[]{
                                "Landroidx/fragment/app/FragmentActivity;",
                                "Lcom/netease/cloudmusic/meta/LyricDialogData;"
                        }));

        // EN-01 背景注入入口：nb2 包 -> yb2 包（方法名/签名不变，保留 pc2 候选）。
        out.put("EN-01/BgInfoParser#B",
                MountSpec.method("EN-01", "BgInfoParser#B", false,
                        new String[]{"yb2.e$a", "nb2.e$a", "pc2.e$a"},
                        "B", "Lkotlin/Pair;",
                        new String[]{"Lorg/json/JSONObject;"}));
        out.put("EN-01/BgStore#g",
                MountSpec.method("EN-01", "BgStore#g", false,
                        new String[]{"yb2.f", "nb2.f", "pc2.f"},
                        "g", "Ljava/lang/String;", new String[]{"J"}));
        out.put("EN-01/BgStore#s",
                MountSpec.method("EN-01", "BgStore#s", false,
                        new String[]{"yb2.f", "nb2.f", "pc2.f"},
                        "s", "Z", new String[]{"J", "Ljava/lang/String;"}));

        // EN-01 分支方法：kc()Z -> jc()Z（同类另一零参 boolean 是 Tb()Z，opcode 不同）。
        out.put("EN-01/MyMusicProxyFragment#kc",
                MountSpec.method("EN-01", "MyMusicProxyFragment#kc", true,
                        "com.netease.cloudmusic.fragment.MyMusicProxyFragment",
                        "jc", "Z", NONE));

        // EN-01 只读探针：utils.sg -> utils.eh（s(JSONObject)Z 的 callee 不变）。
        out.put("EN-01/sg#s",
                MountSpec.method("EN-01", "sg#s", false,
                        new String[]{"com.netease.cloudmusic.utils.eh",
                                "com.netease.cloudmusic.utils.sg"},
                        "s", "Z", new String[]{"Lorg/json/JSONObject;"}));

        return out;
    }

    private static TargetProfile build() {
        List<MountSpec> all = new ArrayList<>(Profile9615.PROFILE.mounts);
        // PV-05：9.6.20 把原 d/e 两条解析路径合并进 c()Z，profile.remove 同步删除。
        all.removeIf(spec -> LOG_MONITOR_D.equals(spec.id)
                || LOG_MONITOR_E.equals(spec.id));
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
