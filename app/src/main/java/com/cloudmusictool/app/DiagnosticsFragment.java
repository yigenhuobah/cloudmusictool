package com.cloudmusictool.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import org.json.JSONArray;
import org.json.JSONObject;

import io.github.libxposed.service.XposedService;

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

    /** 导出诊断包文本里「最近命中」段最多列出的观测条数。 */
    private static final int MAX_EXPORT_HITS = 20;

    private ExecutorService mStatusExecutor;
    /** 丢弃过期的后台读取结果，避免旧快照覆盖新一次刷新。 */
    private final AtomicInteger mRefreshGeneration = new AtomicInteger();
    private final AtomicInteger mCopyGeneration = new AtomicInteger();
    private final AtomicInteger mExportGeneration = new AtomicInteger();

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
        mExportGeneration.incrementAndGet();
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

        // 最近崩溃（内置诊断 crashes 通道，[0] 为最新）：无崩溃时不显示。
        // strings.xml 不在本簇清单内，卡片文案沿用本文件 abtest 段的字面量先例。
        JSONArray crashes = root == null ? null : root.optJSONArray("crashes");
        if (crashes != null && crashes.length() > 0) {
            PreferenceCategory crashCategory = new PreferenceCategory(context);
            crashCategory.setTitle("最近崩溃");
            crashCategory.setPersistent(false);
            screen.addPreference(crashCategory);
            for (int i = 0; i < crashes.length(); i++) {
                Preference crash = new Preference(context);
                crash.setKey("diag_crash_" + i);
                crash.setTitle(i == 0 ? "最新" : "#" + (i + 1));
                crash.setSummary(crashes.optString(i, ""));
                crash.setSelectable(false);
                crash.setPersistent(false);
                crashCategory.addPreference(crash);
            }
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

        Preference export = new Preference(context);
        export.setKey("diag_export");
        export.setTitle("导出诊断包");
        export.setSummary("以纯文本分享：模块版本/系统/框架 API/目标版本/功能状态/最近命中/最近崩溃");
        export.setPersistent(false);
        export.setOnPreferenceClickListener(pref -> {
            exportDiagnosticsPackage();
            return true;
        });
        actions.addPreference(export);

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
        return readJson(StatusProvider.readState(context));
    }

    private static JSONObject readJson(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(json);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------- 导出诊断包

    /** 导出诊断包需要的全部输入；字段在后台线程填充，主线程只消费。 */
    private static final class ExportPayload {
        JSONObject state;
        JSONObject experiments;
        String moduleVersion;
        String systemVersion;
        String frameworkApi;
        String targetVersion;
    }

    /** 「导出诊断包」入口：后台读取全部输入，回主线程拼文本并经系统分享。 */
    private void exportDiagnosticsPackage() {
        if (getContext() == null) {
            return;
        }
        Context context = requireContext();
        Context appContext = context.getApplicationContext();
        final Context readContext = appContext != null ? appContext : context;
        loadAsync(mStatusExecutor, ConfigStore::postToMain,
                () -> readExportPayload(readContext),
                payload -> {
                    if (isAdded()) {
                        shareDiagnosticsPackage(payload);
                    }
                },
                mExportGeneration);
    }

    /** 只能在后台线程调用：binder/SharedPreferences 读取都不占用主线程。 */
    private static ExportPayload readExportPayload(Context context) {
        ExportPayload payload = new ExportPayload();
        payload.state = readStatus(context);
        payload.experiments = readJson(StatusProvider.readExperiments(context));
        payload.moduleVersion = packageVersion(context, context.getPackageName());
        payload.targetVersion = packageVersion(context, Config.TARGET_PACKAGE);
        payload.systemVersion = "Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")";
        XposedService service = ServiceBridge.get();
        String api;
        try {
            api = service == null ? null : String.valueOf(service.getApiVersion());
        } catch (Throwable t) {
            api = null;
        }
        payload.frameworkApi = api;
        return payload;
    }

    private static String packageVersion(Context context, String packageName) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
            return info.versionName == null ? "?" : info.versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * 拼装导出文本并经 ACTION_SEND text/plain 系统分享：不写文件、不依赖 FileProvider。
     * 拼装（{@link #buildExportPackage}）放在 try 外也安全（纯字符串操作），
     * 分享目标缺失时只提示失败。
     */
    private void shareDiagnosticsPackage(ExportPayload payload) {
        String text = buildExportPackage(payload.moduleVersion, payload.systemVersion,
                payload.frameworkApi, payload.targetVersion,
                buildDiagnosticsText(payload.state), payload.state, payload.experiments);
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(intent, getString(R.string.page_diagnostics)));
        } catch (Throwable t) {
            Toast.makeText(requireContext(), "导出诊断包失败", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 纯 JVM：拼装导出诊断包文本（模块版本/系统版本/框架 API/目标版本/各功能状态/
     * 最近命中/最近崩溃，段顺序固定），便于单测锁定关键段与顺序。
     *
     * @param featureSection 各功能状态段（buildDiagnosticsText，含数据来源摘要；可空）
     * @param state          mounts 通道快照（可空，无崩溃时不输出最近崩溃段）
     * @param experiments    experiments 通道快照（可空，无观测时不输出最近命中段）
     */
    static String buildExportPackage(String moduleVersion, String systemVersion,
            String frameworkApi, String targetVersion, String featureSection,
            JSONObject state, JSONObject experiments) {
        StringBuilder text = new StringBuilder();
        text.append("模块版本: ").append(orDash(moduleVersion)).append('\n');
        text.append("系统版本: ").append(orDash(systemVersion)).append('\n');
        text.append("框架 API: ").append(orDash(frameworkApi)).append('\n');
        text.append("目标版本: ").append(orDash(targetVersion)).append('\n');
        if (featureSection != null) {
            text.append(featureSection);
        }
        text.append(recentHitsSection(experiments));
        text.append(crashesSection(state));
        return text.toString();
    }

    private static String orDash(String s) {
        return s == null || s.isEmpty() ? "—" : s;
    }

    /** 最近命中段：experiments 观测按 lastAt 降序最多 {@link #MAX_EXPORT_HITS} 条；无观测返回空串。 */
    static String recentHitsSection(JSONObject experiments) {
        JSONArray items = experiments == null ? null : experiments.optJSONArray("items");
        if (items == null || items.length() == 0) {
            return "";
        }
        List<JSONObject> hits = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && !item.optString("key", "").isEmpty()) {
                hits.add(item);
            }
        }
        if (hits.isEmpty()) {
            return "";
        }
        hits.sort((a, b) -> Long.compare(b.optLong("lastAt", 0L), a.optLong("lastAt", 0L)));
        StringBuilder section = new StringBuilder("最近命中:\n");
        for (int i = 0; i < hits.size() && i < MAX_EXPORT_HITS; i++) {
            JSONObject item = hits.get(i);
            section.append("  ").append(item.optString("key", ""))
                    .append(' ').append(orDash(item.optString("group", "")))
                    .append(" ×").append(item.optInt("count", 0)).append('\n');
        }
        return section.toString();
    }

    /** 最近崩溃段（[0] 为最新）；无崩溃返回空串（与诊断页卡片一致的「无崩溃不显示」）。 */
    static String crashesSection(JSONObject state) {
        JSONArray crashes = state == null ? null : state.optJSONArray("crashes");
        if (crashes == null || crashes.length() == 0) {
            return "";
        }
        StringBuilder section = new StringBuilder("最近崩溃:\n");
        for (int i = 0; i < crashes.length(); i++) {
            section.append("  ").append(crashes.optString(i, "")).append('\n');
        }
        return section.toString();
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
