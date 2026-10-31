package com.cloudmusictool.app;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.XposedService;

/**
 * 主设置页：四态状态卡、异常横幅、功能开关、二级页入口与页脚。
 *
 * 状态卡的 binder 查询全部放后台单线程；结果回主线程前校验 Fragment 仍存在、
 * 查询未被更新的一次覆盖、service 未被换绑。
 */
public class SettingsFragment extends PreferenceFragmentCompat {

    private static final String TARGET = Config.TARGET_PACKAGE;

    /** 与 res/xml/prefs.xml 的 android:key 保持一致。 */
    private static final String KEY_HIDE_ICON = "app_hide_icon";

    private ConfigStore mStore;
    private ExecutorService mStatusExecutor;
    private final AtomicInteger mStatusGeneration = new AtomicInteger();
    /** 每个 key 的写回令牌与最近确认值；与首页定制页共用同一状态机语义。 */
    private final HomeCustomizeFragment.WriteGate<Boolean> mWriteGate =
            new HomeCustomizeFragment.WriteGate<>();

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.prefs, rootKey);
        // 构造不做 I/O；remote prefs 在 refresh() 的后台任务里解析。
        mStore = new ConfigStore();
        mStatusExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "CloudMusicTool-Status");
            thread.setDaemon(true);
            return thread;
        });
        bindFeatures();
        bindLauncherIcon();
        bindPages();
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onDestroy() {
        mStatusGeneration.incrementAndGet();
        mWriteGate.clear();
        if (mStatusExecutor != null) {
            mStatusExecutor.shutdownNow();
            mStatusExecutor = null;
        }
        super.onDestroy();
    }

    private void bindFeatures() {
        for (Feature f : Feature.values()) {
            Preference p = findPreference(f.key);
            if (p == null) {
                continue;
            }
            p.setTitle(f.titleRes);
            p.setSummary(f.summaryRes);
            if (f.blocked()) {
                p.setEnabled(false);
                continue;
            }
            // 首次快照回来前先置灰：此时 remote() 还是 false，不能接受写入。
            p.setEnabled(false);
            p.setOnPreferenceChangeListener((pref, value) -> {
                if (!(value instanceof Boolean) || !(pref instanceof MaterialSwitchPreference)) {
                    return false;
                }
                boolean next = (Boolean) value;
                MaterialSwitchPreference sw = (MaterialSwitchPreference) pref;
                final boolean before = sw.isCheckedValue();
                if (!mStore.remote()) {
                    Toast.makeText(requireContext(),
                            R.string.status_remote_unavailable, Toast.LENGTH_SHORT).show();
                    return false;
                }
                // 用户已产生更新的选择：作废在途快照，避免旧值回写覆盖本次修改。
                mStatusGeneration.incrementAndGet();
                final String key = f.key;
                final long token = mWriteGate.begin(key);
                boolean enqueued = mStore.put(key, next, saved -> {
                    // 成功也记确认值：旧写入落盘后仍是后续失败的回滚目标。
                    boolean latest = mWriteGate.onResult(key, token, saved, next);
                    if (!isAdded()) {
                        return;
                    }
                    // 写结果比任何在途快照都新，作废它们；成功时以提交值为准。
                    mStatusGeneration.incrementAndGet();
                    if (!latest) {
                        // 已有更新的写入：旧结果不能覆盖界面。
                        return;
                    }
                    Preference current = findPreference(key);
                    if (!(current instanceof MaterialSwitchPreference)) {
                        return;
                    }
                    MaterialSwitchPreference currentSwitch = (MaterialSwitchPreference) current;
                    if (saved) {
                        currentSwitch.setCheckedValue(next);
                        return;
                    }
                    // commit 被远端拒绝：回到最近一次确认落盘的值，不假装已保存。
                    currentSwitch.setCheckedValue(mWriteGate.rollback(key, before));
                    Toast.makeText(requireContext(),
                            R.string.setting_save_failed, Toast.LENGTH_SHORT).show();
                });
                if (!enqueued) {
                    Toast.makeText(requireContext(),
                            R.string.setting_save_failed, Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            });
        }

        Preference limits = findPreference("footer_limits");
        if (limits != null) {
            limits.setSummary(R.string.footer_limits);
        }
        Preference hint = findPreference("footer_hint");
        if (hint != null) {
            hint.setSummary(R.string.footer_hint);
        }
    }

    /**
     * 桌面图标开关。
     *
     * <p>改的是本模块 launcher alias 的组件状态，与目标进程的 remote prefs 无关，
     * 所以不走 ConfigStore。组件状态读写是 PackageManager binder，
     * 统一放到后台队列；失败时回滚开关。
     */
    private void bindLauncherIcon() {
        Preference pref = findPreference(KEY_HIDE_ICON);
        if (!(pref instanceof MaterialSwitchPreference)) {
            return;
        }
        final MaterialSwitchPreference sw = (MaterialSwitchPreference) pref;
        sw.setTitle(R.string.app_hide_icon_title);
        sw.setSummary(R.string.app_hide_icon_summary);
        // 初始值交给紧随其后的 refresh() -> applySnapshot()，避免重复读取 binder。
        // 首次快照回来前先置灰：此时开关还是默认值，不能拿它当系统状态写回。
        sw.setEnabled(false);
        sw.setOnPreferenceChangeListener((preference, value) -> {
            if (!(value instanceof Boolean)) {
                return false;
            }
            final boolean hide = (Boolean) value;
            final boolean before = sw.isCheckedValue();
            Context context = requireContext();
            Context appContext = context.getApplicationContext();
            final Context bgContext = appContext != null ? appContext : context;
            // 用户已产生更新的选择：作废在途快照，避免旧 iconHidden 回写覆盖本次修改。
            mStatusGeneration.incrementAndGet();
            final long token = mWriteGate.begin(KEY_HIDE_ICON);
            ConfigStore.io().execute(() -> {
                final boolean ok = LauncherIcon.setHidden(bgContext, hide);
                ConfigStore.postToMain(() -> {
                    // 写结果比任何在途快照都新：作废它们（即使本次结果已被新写入取代）。
                    mStatusGeneration.incrementAndGet();
                    boolean latest = mWriteGate.onResult(KEY_HIDE_ICON, token, ok, hide);
                    if (!isAdded() || !latest) {
                        return;
                    }
                    Preference current = findPreference(KEY_HIDE_ICON);
                    if (!(current instanceof MaterialSwitchPreference)) {
                        return;
                    }
                    MaterialSwitchPreference currentSwitch = (MaterialSwitchPreference) current;
                    if (!ok) {
                        // 失败：回到最近一次确认的系统状态，不假装已切换。
                        currentSwitch.setCheckedValue(
                                mWriteGate.rollback(KEY_HIDE_ICON, before));
                        Toast.makeText(requireContext(),
                                R.string.app_hide_icon_failed, Toast.LENGTH_LONG).show();
                        return;
                    }
                    currentSwitch.setCheckedValue(hide);
                    Toast.makeText(requireContext(),
                            hide ? R.string.app_hide_icon_hidden_toast
                                    : R.string.app_hide_icon_shown_toast,
                            Toast.LENGTH_SHORT).show();
                });
            });
            return true;
        });
    }

    private void bindPages() {
        Preference home = findPreference("page_home_customize");
        if (home != null) {
            home.setOnPreferenceClickListener(p -> {
                openPage(new HomeCustomizeFragment());
                return true;
            });
        }
    }

    private void openPage(androidx.fragment.app.Fragment page) {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).openPage(page);
        }
    }

    /**
     * 重新读取框架状态与开关值；service 绑定后由 Activity 调用。
     *
     * <p>remote prefs 解析、开关读取、launcher 组件状态与状态卡查询全部在
     * mStatusExecutor 上完成，结果回主线程一次性渲染；generation 丢弃过期结果。
     */
    void refresh() {
        if (getContext() == null || mStatusExecutor == null
                || mStatusExecutor.isShutdown()) {
            return;
        }
        final Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        Context context = requireContext();
        Context appContext = context.getApplicationContext();
        final Context bgContext = appContext != null ? appContext : context;
        final XposedService service = ServiceBridge.get();
        final int generation = mStatusGeneration.incrementAndGet();
        try {
            mStatusExecutor.execute(() -> {
                // 首次解析 remote prefs 是同步 binder，必须在后台线程。
                mStore.resolveRemote();
                final boolean writable = mStore.remote();
                final Feature[] features = Feature.values();
                final boolean[] enabled = new boolean[features.length];
                for (int i = 0; i < features.length; i++) {
                    enabled[i] = mStore.get(features[i].key, features[i].defaultEnabled);
                }
                final boolean iconHidden = LauncherIcon.isHidden(bgContext);
                final StatusSnapshot snapshot = queryStatus(service, bgContext, writable);
                activity.runOnUiThread(() -> {
                    if (!isAdded() || mStatusGeneration.get() != generation
                            || ServiceBridge.get() != service) {
                        return;
                    }
                    applySnapshot(features, enabled, writable, iconHidden, snapshot);
                });
            });
        } catch (Throwable ignored) {
            // Fragment 正在销毁或 executor 已关闭：下次 onResume 会重新查询。
        }
    }

    private void applySnapshot(Feature[] features, boolean[] enabled, boolean writable,
                               boolean iconHidden, StatusSnapshot snapshot) {
        for (int i = 0; i < features.length; i++) {
            Feature f = features[i];
            Preference p = findPreference(f.key);
            if (!(p instanceof MaterialSwitchPreference)) {
                continue;
            }
            MaterialSwitchPreference sw = (MaterialSwitchPreference) p;
            if (f.blocked()) {
                sw.setCheckedValue(false);
                sw.setEnabled(false);
            } else {
                // 快照值只作为“还没有确认值”时的回滚基线，不覆盖已确认落盘的值。
                mWriteGate.seed(f.key, enabled[i]);
                sw.setCheckedValue(enabled[i]);
                sw.setEnabled(writable);
            }
        }
        // 组件状态可能被外部改动（adb、清数据、重装），回到本页时以系统状态为准。
        Preference icon = findPreference(KEY_HIDE_ICON);
        if (icon instanceof MaterialSwitchPreference) {
            mWriteGate.seed(KEY_HIDE_ICON, iconHidden);
            ((MaterialSwitchPreference) icon).setCheckedValue(iconHidden);
            // 首次快照已到，开关才有可信初始值。
            icon.setEnabled(true);
        }
        renderStatus(snapshot);
    }

    private StatusSnapshot queryStatus(XposedService service, Context context,
                                       boolean remotePrefs) {
        StatusSnapshot snapshot = new StatusSnapshot();
        if (service != null) {
            try {
                snapshot.api = service.getApiVersion();
            } catch (Throwable ignored) {
            }
            try {
                List<String> scope = service.getScope();
                snapshot.scoped = scope != null && scope.contains(TARGET);
            } catch (Throwable ignored) {
            }
            snapshot.running = isTargetRunning(service);
        }
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(TARGET, 0);
            snapshot.installed = true;
            snapshot.targetVersion = info.versionName;
        } catch (Throwable ignored) {
        }
        snapshot.remotePrefs = remotePrefs;
        snapshot.failedFeatures = StatusProvider.failedFeatureCount(context);
        return snapshot;
    }

    private boolean isTargetRunning(XposedService service) {
        try {
            List<HookedTarget> targets = service.getRunningTargets();
            if (targets == null) {
                return false;
            }
            for (HookedTarget target : targets) {
                String name = target.getProcessName();
                if (name != null && (name.equals(TARGET) || name.startsWith(TARGET + ":"))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void renderStatus(StatusSnapshot snapshot) {
        Preference status = findPreference("status");
        if (status != null) {
            String target = snapshot.targetVersion != null
                    ? snapshot.targetVersion
                    : getString(R.string.status_target_missing);
            String remote = getString(snapshot.remotePrefs
                    ? R.string.status_remote_ok
                    : R.string.status_remote_unavailable);
            if (ServiceBridge.get() == null) {
                status.setTitle(R.string.status_connecting);
                status.setSummary(getString(R.string.status_detail_connecting) + "\n"
                        + getString(R.string.status_meta_unknown, target) + "\n" + remote);
            } else if (!snapshot.scoped) {
                status.setTitle(R.string.status_inactive);
                status.setSummary(getString(R.string.status_detail_no_scope) + "\n"
                        + getString(R.string.status_meta, snapshot.api, target) + "\n" + remote);
            } else if (snapshot.running) {
                status.setTitle(R.string.status_active);
                status.setSummary(getString(R.string.status_detail_active,
                        snapshot.api, target) + "\n" + remote);
            } else {
                status.setTitle(R.string.status_connected);
                status.setSummary(getString(R.string.status_detail_not_running) + "\n"
                        + getString(R.string.status_meta, snapshot.api, target) + "\n" + remote);
            }
        }

        Preference warning = findPreference("warning");
        if (warning != null) {
            StringBuilder text = new StringBuilder();
            if (snapshot.installed && snapshot.targetVersion != null
                    && !VersionGate.isSupportedVersion(snapshot.targetVersion)) {
                text.append(getString(R.string.status_warning_version,
                        snapshot.targetVersion, VersionGate.SUPPORTED_VERSION));
            }
            if (snapshot.failedFeatures >= 2) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(getString(R.string.status_warning_failed,
                        snapshot.failedFeatures));
            }
            warning.setVisible(text.length() > 0);
            if (text.length() > 0) {
                warning.setSummary(text.toString());
            }
        }
    }

    private static final class StatusSnapshot {
        int api;
        boolean scoped;
        boolean running;
        boolean installed;
        boolean remotePrefs;
        int failedFeatures;
        String targetVersion;
    }
}
