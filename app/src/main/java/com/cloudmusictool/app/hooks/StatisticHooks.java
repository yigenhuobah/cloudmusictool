package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedModule;

/**
 * PV-05 统计数据上报抑制：两类挂载点语义不同，不要把本功能当成纯只读观测。
 *
 * 1. StatisticImpl#log / #logJSON（只观测，不拦截）：限流记录统计 key 后放行宿主；
 * 2. LogMonitorConfigManager#b/c/d/e（拦截改写）：constBool(false) 强制关闭监控开关，
 *    直接改写宿主返回值。
 */
public final class StatisticHooks {

    private static final AtomicInteger sLogCount = new AtomicInteger(0);
    private static final AtomicInteger sLogJsonCount = new AtomicInteger(0);
    private static final AtomicInteger sErrorCount = new AtomicInteger(0);

    private StatisticHooks() {
    }

    private static void logErrorLimited(XposedModule module, String msg, Throwable t) {
        if (sErrorCount.get() < 20 && sErrorCount.incrementAndGet() <= 20) {
            HookKit.error(module, msg, t);
        }
    }

    public static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.STATS_OBSERVE;

        // 1. StatisticImpl.log / logJSON
        try {
            Class<?> statCls = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.core.statistic.StatisticImpl");
            if (statCls != null) {
                Method mLog = HookKit.findMethodQuiet(module, f, "StatisticImpl#log",
                        statCls, "log", String.class, Object[].class);
                if (mLog == null) {
                    mLog = HookKit.findByNameAndArity(module, f, "StatisticImpl#log",
                            statCls, "log", 2);
                }
                if (mLog != null) {
                    HookKit.install(module, f, "StatisticImpl#log", mLog, chain -> {
                        Object res = chain.proceed();
                        try {
                            if (sLogCount.get() < 100) {
                                int count = sLogCount.incrementAndGet();
                                if (count <= 100) {
                                    java.util.List<Object> args = chain.getArgs();
                                    String key = (args != null && !args.isEmpty() && args.get(0) != null) ? args.get(0).toString() : "unknown";
                                    HookKit.info(module, f.id + " observe log: <" + key + "> (" + count + "/100)");
                                }
                            }
                        } catch (Throwable t) {
                            logErrorLimited(module, f.id + " observe error StatisticImpl#log", t);
                        }
                        return res;
                    });
                }

                Method mLogJson = HookKit.findByNameAndArity(module, f, "StatisticImpl#logJSON",
                        statCls, "logJSON", 2);
                if (mLogJson == null) {
                    mLogJson = HookKit.findByNameQuiet(module, f, "StatisticImpl#logJSON",
                            statCls, "logJSON");
                }
                if (mLogJson != null) {
                    HookKit.install(module, f, "StatisticImpl#logJSON", mLogJson, chain -> {
                        Object res = chain.proceed();
                        try {
                            if (sLogJsonCount.get() < 100) {
                                int count = sLogJsonCount.incrementAndGet();
                                if (count <= 100) {
                                    java.util.List<Object> args = chain.getArgs();
                                    String key = (args != null && !args.isEmpty() && args.get(0) != null) ? args.get(0).toString() : "unknown";
                                    HookKit.info(module, f.id + " observe logJSON: <" + key + "> (" + count + "/100)");
                                }
                            }
                        } catch (Throwable t) {
                            logErrorLimited(module, f.id + " observe error StatisticImpl#logJSON", t);
                        }
                        return res;
                    });
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " StatisticImpl hook failed", t);
        }

        // 2. LogMonitorConfigManager: b, c, d, e -> false
        try {
            Class<?> monitorCls = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.statistic.monitor.LogMonitorConfigManager");
            if (monitorCls != null) {
                String[] methods = {"b", "c", "d", "e"};
                for (String mName : methods) {
                    Method m = HookKit.findByNameQuiet(module, f,
                            "LogMonitorConfigManager#" + mName, monitorCls, mName);
                    if (m != null) {
                        HookKit.install(module, f, "LogMonitorConfigManager#" + mName, m,
                                HookKit.constBool(module, f, "LogMonitorConfigManager." + mName + " -> false", false));
                    }
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " LogMonitorConfigManager hook failed", t);
        }
    }
}
