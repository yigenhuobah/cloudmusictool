package com.cloudmusictool.app;

import android.app.Activity;
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

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.prefs, rootKey);
        mStore = new ConfigStore(requireContext());
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
            p.setOnPreferenceChangeListener((pref, value) -> {
                if (value instanceof Boolean) {
                    mStore.put(f.key, (Boolean) value);
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
     * 所以不走 ConfigStore。
     */
    private void bindLauncherIcon() {
        Preference pref = findPreference(KEY_HIDE_ICON);
        if (!(pref instanceof MaterialSwitchPreference)) {
            return;
        }
        MaterialSwitchPreference sw = (MaterialSwitchPreference) pref;
        sw.setTitle(R.string.app_hide_icon_title);
        sw.setSummary(R.string.app_hide_icon_summary);
        // 初始值交给紧随其后的 refresh() -> syncLauncherIconState()，避免重复读取 binder。
        sw.setOnPreferenceChangeListener((preference, value) -> {
            if (!(value instanceof Boolean)) {
                return false;
            }
            boolean hide = (Boolean) value;
            if (!LauncherIcon.setHidden(requireContext(), hide)) {
                Toast.makeText(requireContext(),
                        R.string.app_hide_icon_failed, Toast.LENGTH_LONG).show();
                return false;
            }
            Toast.makeText(requireContext(),
                    hide ? R.string.app_hide_icon_hidden_toast
                            : R.string.app_hide_icon_shown_toast,
                    Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    /** 组件状态可能被外部改动（adb、清数据、重装），回到本页时以系统状态为准重刷一次。 */
    private void syncLauncherIconState() {
        Preference pref = findPreference(KEY_HIDE_ICON);
        if (pref instanceof MaterialSwitchPreference) {
            ((MaterialSwitchPreference) pref)
                    .setCheckedValue(LauncherIcon.isHidden(requireContext()));
        }
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

    /** 重新读取框架状态与开关值；service 绑定后由 Activity 调用。 */
    void refresh() {
        if (getContext() == null || mStatusExecutor == null
                || mStatusExecutor.isShutdown()) {
            return;
        }
        mStore = new ConfigStore(requireContext());
        boolean writable = mStore.remote();
        for (Feature f : Feature.values()) {
            Preference p = findPreference(f.key);
            if (p instanceof MaterialSwitchPreference) {
                MaterialSwitchPreference sw = (MaterialSwitchPreference) p;
                if (f.blocked()) {
                    sw.setCheckedValue(false);
                    sw.setEnabled(false);
                } else {
                    sw.setCheckedValue(mStore.get(f.key, f.defaultEnabled));
                    sw.setEnabled(writable);
                }
            }
        }
        syncLauncherIconState();
        refreshStatusAsync();
    }

    private void refreshStatusAsync() {
        final XposedService service = ServiceBridge.get();
        final Activity activity = getActivity();
        final android.content.Context appContext = requireContext().getApplicationContext();
        if (activity == null) {
            return;
        }
        final int generation = mStatusGeneration.incrementAndGet();
        try {
            mStatusExecutor.execute(() -> {
                StatusSnapshot snapshot = queryStatus(service, appContext);
                activity.runOnUiThread(() -> {
                    if (!isAdded() || mStatusGeneration.get() != generation
                            || ServiceBridge.get() != service) {
                        return;
                    }
                    renderStatus(snapshot);
                });
            });
        } catch (Throwable ignored) {
            // Fragment 正在销毁或 executor 已关闭：下次 onResume 会重新查询。
        }
    }

    private StatusSnapshot queryStatus(XposedService service, android.content.Context context) {
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
        snapshot.remotePrefs = ServiceBridge.remotePreferences(Config.GROUP) != null;
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
