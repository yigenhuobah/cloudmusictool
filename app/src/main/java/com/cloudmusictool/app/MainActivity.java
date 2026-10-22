package com.cloudmusictool.app;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.color.DynamicColors;

/**
 * 单 Activity 外壳：MaterialToolbar + FrameLayout。
 *
 * 主页面固定为 {@link SettingsFragment}；首页定制、实验、诊断、关于作为二级页
 * 通过 hide/add 入返回栈。隐藏而非销毁主页面，返回时保留滚动位置。
 */
public class MainActivity extends AppCompatActivity {

    private MaterialToolbar mToolbar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mToolbar = findViewById(R.id.toolbar);
        mToolbar.setNavigationOnClickListener(v -> {
            FragmentManager fm = getSupportFragmentManager();
            if (fm.getBackStackEntryCount() > 0) {
                fm.popBackStack();
            } else {
                finish();
            }
        });
        getSupportFragmentManager().addOnBackStackChangedListener(this::updateToolbar);

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .add(R.id.settings_container, new SettingsFragment(),
                            SettingsFragment.class.getName())
                    .commit();
        }
        updateToolbar();
        applyInsets();

        ServiceBridge.setOnBound(() -> runOnUiThread(this::refreshCurrent));
        ServiceBridge.bind(getApplicationContext());
        ServiceBridge.poke(getApplicationContext());
    }

    /** 打开二级页；只允许一层，重复点击同一页时不叠加返回栈。 */
    public void openPage(Fragment page) {
        FragmentManager fm = getSupportFragmentManager();
        if (fm.getBackStackEntryCount() > 0) {
            return;
        }
        Fragment current = currentFragment();
        if (current == null || current.getClass() == page.getClass()) {
            return;
        }
        fm.beginTransaction()
                .setReorderingAllowed(true)
                .hide(current)
                .add(R.id.settings_container, page, page.getClass().getName())
                .addToBackStack(page.getClass().getName())
                .commit();
    }

    private Fragment currentFragment() {
        Fragment top = null;
        for (Fragment fragment : getSupportFragmentManager().getFragments()) {
            if (fragment.isAdded() && !fragment.isHidden()) {
                top = fragment;
            }
        }
        return top;
    }

    private void updateToolbar() {
        boolean secondary = getSupportFragmentManager().getBackStackEntryCount() > 0;
        // MIUI 的 Resources 对 setNavigationIcon(0) 会抛 NotFoundException，主页必须传 null。
        if (secondary) {
            mToolbar.setNavigationIcon(R.drawable.ic_arrow_back);
        } else {
            mToolbar.setNavigationIcon(null);
        }
        Fragment current = currentFragment();
        if (current instanceof HomeCustomizeFragment) {
            mToolbar.setTitle(R.string.page_home_customize);
        } else if (current instanceof ExperimentFragment) {
            mToolbar.setTitle(R.string.page_experiments);
        } else if (current instanceof DiagnosticsFragment) {
            mToolbar.setTitle(R.string.page_diagnostics);
        } else if (current instanceof AboutFragment) {
            mToolbar.setTitle(R.string.page_about);
        } else {
            mToolbar.setTitle(R.string.app_name_full);
        }
        mToolbar.setSubtitle("v" + versionName());
    }

    private void applyInsets() {
        View root = findViewById(R.id.root);
        View container = findViewById(R.id.settings_container);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, 0);
            container.setPadding(0, 0, 0, bars.bottom);
            return windowInsets;
        });
    }

    private void refreshCurrent() {
        for (Fragment fragment : getSupportFragmentManager().getFragments()) {
            if (fragment instanceof SettingsFragment) {
                ((SettingsFragment) fragment).refresh();
            } else if (fragment instanceof HomeCustomizeFragment) {
                ((HomeCustomizeFragment) fragment).refresh();
            } else if (fragment instanceof ExperimentFragment) {
                ((ExperimentFragment) fragment).refresh();
            } else if (fragment instanceof DiagnosticsFragment) {
                ((DiagnosticsFragment) fragment).refresh();
            }
        }
    }

    private String versionName() {
        try {
            String name = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            return name == null ? "?" : name;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }
}
