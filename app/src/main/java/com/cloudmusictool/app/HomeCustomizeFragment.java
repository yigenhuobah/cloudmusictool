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
import java.util.Set;

/**
 * 首页定制与导航精简页面。
 */
public class HomeCustomizeFragment extends PreferenceFragmentCompat {

    private static final int REQ_PICK_BG = 1001;

    private ConfigStore mStore;

    // 发现页推荐模块代码与中文名（code 与 9.6.08 实际下发值对齐）
    private static final String[] BLOCK_KEYS = {
            "PAGE_RECOMMEND_PRIVATE_RCMD_SONG",
            "PAGE_RECOMMEND_RANK",
            "PAGE_RECOMMEND_SHORTCUT",
            "PAGE_RECOMMEND_RADAR",
            "PAGE_RECOMMEND_DAILY_RECOMMEND",
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
            "PAGE_RECOMMEND_PODCAST_RADIO_PROGRAM",
            "PAGE_RECOMMEND_PODCAST_MUSIC_RADIO",
            "PAGE_RECOMMEND_PODCAST_SUB_UPDATE",
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

    private static final String[] BLOCK_TITLES = {
            "猜你喜欢",
            "排行榜",
            "快捷入口 (每日推荐/心动模式等)",
            "雷达歌单",
            "每日推荐",
            "新歌新碟",
            "云村精选歌单",
            "红心相似推荐",
            "艺人/组合歌单",
            "心动歌单",
            "场景歌单",
            "风格歌单",
            "调研问卷",
            "VIP 专属模块",
            "VIP 小卡片",
            "运营 Banner 1",
            "运营 Banner 6",
            "播客电台",
            "播客音乐电台",
            "播客订阅更新",
            "播客关联节目",
            "艺人动态",
            "艺人 FM",
            "音乐 FM",
            "粉丝团卡片",
            "实时兴趣推荐",
            "组合模块",
            "品牌歌单",
            "音乐播客图文",
            "声音/视频歌单推荐"
    };

    // 顶部频道（与 9.6.08 实测 tab 对齐）
    private static final String[] CHANNEL_KEYS = {
            "心动", "推荐", "音乐", "播客", "听书", "AI写歌"
    };
    private static final String[] CHANNEL_TITLES = {
            "心动", "推荐", "音乐", "播客", "听书", "AI写歌"
    };

    // 底部导航（与 9.6.08 实测 tab 对齐：getTabTitle/getTabCode）
    private static final String[] BOTTOM_NAV_KEYS = {
            "首页", "搜索", "关注", "我的"
    };
    private static final String[] BOTTOM_NAV_TITLES = {
            "首页", "搜索", "关注", "我的"
    };

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.prefs_home_customize, rootKey);
        mStore = new ConfigStore(requireContext());
        setupPreferences();
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void setupPreferences() {
        bindMultiSelect(Config.KEY_HOME_BLOCKS_FILTER, BLOCK_TITLES, BLOCK_KEYS);
        bindMultiSelect(Config.KEY_TOP_CHANNELS_KEEP, CHANNEL_TITLES, CHANNEL_KEYS);
        bindMultiSelect(Config.KEY_BOTTOM_NAV_KEEP, BOTTOM_NAV_TITLES, BOTTOM_NAV_KEYS);

        Preference hint = findPreference("footer_home_custom_hint");
        if (hint != null) {
            hint.setSummary(R.string.home_customize_hint);
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
        if (pref != null) {
            pref.setEntries(entries);
            pref.setEntryValues(values);
            pref.setOnPreferenceChangeListener((preference, newValue) -> {
                if (newValue instanceof Set) {
                    @SuppressWarnings("unchecked")
                    Set<String> set = (Set<String>) newValue;
                    mStore.putStringSet(key, set);
                }
                return true;
            });
        }
    }

    void refresh() {
        if (getContext() == null) {
            return;
        }
        mStore = new ConfigStore(requireContext());
        MineBackgroundStore.syncExternal(requireContext());
        updatePrefValues(Config.KEY_HOME_BLOCKS_FILTER);
        updatePrefValues(Config.KEY_TOP_CHANNELS_KEEP);
        updatePrefValues(Config.KEY_BOTTOM_NAV_KEEP);
        Preference pick = findPreference("mine_bg_pick");
        if (pick != null) {
            pick.setSummary(MineBackgroundStore.hasImage(requireContext())
                    ? R.string.mine_bg_pick_summary_set
                    : R.string.mine_bg_pick_summary_unset);
        }
    }

    private void updatePrefValues(String key) {
        MultiSelectPreference pref = findPreference(key);
        if (pref != null) {
            Set<String> saved = mStore.getStringSet(key, Collections.emptySet());
            pref.setValues(saved);
        }
    }
}
