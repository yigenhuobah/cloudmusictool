package com.cloudmusictool.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 首页定制与导航精简页面。
 *
 * <p>所有 remote prefs 读取都在后台线程完成，结果回主线程渲染；
 * 框架未连接时三个多选项置灰并显示“设置不会生效”，写入失败则回滚 UI。
 */
public class HomeCustomizeFragment extends PreferenceFragmentCompat {

    private static final int REQ_PICK_BG = 1001;

    private static final String KEY_UNAVAILABLE = "home_customize_unavailable";

    private ConfigStore mStore;
    /** 丢弃过期的后台读取结果，避免旧快照覆盖新一次 refresh。 */
    private int mRefreshGeneration;

    // 9.6.13 新增 6 项（BANNER_0 / GREETING / NOTE / PODCAST_NARROW_SCENE /
    // SEARCH_TOP_BANNER / SECONDARY_TAB）与旧 30 项并存；疑似改名项保留旧 code，
    // 另列新 code（PAGE_BLOCK_TOPLIST / BANNER_0 / PODCAST_NARROW_SCENE）。
    private static final String[] BLOCK_KEYS = {
            "PAGE_RECOMMEND_PRIVATE_RCMD_SONG",
            "PAGE_RECOMMEND_RANK",
            "PAGE_BLOCK_TOPLIST",
            "PAGE_RECOMMEND_SHORTCUT",
            "PAGE_RECOMMEND_RADAR",
            "PAGE_RECOMMEND_DAILY_RECOMMEND",
            "PAGE_RECOMMEND_GREETING",
            "PAGE_RECOMMEND_NOTE",
            "PAGE_RECOMMEND_NEW_SONG_AND_ALBUM",
            "PAGE_RECOMMEND_SPECIAL_CLOUD_VILLAGE_PLAYLIST",
            "PAGE_RECOMMEND_RED_SIMILAR_SONG",
            "PAGE_RECOMMEND_MIXED_ARTIST_PLAYLIST",
            "PAGE_RECOMMEND_FEELING_PLAYLIST_LOCATION",
            "PAGE_RECOMMEND_SCENE_PLAYLIST_LOCATION",
            "PAGE_RECOMMEND_STYLE_PLAYLIST_1",
            "PAGE_RECOMMEND_SURVEY",
            "PAGE_RECOMMEND_VIP_MODULE",
            "PAGE_RECOMMEND_VIP_SMALL_CARD",
            "PAGE_RECOMMEND_BANNER_1",
            "PAGE_RECOMMEND_BANNER_6",
            "PAGE_RECOMMEND_BANNER_0",
            "PAGE_RECOMMEND_SEARCH_TOP_BANNER",
            "PAGE_RECOMMEND_SECONDARY_TAB",
            "PAGE_RECOMMEND_PODCAST_RADIO_PROGRAM",
            "PAGE_RECOMMEND_PODCAST_MUSIC_RADIO",
            "PAGE_RECOMMEND_PODCAST_SUB_UPDATE",
            "PAGE_RECOMMEND_PODCAST_NARROW_SCENE",
            "PAGE_RECOMMEND_PODCAST_RELATE_PROGRAM",
            "PAGE_RECOMMEND_ARTIST_TREND",
            "PAGE_RECOMMEND_ARTIST_FM_LIST",
            "PAGE_RECOMMEND_MUSIC_FM_LIST",
            "PAGE_RECOMMEND_FANS_GROUP_LEADER",
            "PAGE_RECOMMEND_REAL_TIME_INTEREST_RCMD",
            "PAGE_RECOMMEND_COMBINATION",
            "PAGE_RECOMMEND_FIRM_PLAYLIST",
            "PAGE_IMG_TEXT_MUSIC_PODCAST",
            "PAGE_IMG_TEXT_VOICELIST_RCMD_HOT"
    };

    // 顶部频道（与 9.6.08 实测 tab 对齐；key 是目标应用下发的频道代码）
    private static final String[] CHANNEL_KEYS = {
            "心动", "推荐", "音乐", "播客", "听书", "AI写歌"
    };

