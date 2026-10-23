package com.cloudmusictool.app;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.color.DynamicColors;

/**
 * 单 Activity 外壳：工具栏 + 四个顶层 tab（设置 / 实验 / 诊断 / 关于）+ 常驻底栏。
 *
 * 四个 Fragment 首次创建时一次性 add，切换只用 hide/show，因此各自的
 * Preference 滚动位置与控件状态都会保留。设置是默认 tab，返回键在任何
 * 非设置 tab 上都先回到设置，只有在设置页才退出应用。
 *
 * 首页定制仍是二级页：进入时隐藏当前 tab 与底栏，返回后恢复。
 */
public class MainActivity extends AppCompatActivity {

    private static final String STATE_TAB = "state_tab";
    private static final String TAG_SETTINGS = "tab_settings";
    private static final String TAG_EXPERIMENTS = "tab_experiments";
    private static final String TAG_DIAGNOSTICS = "tab_diagnostics";
    private static final String TAG_ABOUT = "tab_about";

    private static final String[] TAB_TAGS = {
            TAG_SETTINGS, TAG_EXPERIMENTS, TAG_DIAGNOSTICS, TAG_ABOUT
    };

    private MaterialToolbar mToolbar;
    private BottomNavigationView mBottomNav;
    /** 当前顶层 tab；二级页打开时它仍指向被隐藏的 tab。 */
    private Fragment mCurrentFragment;
    private int mSelectedTab = R.id.nav_settings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 必须在 super.onCreate 之前调用，否则动态取色不会生效。
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mToolbar = findViewById(R.id.toolbar);
        mBottomNav = findViewById(R.id.bottom_nav);
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
            Fragment settings = new SettingsFragment();
            Fragment experiments = new ExperimentFragment();
            Fragment diagnostics = new DiagnosticsFragment();
            Fragment about = new AboutFragment();
            // commitNow：底栏恢复选中态之前，四个 Fragment 必须已经就位，
            // 否则 setSelectedItemId 触发的回调会重复 add。
            getSupportFragmentManager().beginTransaction()
                    .add(R.id.settings_container, settings, TAG_SETTINGS)
                    .add(R.id.settings_container, experiments, TAG_EXPERIMENTS)
                    .hide(experiments)
                    .add(R.id.settings_container, diagnostics, TAG_DIAGNOSTICS)
                    .hide(diagnostics)
                    .add(R.id.settings_container, about, TAG_ABOUT)
                    .hide(about)
                    .commitNow();
            mCurrentFragment = settings;
            mSelectedTab = R.id.nav_settings;
        } else {
            mSelectedTab = savedInstanceState.getInt(STATE_TAB, R.id.nav_settings);
            if (!isTopLevelTab(mSelectedTab)) {
                mSelectedTab = R.id.nav_settings;
            }
            restoreFragments();
        }

        mBottomNav.setSelectedItemId(mSelectedTab);
        mBottomNav.setOnItemSelectedListener(item -> {
            switchTab(item.getItemId());
            return true;
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                FragmentManager fm = getSupportFragmentManager();
                if (fm.getBackStackEntryCount() > 0) {
                    fm.popBackStack();
                } else if (mSelectedTab != R.id.nav_settings) {
                    // 先切 Fragment，再同步底栏选中态；若回调再触发 switchTab，会因已选中而直接返回。
                    switchTab(R.id.nav_settings);
                    mBottomNav.setSelectedItemId(R.id.nav_settings);
                } else {
                    finish();
                }
            }
        });

        applyInsets();
        updateToolbar();

        // 顺序不能反：registerListener 对已缓存的 binder 会立即回调，
        // 回调必须先挂上，否则首次绑定会被空回调吃掉、永远等不到重绘。
        ServiceBridge.setOnBound(() -> runOnUiThread(this::refreshCurrent));
        ServiceBridge.bind(getApplicationContext());
        ServiceBridge.poke(getApplicationContext());
    }

    /** 旋转恢复：按 tag 找回四个 Fragment，只显示保存的 tab；二级页打开时全部隐藏。 */
    private void restoreFragments() {
        FragmentManager fm = getSupportFragmentManager();
        boolean secondary = fm.getBackStackEntryCount() > 0;
        FragmentTransaction tx = fm.beginTransaction();
        Fragment target = null;
        for (String tag : TAB_TAGS) {
            Fragment f = fm.findFragmentByTag(tag);
            boolean current = tag.equals(tagFor(mSelectedTab));
            if (f == null) {
                f = createFragmentForTag(tag);
                if (f == null) {
                    continue;
                }
                tx.add(R.id.settings_container, f, tag);
            }
            if (current) {
                target = f;
                if (secondary) {
                    tx.hide(f);
                } else {
                    tx.show(f);
                }
            } else {
                tx.hide(f);
            }
        }
        if (target == null) {
            // 状态不完整时按当前 tab 重建，避免容器空白。
            target = createFragmentForTag(tagFor(mSelectedTab));
            if (target != null) {
                tx.add(R.id.settings_container, target, tagFor(mSelectedTab));
                if (secondary) {
                    tx.hide(target);
                }
            }
        }
        tx.commitNow();
        mCurrentFragment = target;
    }

    /** 打开二级页；只允许一层，重复点击同一页时不叠加返回栈。 */
    public void openPage(Fragment page) {
        FragmentManager fm = getSupportFragmentManager();
        if (fm.getBackStackEntryCount() > 0) {
            return;
        }
        if (mCurrentFragment == null || mCurrentFragment.getClass() == page.getClass()) {
            return;
        }
        fm.beginTransaction()
                .setReorderingAllowed(true)
                .hide(mCurrentFragment)
                .add(R.id.settings_container, page, page.getClass().getName())
                .addToBackStack(page.getClass().getName())
                .commit();
    }

    /** hide/show 切换，不 replace：保留每个 tab 的滚动位置与控件状态。 */
    private void switchTab(int tabId) {
        if (!isTopLevelTab(tabId)) {
            tabId = R.id.nav_settings;
        }
        if (tabId == mSelectedTab && mCurrentFragment != null) {
            return;
        }
        FragmentManager fm = getSupportFragmentManager();
        if (fm.isStateSaved()) {
            return;
        }

        Fragment target = fm.findFragmentByTag(tagFor(tabId));
        FragmentTransaction tx = fm.beginTransaction();
        if (mCurrentFragment != null && mCurrentFragment != target
                && !mCurrentFragment.isHidden()) {
            tx.hide(mCurrentFragment);
        }
        if (target == null) {
            target = createFragment(tabId);
            tx.add(R.id.settings_container, target, tagFor(tabId));
        } else {
            tx.show(target);
        }
        tx.commit();

        mCurrentFragment = target;
        mSelectedTab = tabId;
        // 切回某个 tab 时刷新一次，等价于二级页时代 onResume 的行为。
        refreshCurrent();
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

    private boolean isTopLevelTab(int tabId) {
        return tabId == R.id.nav_settings || tabId == R.id.nav_experiments
                || tabId == R.id.nav_diagnostics || tabId == R.id.nav_about;
    }

    private Fragment createFragment(int tabId) {
        return createFragmentForTag(tagFor(tabId));
    }

    private Fragment createFragmentForTag(String tag) {
        switch (tag) {
            case TAG_EXPERIMENTS:
                return new ExperimentFragment();
            case TAG_DIAGNOSTICS:
                return new DiagnosticsFragment();
            case TAG_ABOUT:
                return new AboutFragment();
            case TAG_SETTINGS:
            default:
                return new SettingsFragment();
        }
    }

    private String tagFor(int tabId) {
        switch (tabId) {
            case R.id.nav_experiments:
                return TAG_EXPERIMENTS;
            case R.id.nav_diagnostics:
                return TAG_DIAGNOSTICS;
            case R.id.nav_about:
                return TAG_ABOUT;
            case R.id.nav_settings:
            default:
                return TAG_SETTINGS;
        }
    }

    /**
     * 二级页：返回箭头 + 页面标题，底栏隐藏；顶层 tab：无返回箭头，
     * 标题 app name + 版本 subtitle，底栏常驻。
     */
    private void updateToolbar() {
        FragmentManager fm = getSupportFragmentManager();
        boolean secondary = fm.getBackStackEntryCount() > 0;
        // MIUI 的 Resources 对 setNavigationIcon(0) 会抛 NotFoundException，顶层必须传 null。
        // 注意不能写成 secondary ? R.drawable.ic_arrow_back : null：三元会装箱成 Integer，
        // 顶层分支拆箱 null 直接 NPE。
        if (secondary) {
            mToolbar.setNavigationIcon(R.drawable.ic_arrow_back);
        } else {
            mToolbar.setNavigationIcon(null);
        }
        mBottomNav.setVisibility(secondary ? View.GONE : View.VISIBLE);
        if (secondary) {
            Fragment current = currentFragment();
            mToolbar.setTitle(current instanceof HomeCustomizeFragment
                    ? R.string.page_home_customize
                    : R.string.app_name_full);
            mToolbar.setSubtitle(null);
        } else {
            mToolbar.setTitle(R.string.app_name_full);
            mToolbar.setSubtitle("v" + versionName());
        }
    }

    /**
     * targetSdk 36 强制 edge-to-edge。root 统一吃 systemBars 四边：
     * 底栏可见时 padding 落在底栏下方，隐藏时落在内容下方，不会双倍。
     */
    private void applyInsets() {
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return windowInsets;
        });
    }

    /** 框架绑定/断开或切换 tab 后，只刷新当前可见页。 */
    private void refreshCurrent() {
        // binder 回调可能晚于 Activity 销毁，避免在已销毁的实例上操作 Fragment。
        if (isFinishing() || isDestroyed()) {
            return;
        }
        Fragment f = currentFragment();
        if (f == null) {
            f = mCurrentFragment;
        }
        if (f instanceof SettingsFragment) {
            ((SettingsFragment) f).refresh();
        } else if (f instanceof HomeCustomizeFragment) {
            ((HomeCustomizeFragment) f).refresh();
        } else if (f instanceof ExperimentFragment) {
            ((ExperimentFragment) f).refresh();
        } else if (f instanceof DiagnosticsFragment) {
            ((DiagnosticsFragment) f).refresh();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, mSelectedTab);
    }

    @Override
    protected void onDestroy() {
        // 清掉对 Activity 的静态引用，防止退出后 binder 回调仍持有已销毁实例。
        ServiceBridge.setOnBound(null);
        super.onDestroy();
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
