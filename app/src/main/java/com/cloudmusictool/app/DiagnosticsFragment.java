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

/**
 * 诊断页：逐功能展示目标进程回传的 registered / hit / skip。
 *
 * 进入页面与 onResume 时刷新，不自动轮询；无数据显示 “—”，blocked 显示原因。
 */
public class DiagnosticsFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    /** 重新读取 StatusProvider 落盘的状态并重建页面。 */
    void refresh() {
        if (getContext() == null) {
            return;
        }
        Context context = requireContext();
        JSONObject root = readStatus();
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
        String time = updatedAt > 0
                ? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        .format(new Date(updatedAt))
                : getString(R.string.diag_no_data);
        String summary = getString(R.string.diag_source_ok, time);
        if (root.optBoolean("truncated", false)) {
            summary = summary + "\n" + getString(R.string.diag_truncated);
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
        JSONObject root = readStatus();
        JSONObject features = root == null ? null : root.optJSONObject("features");
        StringBuilder text = new StringBuilder();
        text.append(getString(R.string.diag_source_title)).append(": ")
                .append(sourceSummary(root)).append('\n');
        for (Feature f : Feature.values()) {
            text.append(f.id).append(' ').append(getString(f.titleRes))
                    .append(' ').append(featureSummary(f, features)).append('\n');
        }
        try {
            ClipboardManager clipboard = (ClipboardManager) requireContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    getString(R.string.page_diagnostics), text.toString()));
            Toast.makeText(requireContext(), R.string.diag_copied,
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(requireContext(), R.string.diag_copy_failed,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private JSONObject readStatus() {
        String json = StatusProvider.readState(requireContext());
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(json);
        } catch (Throwable t) {
            return null;
        }
    }
}
