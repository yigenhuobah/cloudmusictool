package com.cloudmusictool.app;

import android.content.Context;
import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 实验（ABTest）页。
 *
 * 只读展示目标进程回写的实际命中分组，并允许按精确白名单强开单个实验。
 * 强开结果写入 remote prefs 的 abtest_force_map（JSON 对象），重启网易云生效。
 */
public class ExperimentFragment extends PreferenceFragmentCompat {

    private static final int MAX_OBSERVED = 200;

    private ConfigStore mStore;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        mStore = new ConfigStore(requireContext());
        rebuild();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    /** service 绑定或切回本页时重新读取配置与观测数据。 */
    void refresh() {
        if (getContext() == null) {
            return;
        }
        mStore = new ConfigStore(requireContext());
        rebuild();
    }

    private void rebuild() {
        Context ctx = requireContext();
        PreferenceManager pm = getPreferenceManager();
        PreferenceScreen screen = pm.createPreferenceScreen(ctx);

        Map<String, String> forceMap = readForceMap();
        Map<String, Observed> observed = readObserved();

        addNotice(screen, ctx);
        addCurated(screen, ctx, forceMap, observed);
        addObserved(screen, ctx, forceMap, observed);
        addForced(screen, ctx, forceMap);

        setPreferenceScreen(screen);
    }

    // ---------------------------------------------------------------- sections

    private void addNotice(PreferenceScreen screen, Context ctx) {
        PreferenceCategory category = new PreferenceCategory(ctx);
        category.setTitle(R.string.experiment_section_notice);
        category.setPersistent(false);
        screen.addPreference(category);

        Preference notice = new Preference(ctx);
        notice.setTitle(R.string.experiment_notice_title);
        notice.setSummary(R.string.experiment_warning);
        notice.setSelectable(false);
        notice.setPersistent(false);
        category.addPreference(notice);

        Preference groupHelp = new Preference(ctx);
        groupHelp.setTitle(R.string.experiment_group_help_title);
        groupHelp.setSummary(R.string.experiment_group_help_summary);
        groupHelp.setSelectable(false);
        groupHelp.setPersistent(false);
        category.addPreference(groupHelp);
    }

    private void addCurated(PreferenceScreen screen, Context ctx,
                            Map<String, String> forceMap, Map<String, Observed> observed) {
        PreferenceCategory category = new PreferenceCategory(ctx);
        category.setTitle(R.string.experiment_section_curated);
        category.setPersistent(false);
        screen.addPreference(category);

        for (String key : AbTestPolicy.CURATED_KEYS) {
            final String curatedKey = key;
            Observed ob = observed.get(key);
            String observedGroup = ob == null || ob.group == null
                    ? getString(R.string.experiment_not_observed)
                    : ob.group;
            String forced = forceMap.containsKey(key)
                    ? forceMap.get(key)
                    : getString(R.string.experiment_force_none);

            Preference p = new Preference(ctx);
            p.setKey("curated_" + key);
            p.setTitle(getString(R.string.experiment_curated_title,
                    AbTestDescriptions.title(ctx, key), key));
            p.setSummary(AbTestDescriptions.description(ctx, key) + "\n"
                    + getString(R.string.experiment_curated_summary, observedGroup, forced));
            p.setPersistent(false);
            p.setOnPreferenceClickListener(pref -> {
                showGroupDialog(curatedKey, forceMap.get(curatedKey), false);
                return true;
            });
            category.addPreference(p);
        }
    }

