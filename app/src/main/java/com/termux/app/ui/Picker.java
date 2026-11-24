package com.termux.app.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.termux.R;

import java.util.Locale;

/** Bottom-sheet pickers and sheets, used instead of the stock centered list dialogs. */
public final class Picker {

    public interface OnPick {
        void onPick(int which);
    }

    /** A themed bottom sheet with a handle, an optional title/subtitle and a body to fill. */
    public static final class Sheet {
        public final BottomSheetDialog dialog;
        public final LinearLayout body;

        Sheet(BottomSheetDialog dialog, LinearLayout body) {
            this.dialog = dialog;
            this.body = body;
        }

        public void show() {
            UiPrefs.applyBlur(dialog.getContext(), dialog);
            dialog.show();
        }

        public void dismiss() {
            dialog.dismiss();
        }
    }

    private Picker() { }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Create an empty sheet; add views to {@link Sheet#body} and call {@link Sheet#show()}. */
    @NonNull
    public static Sheet sheet(@NonNull Context context, @Nullable CharSequence title, @Nullable CharSequence subtitle) {
        final BottomSheetDialog dialog = new BottomSheetDialog(context);

        float r = dp(context, 26);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(UiColors.attr(context, R.attr.tx_surface));
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bg.setStroke(dp(context, 1), UiColors.attr(context, R.attr.tx_stroke));

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(bg);
        root.setPadding(0, dp(context, 10), 0, dp(context, 12));

        View handle = new View(context);
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(UiColors.attr(context, R.attr.tx_stroke));
        hb.setCornerRadius(dp(context, 3));
        handle.setBackground(hb);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(context, 36), dp(context, 4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(handle, hp);

        if (title != null && title.length() > 0) {
            TextView t = new TextView(context);
            t.setText(title);
            t.setTextSize(18);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextColor(UiColors.attr(context, R.attr.tx_text));
            t.setPadding(dp(context, 24), dp(context, 16), dp(context, 24), subtitle == null ? dp(context, 8) : 0);
            root.addView(t);
        }
        if (subtitle != null && subtitle.length() > 0) {
            TextView s = new TextView(context);
            s.setText(subtitle);
            s.setTextSize(13);
            s.setTextColor(UiColors.attr(context, R.attr.tx_text_2));
            s.setPadding(dp(context, 24), dp(context, 2), dp(context, 24), dp(context, 8));
            root.addView(s);
        }

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(context);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(body, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(root);
        dialog.setOnShowListener(d -> {
            View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) {
                sheet.setBackgroundColor(Color.TRANSPARENT);
                BottomSheetBehavior<View> b = BottomSheetBehavior.from(sheet);
                b.setSkipCollapsed(true);
                b.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        });
        return new Sheet(dialog, body);
    }

    /** Show a single-choice list. {@code checked} is the selected index, or -1 for none. */
    @NonNull
    public static Sheet show(@NonNull Context context, @Nullable CharSequence title,
                             @NonNull CharSequence[] labels, int checked, @NonNull OnPick onPick) {
        return show(context, title, null, labels, checked, onPick);
    }

    @NonNull
    public static Sheet show(@NonNull Context context, @Nullable CharSequence title, @Nullable CharSequence subtitle,
                             @NonNull CharSequence[] labels, int checked, @NonNull OnPick onPick) {
        final Sheet sheet = sheet(context, title, subtitle);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            sheet.body.addView(row(context, labels[i], i == checked, v -> {
                sheet.dismiss();
                onPick.onPick(index);
            }));
        }
        sheet.show();
        return sheet;
    }

    /** A list entry that can carry vector icons before and after the label (0 = none). */
    public static final class Entry {
        @NonNull public final CharSequence label;
        @DrawableRes public final int leadingIcon;
        @DrawableRes public final int trailingIcon;

        public Entry(@NonNull CharSequence label, @DrawableRes int leadingIcon, @DrawableRes int trailingIcon) {
            this.label = label;
            this.leadingIcon = leadingIcon;
            this.trailingIcon = trailingIcon;
        }
    }

    /** Like {@link #show(Context, CharSequence, CharSequence[], int, OnPick)} but entries may have icons. */
    @NonNull
    public static Sheet showEntries(@NonNull Context context, @Nullable CharSequence title,
                                    @NonNull java.util.List<Entry> entries, @NonNull OnPick onPick) {
        final Sheet sheet = sheet(context, title, null);
        for (int i = 0; i < entries.size(); i++) {
            final int index = i;
            Entry e = entries.get(i);
            sheet.body.addView(row(context, e.label, false, e.leadingIcon, e.trailingIcon, v -> {
                sheet.dismiss();
                onPick.onPick(index);
            }));
        }
        sheet.show();
        return sheet;
    }

    /** One tappable row: label on the left, a check mark when selected. Destructive labels are red. */
    @NonNull
    public static View row(@NonNull Context context, @NonNull CharSequence label, boolean selected,
                           @NonNull View.OnClickListener click) {
        return row(context, label, selected, 0, 0, click);
    }

    @NonNull
    public static View row(@NonNull Context context, @NonNull CharSequence label, boolean selected,
                           @DrawableRes int leadingIcon, @DrawableRes int trailingIcon,
                           @NonNull View.OnClickListener click) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(context, 56));
        row.setPadding(dp(context, 24), 0, dp(context, 20), 0);
        row.setBackgroundResource(R.drawable.ripple_borderless);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(click);

        String l = label.toString().toLowerCase(Locale.ROOT);
        boolean destructive = l.startsWith("delete") || l.startsWith("remove") || l.startsWith("close session");

        if (leadingIcon != 0) {
            ImageView lead = new ImageView(context);
            lead.setImageResource(leadingIcon);
            lead.setColorFilter(UiColors.attr(context, R.attr.tx_text));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(context, 22), dp(context, 22));
            lp.setMarginEnd(dp(context, 14));
            row.addView(lead, lp);
        }

        TextView text = new TextView(context);
        text.setText(label);
        text.setTextSize(16);
        text.setTextColor(UiColors.attr(context, destructive ? R.attr.tx_red : R.attr.tx_text));
        if (selected) text.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (trailingIcon != 0) {
            ImageView trail = new ImageView(context);
            trail.setImageResource(trailingIcon);
            trail.setColorFilter(UiColors.attr(context, R.attr.tx_text_2));
            trail.setContentDescription("Private");
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(dp(context, 18), dp(context, 18));
            tp.setMarginStart(dp(context, 10));
            row.addView(trail, tp);
        }

        if (selected) {
            ImageView check = new ImageView(context);
            check.setImageResource(R.drawable.ic_check);
            check.setColorFilter(UiColors.attr(context, R.attr.tx_text));
            row.addView(check, new LinearLayout.LayoutParams(dp(context, 22), dp(context, 22)));
        }
        return row;
    }
}
