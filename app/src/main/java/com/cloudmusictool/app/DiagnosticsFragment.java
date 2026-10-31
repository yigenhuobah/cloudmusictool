package com.cloudmusictool.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 诊断页：逐功能展示目标进程回传的 registered / hit / skip。
 *
 * 进入页面与 onResume 时刷新，不自动轮询；无数据显示 “—”，blocked 显示原因。
 * hook_status 读取与 JSON 解析全部在后台单线程完成，只把渲染回主线程：
 * 首次读取会同步加载磁盘，不能占用 UI 线程。
 */
public class DiagnosticsFragment extends PreferenceFragmentCompat {

    private ExecutorService mStatusExecutor;
    /** 丢弃过期的后台读取结果，避免旧快照覆盖新一次刷新。 */
    private final AtomicInteger mRefreshGeneration = new AtomicInteger();
    private final AtomicInteger mCopyGeneration = new AtomicInteger();

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        if (mStatusExecutor == null) {
            mStatusExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "CloudMusicTool-Diagnostics");
                thread.setDaemon(true);
                return thread;
            });
        }
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onDestroy() {
        mRefreshGeneration.incrementAndGet();
        mCopyGeneration.incrementAndGet();
        if (mStatusExecutor != null) {
            mStatusExecutor.shutdownNow();
            mStatusExecutor = null;
        }
        super.onDestroy();
    }

    /** 重新读取 StatusProvider 落盘的状态并在主线程重建页面。 */
    void refresh() {
        if (getContext() == null) {
            return;
        }
        Context context = requireContext();
        Context appContext = context.getApplicationContext();
        final Context readContext = appContext != null ? appContext : context;
        loadAsync(mStatusExecutor, ConfigStore::postToMain,
                () -> readStatus(readContext),
                root -> {
                    if (isAdded()) {
                        render(root);
                    }
                },
                mRefreshGeneration);
    }

    private void render(JSONObject root) {
        Context context = requireContext();
        JSONObject features = root == null ? null : root.optJSONObject("features");

        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);

        Preference header = new Preference(context);
        header.setKey("diag_header");
        header.setTitle(R.string.diag_source_title);
        header.setSummary(sourceSummary(root));
        header.setSelectable(false);
        header.setPersistent(false);
        screen.addPreference(header);

        Feature.Group lastGroup = null;
        PreferenceCategory category = null;
        for (Feature f : Feature.values()) {
            if (f.group != lastGroup) {
                category = new PreferenceCategory(context);
                category.setTitle(groupTitle(f.group));
                category.setPersistent(false);
                screen.addPreference(category);
                lastGroup = f.group;
            }
            Preference row = new Preference(context);
            row.setKey("diag_" + f.key);
            row.setTitle(f.id + " " + getString(f.titleRes));
            row.setSummary(featureSummary(f, features));
            row.setSelectable(false);
            row.setPersistent(false);
            category.addPreference(row);
        }

        PreferenceCategory actions = new PreferenceCategory(context);
        actions.setTitle(R.string.diag_actions_title);
        actions.setPersistent(false);
        screen.addPreference(actions);

        Preference refresh = new Preference(context);
        refresh.setKey("diag_refresh");
        refresh.setTitle(R.string.diag_refresh_title);
        refresh.setSummary(R.string.diag_refresh_summary);
        refresh.setPersistent(false);
        refresh.setOnPreferenceClickListener(pref -> {
            refresh();
            Toast.makeText(requireContext(), R.string.diag_refreshed,
                    Toast.LENGTH_SHORT).show();
            return true;
        });
        actions.addPreference(refresh);

        Preference copy = new Preference(context);
        copy.setKey("diag_copy");
        copy.setTitle(R.string.diag_copy_title);
        copy.setSummary(R.string.diag_copy_summary);
        copy.setPersistent(false);
        copy.setOnPreferenceClickListener(pref -> {
            copyDiagnostics();
            return true;
        });
        actions.addPreference(copy);

        setPreferenceScreen(screen);
    }

    private String sourceSummary(JSONObject root) {
        if (root == null) {
            return getString(R.string.diag_source_empty);
        }
        long updatedAt = root.optLong("updatedAt", 0L);
        boolean fresh = ReportProtocol.isFresh(updatedAt,
                System.currentTimeMillis(), ReportProtocol.DEFAULT_TTL_MS);
        String time = updatedAt > 0
                ? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        .format(new Date(updatedAt))
                : getString(R.string.diag_no_data);
        String summary = getString(R.string.diag_source_ok, time);
        if (!fresh) {
            // TTL 外或没有 report_time：数据未知，不展示为健康快照。
            summary = summary + "\n" + getString(R.string.diag_stale);
        }
        if (root.optBoolean("truncated", false)) {
            summary = summary + "\n" + getString(R.string.diag_truncated);
        }
        // ABTest 不是 Feature，不占 features 结构；有记录时并入数据来源摘要展示。
        JSONObject abtest = root.optJSONObject("abtest");
        if (abtest != null) {
            String reason = abtest.optString("lastFailure", "");
            summary = summary + "\nABTest 已装 " + abtest.optInt("installed", 0)
                    + " · 失败 " + abtest.optInt("failed", 0)
                    + (reason.isEmpty() ? "" : " · " + reason);
        }
        return summary;
    }

    private String featureSummary(Feature f, JSONObject features) {
        if (f.blocked()) {
            return getString(R.string.diag_blocked, f.blockedReason);
        }
        JSONObject o = features == null ? null : features.optJSONObject(f.name());
        if (o == null || !o.optBoolean("enabled", false)) {
            return getString(R.string.diag_no_data);
        }
        List<String> parts = new ArrayList<>();
        int installed = o.optInt("installed", 0);
        parts.add(getString(installed > 0
                ? R.string.diag_registered
                : R.string.diag_registered_zero));
        if (o.optBoolean("degraded", false)) {
            parts.add(getString(R.string.diag_degraded));
        }
        if (o.optBoolean("unavailable", false)) {
            parts.add(getString(R.string.diag_unavailable));
        }
        parts.add(getString(R.string.diag_hit, o.optInt("hitCount", 0)));
        String skip = o.optString("lastFailure", "");
        parts.add(getString(R.string.diag_skip,
                skip.isEmpty() ? getString(R.string.diag_skip_none) : skip));
        return android.text.TextUtils.join(" · ", parts);
    }

    private int groupTitle(Feature.Group group) {
        switch (group) {
            case AD:
                return R.string.group_ad;
            case UI:
                return R.string.group_ui;
            case PV:
                return R.string.group_pv;
            case EN:
                return R.string.group_en;
            case EX:
            default:
                return R.string.group_ex;
        }
    }

    private void copyDiagnostics() {
        if (getContext() == null) {
            return;
        }
        Context context = requireContext();
        Context appContext = context.getApplicationContext();
        final Context readContext = appContext != null ? appContext : context;
        // 复制也要先在后台读完再回主线程拼文本（getString 只能在主线程）。
        loadAsync(mStatusExecutor, ConfigStore::postToMain,
                () -> readStatus(readContext),
                root -> {
                    if (isAdded()) {
                        copyDiagnosticsToClipboard(buildDiagnosticsText(root));
                    }
                },
                mCopyGeneration);
    }

    private String buildDiagnosticsText(JSONObject root) {
        JSONObject features = root == null ? null : root.optJSONObject("features");
        StringBuilder text = new StringBuilder();
        text.append(getString(R.string.diag_source_title)).append(": ")
                .append(sourceSummary(root)).append('\n');
        for (Feature f : Feature.values()) {
            text.append(f.id).append(' ').append(getString(f.titleRes))
                    .append(' ').append(featureSummary(f, features)).append('\n');
        }
        return text.toString();
    }

    private void copyDiagnosticsToClipboard(String text) {
        try {
            ClipboardManager clipboard = (ClipboardManager) requireContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    getString(R.string.page_diagnostics), text));
            Toast.makeText(requireContext(), R.string.diag_copied,
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(requireContext(), R.string.diag_copy_failed,
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** 只能在后台线程调用：首次 getSharedPreferences 会同步加载磁盘。 */
    private static JSONObject readStatus(Context context) {
        String json = StatusProvider.readState(context);
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(json);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 后台读取 → 主线程渲染的两段式加载。
     *
     * <p>读取不会发生在调用线程；渲染只经 mainSink 投递，且代次不是最新时丢弃，
     * 避免旧快照覆盖新一次刷新。抽成静态方法便于纯 JVM 单测固定这两条语义。
     *
     * @return false 表示执行器不可用/已关闭，本次加载没有提交。
     */
    static <T> boolean loadAsync(Executor background, Consumer<Runnable> mainSink,
                                 Supplier<T> reader, Consumer<T> renderer,
                                 AtomicInteger generation) {
        if (background == null || mainSink == null || reader == null
                || renderer == null || generation == null) {
            return false;
        }
        final int token = generation.incrementAndGet();
        try {
            background.execute(() -> {
                final T value = reader.get();
                mainSink.accept(() -> {
                    if (generation.get() == token) {
                        renderer.accept(value);
                    }
                });
            });
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
