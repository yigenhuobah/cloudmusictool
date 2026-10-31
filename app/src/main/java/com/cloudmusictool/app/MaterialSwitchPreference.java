package com.cloudmusictool.app;

import android.content.Context;
import android.util.AttributeSet;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * Material 3 风格的开关项。
 * 配合 MaterialSwitch 控件，关掉本地持久化，统一写入 ConfigStore。
 */
public class MaterialSwitchPreference extends Preference {

    private boolean mChecked;

    public MaterialSwitchPreference(Context context) {
        this(context, null);
    }

    public MaterialSwitchPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_material_switch);
        setPersistent(false);
    }

    public void setCheckedValue(boolean checked) {
        if (mChecked != checked) {
            mChecked = checked;
            notifyDependencyChange(shouldDisableDependents());
            notifyChanged();
        }
    }

    public boolean isCheckedValue() {
        return mChecked;
    }

    @Override
    public boolean shouldDisableDependents() {
        return !mChecked || super.shouldDisableDependents();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        MaterialSwitch sw = (MaterialSwitch) holder.findViewById(R.id.preference_switch);
        if (sw == null) {
            return;
        }
        // 布局把开关设为 clickable/focusable=false，点击统一走 Preference.onClick()；
        // 这里只同步显示状态，不挂 OnCheckedChangeListener（该回调永远不会触发）。
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(mChecked);
        sw.setEnabled(isEnabled());
    }

    @Override
    protected void onClick() {
        if (!isEnabled()) {
            return;
        }
        boolean next = !mChecked;
        if (callChangeListener(next)) {
            mChecked = next;
            notifyDependencyChange(shouldDisableDependents());
            notifyChanged();
        }
    }
}