    private void addObserved(PreferenceScreen screen, Context ctx,
                             Map<String, String> forceMap, Map<String, Observed> observed) {
        PreferenceCategory category = new PreferenceCategory(ctx);
        category.setTitle(R.string.experiment_section_observed);
        category.setPersistent(false);
        screen.addPreference(category);

        Preference refresh = new Preference(ctx);
        refresh.setKey("action_refresh");
        refresh.setTitle(R.string.experiment_refresh);
        refresh.setSummary(R.string.experiment_refresh_summary);
        refresh.setPersistent(false);
        refresh.setOnPreferenceClickListener(pref -> {
            refresh();
            toast(R.string.experiment_refreshed);
            return true;
        });
        category.addPreference(refresh);

        boolean any = false;
        for (Observed ob : observed.values()) {
            if (AbTestPolicy.isCurated(ob.key)) {
                continue;
            }
            any = true;

            String group = ob.group == null ? getString(R.string.experiment_unknown_group) : ob.group;
            String source = ob.source == null ? getString(R.string.experiment_unknown_group) : ob.source;
            String summary = getString(R.string.experiment_observed_summary, group, ob.count, source);

            Preference p = new Preference(ctx);
            p.setKey("observed_" + ob.key);
            p.setTitle(ob.key);
            p.setPersistent(false);

            if (AbTestPolicy.isBlocked(ob.key)) {
                p.setSummary(summary + " · " + getString(R.string.experiment_blocked));
                p.setSelectable(false);
            } else {
                if (forceMap.containsKey(ob.key)) {
                    summary = summary + " · " + getString(R.string.experiment_forced_mark, forceMap.get(ob.key));
                }
                p.setSummary(summary);
                p.setOnPreferenceClickListener(pref -> {
                    showGroupDialog(ob.key, forceMap.get(ob.key), true);
                    return true;
                });
            }
            category.addPreference(p);
        }

        if (!any) {
            Preference empty = new Preference(ctx);
            empty.setKey("observed_empty");
            empty.setSummary(R.string.experiment_observed_empty);
            empty.setSelectable(false);
            empty.setPersistent(false);
            category.addPreference(empty);
        }
    }

    private void addForced(PreferenceScreen screen, Context ctx, Map<String, String> forceMap) {
        PreferenceCategory category = new PreferenceCategory(ctx);
        category.setTitle(R.string.experiment_section_forced);
        category.setPersistent(false);
        screen.addPreference(category);

        if (forceMap.isEmpty()) {
            Preference empty = new Preference(ctx);
            empty.setKey("forced_empty");
            empty.setSummary(R.string.experiment_forced_empty);
            empty.setSelectable(false);
            empty.setPersistent(false);
            category.addPreference(empty);
        } else {
            for (Map.Entry<String, String> entry : forceMap.entrySet()) {
                final String key = entry.getKey();
                Preference p = new Preference(ctx);
                p.setKey("forced_" + key);
                p.setTitle(key);
                p.setSummary(getString(R.string.experiment_forced_summary, entry.getValue()));
                p.setPersistent(false);
                p.setOnPreferenceClickListener(pref -> {
                    confirmCancel(key);
                    return true;
                });
                category.addPreference(p);
            }
        }

        Preference reset = new Preference(ctx);
        reset.setKey("action_reset");
        reset.setTitle(R.string.experiment_reset);
        reset.setSummary(getString(R.string.experiment_reset_summary, forceMap.size()));
        reset.setPersistent(false);
        reset.setOnPreferenceClickListener(pref -> {
            confirmReset();
            return true;
        });
        category.addPreference(reset);
    }

    // ------------------------------------------------------------------ dialogs

