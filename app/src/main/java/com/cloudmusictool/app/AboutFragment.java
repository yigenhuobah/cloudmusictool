package com.cloudmusictool.app;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

/**
 * 关于页：模块/版本/目标应用/源码/四模块互链/免责声明/MIT。
 *
 * 互链用 SpannableString + ClickableSpan + ACTION_VIEW，颜色统一取
 * {@code @color/about_link}，不在 Java 里写死；当前模块仍可点击并标注“（当前）”。
 */
public class AboutFragment extends Fragment {

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_about, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        TextView header = view.findViewById(R.id.about_header);
        header.setText(R.string.about_header);

        TextView version = view.findViewById(R.id.about_version);
        String versionName = "?";
        long versionCode = 0L;
        try {
            PackageInfo info = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0);
            versionName = info.versionName == null ? "?" : info.versionName;
            versionCode = info.getLongVersionCode();
        } catch (Throwable ignored) {
        }
        version.setText(getString(R.string.about_version, versionName, versionCode));

        TextView target = view.findViewById(R.id.about_target);
        target.setText(getString(R.string.about_target, VersionGate.SUPPORTED_VERSION));

        TextView source = view.findViewById(R.id.about_source);
        SpannableStringBuilder sourceText =
                new SpannableStringBuilder(getString(R.string.about_source_label));
        appendLink(sourceText, getString(R.string.about_source_text),
                getString(R.string.about_source_url), false);
        source.setText(sourceText);
        source.setMovementMethod(LinkMovementMethod.getInstance());
        source.setHighlightColor(Color.TRANSPARENT);

        TextView modules = view.findViewById(R.id.about_modules);
        SpannableStringBuilder moduleText = new SpannableStringBuilder();
        appendLink(moduleText, getString(R.string.about_module_cloudmusictool),
                getString(R.string.about_module_cloudmusictool_url), true);
        moduleText.append('\n');
        appendLink(moduleText, getString(R.string.about_module_quietbox),
                getString(R.string.about_module_quietbox_url), false);
        moduleText.append('\n');
        appendLink(moduleText, getString(R.string.about_module_quietmap),
                getString(R.string.about_module_quietmap_url), false);
        moduleText.append('\n');
        appendLink(moduleText, getString(R.string.about_module_tiebaquiet),
                getString(R.string.about_module_tiebaquiet_url), false);
        modules.setText(moduleText);
        modules.setMovementMethod(LinkMovementMethod.getInstance());
        modules.setHighlightColor(Color.TRANSPARENT);

        TextView license = view.findViewById(R.id.about_license);
        SpannableStringBuilder licenseText = new SpannableStringBuilder();
        appendLink(licenseText, getString(R.string.about_license_name),
                getString(R.string.about_license_url), false);
        license.setText(licenseText);
        license.setMovementMethod(LinkMovementMethod.getInstance());
        license.setHighlightColor(Color.TRANSPARENT);
    }

    private void appendLink(SpannableStringBuilder builder, String label,
                            String url, boolean current) {
        int start = builder.length();
        builder.append(label);
        if (current) {
            builder.append(getString(R.string.about_current_mark));
        }
        int end = builder.length();
        final int linkColor = ContextCompat.getColor(requireContext(), R.color.about_link);
        builder.setSpan(new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                openUrl(url);
            }

            @Override
            public void updateDrawState(TextPaint ds) {
                super.updateDrawState(ds);
                ds.setColor(linkColor);
                ds.setUnderlineText(false);
            }
        }, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            Toast.makeText(requireContext(), R.string.about_link_failed,
                    Toast.LENGTH_SHORT).show();
        }
    }
}