    // 底部导航：前 4 项是 9.6.08 实测 tab 标题；后 2 项是 9.6.13 新增 tab 的
    // tabCode（social=云村、publish=发布）。匹配逻辑是「标题或 code 任一命中」，
    // 因此旧标题保留，同时新增 code 保证 9.6.13 新 tab 可单独保留。
    private static final String[] BOTTOM_NAV_KEYS = {
            "首页", "搜索", "关注", "我的", "social", "publish"
    };

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.prefs_home_customize, rootKey);
        // 构造不做 I/O；remote prefs 在 refresh() 的后台任务里解析。
        mStore = new ConfigStore();
        setupPreferences();
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void setupPreferences() {
        bindMultiSelect(Config.KEY_HOME_BLOCKS_FILTER,
                getResources().getStringArray(R.array.home_block_titles), BLOCK_KEYS);
        bindMultiSelect(Config.KEY_TOP_CHANNELS_KEEP,
                getResources().getStringArray(R.array.home_channel_titles), CHANNEL_KEYS);
        bindMultiSelect(Config.KEY_BOTTOM_NAV_KEEP,
                getResources().getStringArray(R.array.home_bottom_nav_titles), BOTTOM_NAV_KEYS);

        Preference hint = findPreference("footer_home_custom_hint");
        if (hint != null) {
            hint.setSummary(R.string.home_customize_hint);
        }

        Preference warning = findPreference(KEY_UNAVAILABLE);
        if (warning != null) {
            warning.setVisible(false);
        }

        Preference pick = findPreference("mine_bg_pick");
        if (pick != null) {
            pick.setOnPreferenceClickListener(preference -> {
                pickBackground();
                return true;
            });
        }

        Preference clear = findPreference("mine_bg_clear");
        if (clear != null) {
            clear.setOnPreferenceClickListener(preference -> {
                confirmClearBackground();
                return true;
            });
        }
    }

    private void pickBackground() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_PICK_BG);
        } catch (Throwable t) {
            Toast.makeText(requireContext(), R.string.mine_bg_pick_failed,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmClearBackground() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.mine_bg_clear_title)
                .setMessage(R.string.mine_bg_clear_summary)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    MineBackgroundStore.clear(requireContext());
                    Toast.makeText(requireContext(), R.string.mine_bg_cleared,
                            Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_BG || resultCode != Activity.RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        Context context = getContext();
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        MineBackgroundStore.importImageAsync(
                appContext != null ? appContext : context,
                uri,
                ok -> {
                    if (!isAdded()) {
                        return;
                    }
                    Toast.makeText(requireContext(),
                            ok ? R.string.mine_bg_pick_success : R.string.mine_bg_pick_failed,
                            Toast.LENGTH_SHORT).show();
                    refresh();
                });
    }

    private void bindMultiSelect(String key, String[] entries, String[] values) {
        MultiSelectPreference pref = findPreference(key);
        if (pref == null) {
            return;
        }
        pref.setEntries(entries);
        pref.setEntryValues(values);
        // 首次快照回来前先置灰：此时 remote() 还是 false，不能接受写入。
        pref.setEnabled(false);
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            if (!(newValue instanceof Set)) {
                return false;
            }
            @SuppressWarnings("unchecked")
            Set<String> next = new HashSet<>((Set<String>) newValue);
            if (!mStore.remote()) {
                toast(R.string.home_customize_unavailable);
                return false;
            }
            Set<String> before = new HashSet<>(pref.getValues());
            // 用户已产生更新的选择：作废在途读取，避免旧快照回写覆盖本次修改。
            mRefreshGeneration++;
            boolean enqueued = mStore.putStringSet(key, next, saved -> {
                if (saved || !isAdded()) {
                    return;
                }
                // commit 被远端拒绝：回到保存前的选择并提示，不假装已保存。
                pref.setValues(before);
                toast(R.string.home_customize_save_failed);
            });
            if (!enqueued) {
                toast(R.string.home_customize_save_failed);
                return false;
            }
            return true;
        });
    }

    /**
     * 重新读取 remote prefs 与背景图状态。binder/磁盘读取全部在后台线程，
     * 结果回主线程；generation 保证旧快照不会覆盖新一次 refresh。
     */
    void refresh() {
        if (getContext() == null) {
            return;
        }
        Context context = requireContext();
        Context appContext = context.getApplicationContext();
        final Context bgContext = appContext != null ? appContext : context;
        final int generation = ++mRefreshGeneration;
        ConfigStore.io().execute(() -> {
            mStore.resolveRemote();
            final boolean remote = mStore.remote();
            final Set<String> blocks = mStore.getStringSet(
                    Config.KEY_HOME_BLOCKS_FILTER, Collections.emptySet());
            final Set<String> channels = mStore.getStringSet(
                    Config.KEY_TOP_CHANNELS_KEEP, Collections.emptySet());
            final Set<String> bottomNav = mStore.getStringSet(
                    Config.KEY_BOTTOM_NAV_KEEP, Collections.emptySet());
            final boolean hasImage = MineBackgroundStore.hasImage(bgContext);
            ConfigStore.postToMain(() -> {
                if (!isAdded() || generation != mRefreshGeneration) {
                    return;
                }
                applySnapshot(remote, blocks, channels, bottomNav, hasImage);
            });
        });
        MineBackgroundStore.syncExternal(bgContext);
    }

    private void applySnapshot(boolean remote, Set<String> blocks, Set<String> channels,
                               Set<String> bottomNav, boolean hasImage) {
        applyMultiSelect(Config.KEY_HOME_BLOCKS_FILTER, blocks, remote);
        applyMultiSelect(Config.KEY_TOP_CHANNELS_KEEP, channels, remote);
        applyMultiSelect(Config.KEY_BOTTOM_NAV_KEEP, bottomNav, remote);

        Preference warning = findPreference(KEY_UNAVAILABLE);
        if (warning != null) {
            warning.setVisible(!remote);
        }
        Preference hint = findPreference("footer_home_custom_hint");
        if (hint != null) {
            hint.setSummary(remote
                    ? R.string.home_customize_hint
                    : R.string.home_customize_unavailable);
        }
        Preference pick = findPreference("mine_bg_pick");
        if (pick != null) {
            pick.setSummary(hasImage
                    ? R.string.mine_bg_pick_summary_set
                    : R.string.mine_bg_pick_summary_unset);
        }
    }

    private void applyMultiSelect(String key, Set<String> values, boolean remote) {
        MultiSelectPreference pref = findPreference(key);
        if (pref == null) {
            return;
        }
        pref.setValues(values);
        pref.setEnabled(remote);
    }

    private void toast(int resId) {
        Context context = getContext();
        if (context != null) {
            Toast.makeText(context, resId, Toast.LENGTH_SHORT).show();
        }
    }
}
