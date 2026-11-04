package com.cloudmusictool.app.anchor;

import java.util.Collections;
import java.util.List;

/**
 * 已适配版本的唯一来源：anchors.json 为唯一数据源，运行时 Profile 由等价测试锁定。
 *
 * <p>versionCode ↔ versionName ↔ Profile 全部来自 anchors.json。
 * VersionGate（支持判定/日志）、ProfileRegistry（版本 -> 运行时 Profile）、
 * check-mounts.sh（APK versionCode -> 锚点表）与 README 一致性测试都从这里
 * 派生，代码里不再写死 9006008/9006013/9006015 或版本名清单。
 *
 * <p>运行时 Profile9608/9613/9615 作为回滚点保留，由
 * ProfileAnchorEquivalenceTest 锁定与 anchors.json 派生表逐项一致。
 *
 * <p>anchors.json 缺失或损坏时返回空列表：VersionGate 按未适配 fail-open。
 */
public final class SupportedVersions {

    private static volatile AnchorTable sTable;
    private static volatile boolean sLoaded;

    private SupportedVersions() {
    }

    /** 懒加载 anchors.json；失败缓存 null，避免每次 hook 都重试 IO。 */
    public static AnchorTable table() {
        if (!sLoaded) {
            synchronized (SupportedVersions.class) {
                if (!sLoaded) {
                    sTable = AnchorTable.load(SupportedVersions.class.getClassLoader());
                    sLoaded = true;
                }
            }
        }
        return sTable;
    }

    /** 全部已适配版本，按 anchors.json 顺序；表不可用时为空。 */
    public static List<AnchorTable.Version> all() {
        AnchorTable table = table();
        return table == null ? Collections.<AnchorTable.Version>emptyList()
                : table.versions();
    }

    public static AnchorTable.Version byCode(long versionCode) {
        AnchorTable table = table();
        return table == null ? null : table.versionByCode(versionCode);
    }

    public static AnchorTable.Version byName(String versionName) {
        if (versionName == null) {
            return null;
        }
        AnchorTable table = table();
        return table == null ? null : table.versionByName(versionName);
    }

    public static boolean isSupported(String versionName) {
        return byName(versionName) != null;
    }

    /** 展示/日志用版本名清单，如 {@code "9.6.08 / 9.6.13 / 9.6.15"}。 */
    public static String namesLabel() {
        return join(false);
    }

    /** 展示/日志用 versionCode 清单，如 {@code "9006008 / 9006013 / 9006015"}。 */
    public static String codesLabel() {
        return join(true);
    }

    /** 与 {@link #namesLabel()} 同值；供 About 页等原有调用点使用。 */
    public static String label() {
        return namesLabel();
    }

    private static String join(boolean codes) {
        StringBuilder out = new StringBuilder();
        for (AnchorTable.Version version : all()) {
            if (out.length() > 0) {
                out.append(" / ");
            }
            out.append(codes ? String.valueOf(version.versionCode) : version.versionName);
        }
        return out.toString();
    }
}
