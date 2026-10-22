package com.cloudmusictool.app;

import android.content.Context;

/**
 * 可强开实验的中文名与说明。
 *
 * 文案放在 strings.xml 的 abtest_titles / abtest_descriptions 数组里，
 * 顺序与 {@link AbTestPolicy#CURATED_KEYS} 一致；未收录的 key 显示统一兜底文案。
 */
public final class AbTestDescriptions {

    private AbTestDescriptions() {
    }

    public static String title(Context context, String key) {
        int index = indexOf(key);
        if (index < 0) {
            return key;
        }
        String[] titles = context.getResources().getStringArray(R.array.abtest_titles);
        return index < titles.length ? titles[index] : key;
    }

    public static String description(Context context, String key) {
        int index = indexOf(key);
        if (index < 0) {
            return context.getString(R.string.experiment_unlisted);
        }
        String[] descriptions = context.getResources()
                .getStringArray(R.array.abtest_descriptions);
        return index < descriptions.length
                ? descriptions[index]
                : context.getString(R.string.experiment_unlisted);
    }

    private static int indexOf(String key) {
        if (key == null) {
            return -1;
        }
        for (int i = 0; i < AbTestPolicy.CURATED_KEYS.length; i++) {
            if (key.equals(AbTestPolicy.CURATED_KEYS[i])) {
                return i;
            }
        }
        return -1;
    }
}
