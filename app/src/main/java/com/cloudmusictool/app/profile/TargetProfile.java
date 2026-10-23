package com.cloudmusictool.app.profile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一个宿主版本对应的挂载点集合。 */
public final class TargetProfile {

    public final long versionCode;
    public final String versionName;
    public final List<MountSpec> mounts;

    public TargetProfile(long versionCode, String versionName, List<MountSpec> mounts) {
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.mounts = Collections.unmodifiableList(new ArrayList<>(mounts));
    }

    public List<MountSpec> forFeature(String featureId) {
        if (featureId == null) {
            return Collections.emptyList();
        }
        List<MountSpec> out = new ArrayList<>();
        for (MountSpec spec : mounts) {
            if (featureId.equals(spec.featureId)) {
                out.add(spec);
            }
        }
        return out;
    }

    public int criticalCount(String featureId) {
        int n = 0;
        for (MountSpec spec : mounts) {
            if (featureId.equals(spec.featureId) && spec.critical) {
                n++;
            }
        }
        return n;
    }

    public MountSpec findClassSpec(String className) {
        if (className == null) {
            return null;
        }
        for (MountSpec spec : mounts) {
            if (spec.classCandidates == null) {
                continue;
            }
            for (String candidate : spec.classCandidates) {
                if (className.equals(candidate)) {
                    return spec;
                }
            }
        }
        return null;
    }

    public MountSpec findMethodSpec(String className, String methodName) {
        if (className == null || methodName == null) {
            return null;
        }
        for (MountSpec spec : mounts) {
            if (!methodName.equals(spec.methodName) || spec.classCandidates == null) {
                continue;
            }
            for (String candidate : spec.classCandidates) {
                if (className.equals(candidate)) {
                    return spec;
                }
            }
        }
        return null;
    }

    /**
     * 按「hook 请求的旧混淆方法名」查找坐标（纯 JVM 可测）。
     *
     * hook 文件里写死的是 9.6.08 的方法名（如 LoadingAdManager#E）；新版本的
     * Profile 覆盖里 methodName 已换成实际名（如 H），但 logicalName 仍保留旧
     * 逻辑名。此查找匹配：classCandidates 包含 className 且 logicalName 以
     * {@code "#"+requestedMethodName} 结尾的 spec。找不到返回 null，由调用方
     * 走原有按名解析路径。
     */
    public MountSpec findByLogicalName(String className, String requestedMethodName) {
        if (className == null || requestedMethodName == null) {
            return null;
        }
        String suffix = "#" + requestedMethodName;
        for (MountSpec spec : mounts) {
            if (spec.logicalName == null || !spec.logicalName.endsWith(suffix)
                    || spec.classCandidates == null) {
                continue;
            }
            for (String candidate : spec.classCandidates) {
                if (className.equals(candidate)) {
                    return spec;
                }
            }
        }
        return null;
    }
}
