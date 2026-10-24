package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Config;

/**
 * 进程过滤器。
 * 跳过 :patch、:safemode、:heap_analysis、:leakcanary 等辅助进程。
 * :play 进程只安装音频贴片（AD-09）相关 hook，其余功能仅主进程。
 */
public final class ProcessFilter {

    private ProcessFilter() {
    }

    /** 音频播放进程：只装 AD-09。 */
    public static boolean isPlayProcess(String processName) {
        String name = resolve(processName);
        return name != null && name.endsWith(":play");
    }

    public static boolean shouldHook(String processName) {
        String name = resolve(processName);
        if (name == null) {
            return false;
        }
        if (name.endsWith(":patch")
                || name.endsWith(":safemode")
                || name.endsWith(":heap_analysis")
                || name.endsWith(":leakcanary")
                || name.endsWith(":play")) {
            return false;
        }
        return Config.TARGET_PACKAGE.equals(name);
    }

    /** mProcessName 为空时回退到 Application.getProcessName()（API 28+）。 */
    static String resolve(String processName) {
        if (processName != null && !processName.isEmpty()) {
            return processName;
        }
        try {
            return android.app.Application.getProcessName();
        } catch (Throwable t) {
            return null;
        }
    }
}