    /** 分组单选；promote=true 表示这是从观测列表提升的 key，需要额外校验。 */
    private void showGroupDialog(String key, String current, boolean promote) {
        final String[] entries = new String[AbTestPolicy.GROUPS.length + 1];
        entries[0] = getString(R.string.experiment_force_none);
        int checked = 0;
        for (int i = 0; i < AbTestPolicy.GROUPS.length; i++) {
            entries[i + 1] = AbTestPolicy.GROUPS[i];
            if (AbTestPolicy.GROUPS[i].equals(current)) {
                checked = i + 1;
            }
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.experiment_group_dialog_title, key))
                .setSingleChoiceItems(entries, checked, (dialog, which) -> {
                    String group = which == 0 ? null : AbTestPolicy.GROUPS[which - 1];
                    dialog.dismiss();
                    applyForce(key, group, promote);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmCancel(String key) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.experiment_cancel_title)
                .setMessage(R.string.experiment_cancel_message)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    Map<String, String> map = readForceMap();
                    if (map.remove(key) != null && !writeForceMap(map)) {
                        toast(R.string.experiment_save_failed);
                        return;
                    }
                    rebuild();
                    toast(R.string.experiment_cancelled);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmReset() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.experiment_reset_title)
                .setMessage(R.string.experiment_reset_message)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    if (!writeForceMap(new LinkedHashMap<>())) {
                        toast(R.string.experiment_save_failed);
                        return;
                    }
                    rebuild();
                    toast(R.string.experiment_reset_done);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------- writes

    private void applyForce(String key, String group, boolean promote) {
        if (group != null && !AbTestPolicy.isValidGroup(group)) {
            return;
        }

        Map<String, String> map = readForceMap();

        if (group != null) {
            // promoted = 已提升的 key（forceMap 中不属于 CURATED 的）+ 本次待提升的 key
            Set<String> promoted = new LinkedHashSet<>();
            for (String forcedKey : map.keySet()) {
                if (!AbTestPolicy.isCurated(forcedKey)) {
                    promoted.add(forcedKey);
                }
            }
            promoted.add(key);
            if (!AbTestPolicy.isAllowed(key, promoted)) {
                toast(R.string.experiment_promote_denied);
                return;
            }
            map.put(key, group);
        } else {
            map.remove(key);
        }

        if (!writeForceMap(map)) {
            toast(R.string.experiment_save_failed);
            return;
        }
        rebuild();
        toast(R.string.experiment_saved);
    }

    private Map<String, String> readForceMap() {
        String raw = mStore.getString(Config.KEY_ABTEST_FORCE_MAP, null);
        // 实验页只校验分组名、不做白名单/黑名单过滤（需展示全部已强开项）；
        // 公共 JSON 解析收敛在 ForceMapCodec。
        return ForceMapCodec.applyFilter(ForceMapCodec.parse(raw),
                (key, group) -> AbTestPolicy.isValidGroup(group));
    }

    /** @return true 表示已写入 remote prefs；false 表示框架未连接或提交失败。 */
    private boolean writeForceMap(Map<String, String> map) {
        JSONObject obj = new JSONObject();
        try {
            for (Map.Entry<String, String> entry : map.entrySet()) {
                obj.put(entry.getKey(), entry.getValue());
            }
        } catch (Throwable ignored) {
        }
        return mStore.putString(Config.KEY_ABTEST_FORCE_MAP, obj.toString());
    }

    // --------------------------------------------------------------- observed

    private Map<String, Observed> readObserved() {
        LinkedHashMap<String, Observed> out = new LinkedHashMap<>();
        try {
            String raw = StatusProvider.readExperiments(requireContext());
            if (raw == null || raw.isEmpty()) {
                return out;
            }
            JSONArray items = new JSONObject(raw).optJSONArray("items");
            if (items == null) {
                return out;
            }
            for (int i = 0; i < items.length() && out.size() < MAX_OBSERVED; i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String key = item.optString("key", null);
                if (key == null || key.isEmpty() || out.containsKey(key)) {
                    continue;
                }
                out.put(key, new Observed(
                        key,
                        item.optString("group", null),
                        item.optString("source", null),
                        item.optInt("count", 0)));
            }
        } catch (Throwable ignored) {
            // 无观测数据或 JSON 损坏时降级为空列表
        }
        return out;
    }

    private void toast(int resId) {
        if (getContext() != null) {
            Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show();
        }
    }

    private static final class Observed {
        final String key;
        final String group;
        final String source;
        final int count;

        Observed(String key, String group, String source, int count) {
            this.key = key;
            this.group = group;
            this.source = source;
            this.count = count;
        }
    }
}
