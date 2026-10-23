package com.cloudmusictool.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 模块 App 侧的开关读写。
 * 必须写框架的 remote preferences：网易云音乐进程读的是那份存储，
 * 写进模块自己的 MODE_PRIVATE 对目标进程完全不可见。
 */
final class ConfigStore {

    private static final String TAG = "CloudMusicTool";

    private final SharedPreferences mRemote;
    private final SharedPreferences mLocal;

    ConfigStore(Context context) {
        mRemote = ServiceBridge.remotePreferences(Config.GROUP);
        mLocal = context.getSharedPreferences(Config.GROUP, Context.MODE_PRIVATE);
    }

    /** 仅供单测：直接绑定给定存储，等价于框架未绑定时走本地 prefs 的路径。 */
    ConfigStore(SharedPreferences sp) {
        mRemote = null;
        mLocal = sp;
    }

    boolean remote() {
        return mRemote != null;
    }

    boolean get(String key, boolean def) {
        try {
            return (mRemote != null ? mRemote : mLocal).getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    void put(String key, boolean value) {
        try {
            SharedPreferences sp = mRemote != null ? mRemote : mLocal;
            sp.edit().putBoolean(key, value).apply();
            if (mRemote == null) {
                Log.w(TAG, "config written to LOCAL prefs (framework not bound): " + key);
            }
        } catch (Throwable t) {
            Log.w(TAG, "config write failed: " + key, t);
        }
    }

    String getString(String key, String def) {
        try {
            return (mRemote != null ? mRemote : mLocal).getString(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    /**
     * 写入字符串配置。
     *
     * @return 只有 remote prefs 可用且提交成功才返回 true。
     *         框架未绑定时仍回退写本地（保持原有降级行为），但明确返回 false，
     *         避免调用方把“写本地”当成“目标进程已生效”。
     */
    boolean putString(String key, String value) {
        if (mRemote == null) {
            try {
                SharedPreferences.Editor editor = mLocal.edit();
                if (value == null) {
                    editor.remove(key);
                } else {
                    editor.putString(key, value);
                }
                editor.commit();
            } catch (Throwable t) {
                Log.w(TAG, "local config write failed: " + key, t);
            }
            Log.w(TAG, "config written to LOCAL prefs (framework not bound): " + key);
            return false;
        }
        try {
            SharedPreferences.Editor editor = mRemote.edit();
            if (value == null) {
                editor.remove(key);
            } else {
                editor.putString(key, value);
            }
            boolean ok = editor.commit();
            if (!ok) {
                Log.w(TAG, "remote config write rejected: " + key);
            }
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "remote config write failed: " + key, t);
            return false;
        }
    }

    Set<String> getStringSet(String key, Set<String> def) {
        SharedPreferences sp = mRemote != null ? mRemote : mLocal;
        try {
            Set<String> set = sp.getStringSet(key, null);
            if (set != null) {
                return new HashSet<>(set);
            }
        } catch (Throwable ignored) {
        }
        try {
            String raw = sp.getString(key, null);
            // 与 Config.loadSet 一致：空串视为未设置，回退默认值，避免返回空集吞掉默认。
            if (raw != null && !raw.isEmpty()) {
                Set<String> parsed = new HashSet<>();
                for (String part : raw.split(",")) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty()) {
                        parsed.add(trimmed);
                    }
                }
                return parsed;
            }
        } catch (Throwable ignored) {
        }
        return def != null ? def : Collections.emptySet();
    }

    void putStringSet(String key, Set<String> value) {
        try {
            SharedPreferences sp = mRemote != null ? mRemote : mLocal;
            SharedPreferences.Editor editor = sp.edit();
            if (value != null) {
                try {
                    editor.putStringSet(key, value);
                } catch (Throwable t) {
                    StringBuilder sb = new StringBuilder();
                    for (String s : value) {
                        if (sb.length() > 0) {
                            sb.append(",");
                        }
                        sb.append(s);
                    }
                    editor.putString(key, sb.toString());
                }
            } else {
                editor.remove(key);
            }
            editor.apply();
        } catch (Throwable t) {
            Log.w(TAG, "config putStringSet failed: " + key, t);
        }
    }
}
