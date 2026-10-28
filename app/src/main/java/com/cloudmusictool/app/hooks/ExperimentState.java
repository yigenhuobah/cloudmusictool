package com.cloudmusictool.app.hooks;

import android.util.Log;

import com.cloudmusictool.app.ReportProtocol;
import com.cloudmusictool.app.StatusProvider;
import com.cloudmusictool.app.TargetProcessPublisher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 目标进程内的 ABTest 命中观测。
 *
 * 记录 key -> {group, source, count, lastAt}，最多跟踪 {@link #MAX_TRACKED_KEYS} 个 key，
 * 序列化时按 count 取前 {@link #MAX_KEYS} 个，并保证 JSON 不超过 64KB。
 *
 * 写入策略波次2起与 HookState 统一：「领先 + 尾沿」节流——窗口首事件立即发送，
 * 窗口内后续事件合并，窗口关闭时必发一次尾沿（避免丢掉最后一批）；
 * 原实现为纯尾沿（首事件也要等 2 秒）。安装结束时由 CloudMusicToolModule 调 flush()
 * 强制发送一次。调度与发送统一走共享回传管线 {@link TargetProcessPublisher}；
 * remote prefs 在宿主进程只读，因此通过模块 App 的 StatusProvider 回写（type=experiments）。
 */
public final class ExperimentState {

    private static final String TAG = "CloudMusicTool";
    private static final int MAX_TRACKED_KEYS = 500;
    private static final int MAX_KEYS = 200;
    private static final int MAX_JSON_CHARS = 64 * 1024;

    private static final Map<String, Item> sItems = new ConcurrentHashMap<>();

    // 超长条目只记一次日志，避免异常数据刷屏。
    private static final AtomicBoolean sOversizeWarned = new AtomicBoolean();

    // 「领先 + 尾沿」节流器：与 HookState 同语义（每通道一个实例，窗口互不干扰）。
    // 生产用单调时钟（nanoTime），墙钟回拨/跳变不会让节流窗口判定错乱。
    private static final TargetProcessPublisher.PublishThrottle sThrottle =
            new TargetProcessPublisher.PublishThrottle(
                    TargetProcessPublisher.PublishThrottle.MONOTONIC_CLOCK);

    private static volatile String sModulePackage;

    /** experiments 通道描述：内容与告警文案固定，调度与发送由共享管线执行。 */
    private static final TargetProcessPublisher.Channel sChannel = new TargetProcessPublisher.Channel() {
        @Override
        public String modulePackage() {
            return sModulePackage;
        }

        @Override
        public String type() {
            return StatusProvider.TYPE_EXPERIMENTS;
        }

        @Override
        public String buildJson() {
            // 与原 doPublish 的空数据短路一致：无记录不发送，避免空快照覆盖。
            return sItems.isEmpty() ? null : ExperimentState.buildJson();
        }

        @Override
        public String contextUnavailableWarning() {
            return "experiments not published: target Context unavailable";
        }

        @Override
        public String publishFailureWarning(Throwable t) {
            return "experiments publish failed: " + t.getMessage();
        }
    };

    private ExperimentState() {
    }

    private static final class Item {
        volatile String group;
        volatile String source;
        final AtomicInteger count = new AtomicInteger();
        volatile long lastAt;

        synchronized void update(String g, String s) {
            if (g != null && !g.isEmpty()) {
                group = g;
            }
            if (s != null && !s.isEmpty()) {
                source = s;
            }
            count.incrementAndGet();
            lastAt = System.currentTimeMillis();
        }
    }

    /** 由 CloudMusicToolModule.onModuleLoaded 调用。 */
    public static void init(String modulePackage) {
        sModulePackage = modulePackage;
    }

    public static void record(String key, String group, String source) {
        if (key == null || key.isEmpty()) {
            return;
        }
        try {
            Item item = sItems.get(key);
            if (item == null) {
                if (sItems.size() >= MAX_TRACKED_KEYS) {
                    return;
                }
                Item created = new Item();
                Item prev = sItems.putIfAbsent(key, created);
                item = prev != null ? prev : created;
            }
            item.update(group, source);
            schedulePublish();
        } catch (Throwable t) {
            // 观测失败绝不影响宿主。
        }
    }

    /** 强制发送一次当前快照（不受 2 秒节流限制）；无数据时不发送，避免空快照覆盖。 */
    public static void flush() {
        if (sModulePackage == null || sModulePackage.isEmpty() || sItems.isEmpty()) {
            return;
        }
        try {
            TargetProcessPublisher.flush(sChannel);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 节流提交：「领先 + 尾沿」——窗口首事件立即发送（原为纯尾沿：首事件也要等 2 秒），
     * 窗口内后续事件合并，窗口关闭时必发一次尾沿；决策与发送在共享管线执行。
     */
    private static void schedulePublish() {
        if (sModulePackage == null || sModulePackage.isEmpty()) {
            return;
        }
        TargetProcessPublisher.publish(sThrottle, sChannel);
    }

    /**
     * 单条记录是否可写入 payload：key/group/source 任一超过
     * {@link ReportProtocol#MAX_VALUE_CHARS} 时整条跳过。
     *
     * StatusProvider 对超长字符串是「整个 payload 拒绝」而非截断，若照常写入，
     * 一条异常 key 就会让本次 experiments 回传全部丢失；跳过该条可保留其它条目。
     * 包私有静态便于纯 JVM 契约测试。
     */
    static boolean isEntryWithinProtocolLimits(String key, String group, String source) {
        return withinValueLimit(key) && withinValueLimit(group) && withinValueLimit(source);
    }

    private static boolean withinValueLimit(String value) {
        return value == null || value.length() <= ReportProtocol.MAX_VALUE_CHARS;
    }

    private static String buildJson() {
        try {
            List<Map.Entry<String, Item>> entries = new ArrayList<>(sItems.entrySet());
            Collections.sort(entries, new Comparator<Map.Entry<String, Item>>() {
                @Override
                public int compare(Map.Entry<String, Item> a, Map.Entry<String, Item> b) {
                    return Integer.compare(b.getValue().count.get(), a.getValue().count.get());
                }
            });
            int limit = Math.min(entries.size(), MAX_KEYS);
            JSONArray items = new JSONArray();
            for (int i = 0; i < limit; i++) {
                Map.Entry<String, Item> e = entries.get(i);
                Item item = e.getValue();
                String key = e.getKey();
                String group = item.group == null ? "c" : item.group;
                String source = item.source == null ? "unknown" : item.source;
                if (!isEntryWithinProtocolLimits(key, group, source)) {
                    // 超长条目整条跳过：协议按单值 256 字符整体校验，单条超长会拖垮全部条目。
                    if (sOversizeWarned.compareAndSet(false, true)) {
                        Log.w(TAG, "experiments entry skipped: key/group/source exceeds "
                                + ReportProtocol.MAX_VALUE_CHARS + " chars");
                    }
                    continue;
                }
                JSONObject o = new JSONObject();
                o.put("key", key);
                o.put("group", group);
                o.put("source", source);
                o.put("count", item.count.get());
                o.put("lastAt", item.lastAt);
                items.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("updatedAt", System.currentTimeMillis());
            root.put("items", items);
            String json = root.toString();
            // 超限按 count 截断：entries 已按 count 降序，从尾部丢弃。
            while (json.length() > MAX_JSON_CHARS && items.length() > 0) {
                items.remove(items.length() - 1);
                root.put("items", items);
                json = root.toString();
            }
            return json;
        } catch (Throwable t) {
            return null;
        }
    }
}
