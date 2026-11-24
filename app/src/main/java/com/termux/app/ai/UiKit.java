package com.termux.app.ai;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.ui.UiColors;

import java.util.Locale;

/** Small view factory so the programmatic screens look like the rest of the app. */
final class UiKit {

    private UiKit() { }

    static int dp(@NonNull Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView label(@NonNull Context c, @NonNull String text) {
        TextView tv = new TextView(c);
        tv.setText(text.toUpperCase(Locale.US));
        tv.setTextSize(12);
        tv.setLetterSpacing(0.08f);
        tv.setTextColor(UiColors.attr(c, R.attr.tx_text_2));
        tv.setPadding(dp(c, 4), dp(c, 22), 0, dp(c, 8));
        return tv;
    }

    static TextView small(@NonNull Context c, @NonNull String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setLineSpacing(dp(c, 2), 1f);
        tv.setTextColor(UiColors.attr(c, R.attr.tx_text_2));
        tv.setPadding(dp(c, 4), dp(c, 8), dp(c, 4), 0);
        return tv;
    }

    static TextView link(@NonNull Context c, @NonNull String text, @NonNull View.OnClickListener l) {
        TextView tv = small(c, text);
        tv.setTextSize(13);
        tv.setTextColor(UiColors.attr(c, R.attr.tx_text));
        tv.setPadding(dp(c, 4), dp(c, 10), dp(c, 4), dp(c, 4));
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setOnClickListener(l);
        return tv;
    }

    /** A selectable pill. Call {@code setSelected} to show state. */
    static TextView chip(@NonNull Context c, @NonNull String text, @NonNull View.OnClickListener l) {
        TextView chip = new TextView(c);
        chip.setText(text);
        chip.setTextSize(13);
        chip.setGravity(Gravity.CENTER);
        chip.setTextColor(ContextCompat.getColorStateList(c, R.color.chip_text));
        chip.setBackgroundResource(R.drawable.bg_chip);
        chip.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setOnClickListener(l);
        return chip;
    }

    static LinearLayout.LayoutParams chipParams(@NonNull Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(c, 36));
        lp.setMarginEnd(dp(c, 8));
        lp.bottomMargin = dp(c, 8);
        return lp;
    }

    static EditText edit(@NonNull Context c, @NonNull String hint, int inputType) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setInputType(inputType);
        e.setTextSize(15);
        e.setTextColor(UiColors.attr(c, R.attr.tx_text));
        e.setHintTextColor(UiColors.attr(c, R.attr.tx_text_2));
        e.setBackgroundResource(R.drawable.bg_search);
        e.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12));
        e.setMinHeight(dp(c, 48));
        if (Build.VERSION.SDK_INT >= 26) e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        return e;
    }

    static EditText numberEdit(@NonNull Context c, @NonNull String hint) {
        return edit(c, hint, InputType.TYPE_CLASS_NUMBER);
    }

    static TextView button(@NonNull Context c, @NonNull String text, boolean primary, @NonNull View.OnClickListener l) {
        TextView b = new TextView(c);
        b.setText(text);
        b.setTextSize(15);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);
        if (primary) {
            b.setBackgroundResource(R.drawable.bg_btn_primary);
            b.setTextColor(ContextCompat.getColor(c, R.color.tx_on_accent));
        } else {
            b.setBackgroundResource(R.drawable.ripple_card);
            b.setTextColor(UiColors.attr(c, R.attr.tx_text));
        }
        b.setOnClickListener(l);
        return b;
    }

    static LinearLayout.LayoutParams fullWidth(@NonNull Context c, int heightDp, int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(c, heightDp));
        lp.topMargin = dp(c, topMarginDp);
        return lp;
    }

    // ------------------------------------------------------------- card kit

    /** Space between stacked cards. */
    static LinearLayout.LayoutParams cardParams(@NonNull Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 14);
        return lp;
    }

    /** A rounded card with a numbered header; add the step's content to the returned layout. */
    @NonNull
    static LinearLayout step(@NonNull Context c, int number, @NonNull String title, @Nullable String subtitle) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_step_card);
        card.setPadding(dp(c, 18), dp(c, 16), dp(c, 18), dp(c, 18));

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView badge = new TextView(c);
        badge.setText(String.valueOf(number));
        badge.setTextSize(13);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setTextColor(UiColors.attr(c, R.attr.tx_text));
        badge.setBackgroundResource(R.drawable.bg_step_badge);
        head.addView(badge, new LinearLayout.LayoutParams(dp(c, 28), dp(c, 28)));

        LinearLayout titles = new LinearLayout(c);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(UiColors.attr(c, R.attr.tx_text));
        titles.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView st = new TextView(c);
            st.setText(subtitle);
            st.setTextSize(12);
            st.setTextColor(UiColors.attr(c, R.attr.tx_text_2));
            titles.addView(st);
        }
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tp.setMarginStart(dp(c, 12));
        head.addView(titles, tp);
        card.addView(head);
        return card;
    }

    /** A log box with its own height that scrolls on its own even though the whole page scrolls too. */
    static final class NestedScroll extends ScrollView {
        NestedScroll(@NonNull Context c) {
            super(c);
            setNestedScrollingEnabled(true);
            setVerticalScrollBarEnabled(true);
            setScrollbarFadingEnabled(false);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent e) {
            View child = getChildAt(0);
            if (child != null && child.getHeight() > getHeight() && getParent() != null) {
                int a = e.getActionMasked();
                getParent().requestDisallowInterceptTouchEvent(a != MotionEvent.ACTION_UP && a != MotionEvent.ACTION_CANCEL);
            }
            return super.dispatchTouchEvent(e);
        }
    }

    /** Two or more mutually exclusive choices in one pill. */
    static final class Segmented {
        final LinearLayout view;
        private final java.util.List<TextView> mItems = new java.util.ArrayList<>();

        Segmented(@NonNull LinearLayout view) {
            this.view = view;
        }

        void select(int index) {
            for (int i = 0; i < mItems.size(); i++) mItems.get(i).setSelected(i == index);
        }
    }

    interface IndexListener {
        void onIndex(int index);
    }

    @NonNull
    static Segmented segmented(@NonNull Context c, @NonNull String[] labels, @NonNull IndexListener l) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundResource(R.drawable.bg_segment);
        row.setPadding(dp(c, 3), dp(c, 3), dp(c, 3), dp(c, 3));
        final Segmented seg = new Segmented(row);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView item = new TextView(c);
            item.setText(labels[i]);
            item.setTextSize(14);
            item.setTypeface(Typeface.DEFAULT_BOLD);
            item.setGravity(Gravity.CENTER);
            item.setSingleLine(true);
            item.setEllipsize(TextUtils.TruncateAt.END);
            item.setTextColor(ContextCompat.getColorStateList(c, R.color.segment_text));
            item.setBackgroundResource(R.drawable.bg_segment_item);
            item.setClickable(true);
            item.setFocusable(true);
            item.setOnClickListener(v -> l.onIndex(index));
            row.addView(item, new LinearLayout.LayoutParams(0, dp(c, 40), 1f));
            seg.mItems.add(item);
        }
        return seg;
    }

    /** An icon tile with a title and a second line; tap handling is up to the caller. */
    static final class InfoRow {
        final LinearLayout view;
        final ImageView icon;
        final TextView title;
        final TextView sub;

        InfoRow(LinearLayout view, ImageView icon, TextView title, TextView sub) {
            this.view = view;
            this.icon = icon;
            this.title = title;
            this.sub = sub;
        }
    }

    @NonNull
    static InfoRow infoRow(@NonNull Context c, @DrawableRes int iconRes) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon = new ImageView(c);
        icon.setImageResource(iconRes);
        icon.setColorFilter(UiColors.attr(c, R.attr.tx_text));
        icon.setBackgroundResource(R.drawable.bg_icon_tile);
        icon.setPadding(dp(c, 11), dp(c, 11), dp(c, 11), dp(c, 11));
        row.addView(icon, new LinearLayout.LayoutParams(dp(c, 46), dp(c, 46)));

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(c);
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        title.setTextColor(UiColors.attr(c, R.attr.tx_text));
        TextView sub = new TextView(c);
        sub.setTextSize(12);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        sub.setTextColor(UiColors.attr(c, R.attr.tx_text_2));
        texts.addView(title);
        texts.addView(sub);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tp.setMarginStart(dp(c, 14));
        row.addView(texts, tp);
        return new InfoRow(row, icon, title, sub);
    }

    /** A button with a leading icon. Disabled buttons fade. */
    static final class ActionButton extends LinearLayout {
        private final ImageView mIcon;
        private final TextView mLabel;

        ActionButton(@NonNull Context c, @NonNull String text, @DrawableRes int iconRes, boolean primary,
                     @NonNull View.OnClickListener l) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER);
            setClickable(true);
            setFocusable(true);
            int fg;
            if (primary) {
                setBackgroundResource(R.drawable.bg_btn_primary);
                fg = ContextCompat.getColor(c, R.color.tx_on_accent);
            } else {
                setBackgroundResource(R.drawable.ripple_card);
                fg = UiColors.attr(c, R.attr.tx_text);
            }
            mIcon = new ImageView(c);
            mIcon.setColorFilter(fg);
            mIcon.setVisibility(iconRes == 0 ? GONE : VISIBLE);
            if (iconRes != 0) mIcon.setImageResource(iconRes);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(c, 20), dp(c, 20));
            ip.setMarginEnd(dp(c, 10));
            addView(mIcon, ip);
            mLabel = new TextView(c);
            mLabel.setText(text);
            mLabel.setTextSize(15);
            mLabel.setTypeface(Typeface.DEFAULT_BOLD);
            mLabel.setTextColor(fg);
            addView(mLabel);
            setOnClickListener(l);
        }

        void setLabel(@NonNull CharSequence text) {
            mLabel.setText(text);
        }

        void setIcon(@DrawableRes int iconRes) {
            mIcon.setVisibility(iconRes == 0 ? GONE : VISIBLE);
            if (iconRes != 0) mIcon.setImageResource(iconRes);
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            setAlpha(enabled ? 1f : 0.4f);
        }
    }

    /** A selectable square-ish card for one tool: icon, name, what it makes, and whether it is installed. */
    static final class Tile extends LinearLayout {
        private final ImageView mCheck;
        final TextView title;
        final TextView sub;
        final TextView status;
        private final View mDot;

        Tile(@NonNull Context c, @DrawableRes int iconRes, @NonNull String name, @NonNull String produces,
             @NonNull View.OnClickListener l) {
            super(c);
            setOrientation(VERTICAL);
            setBackgroundResource(R.drawable.bg_tile);
            setPadding(dp(c, 14), dp(c, 14), dp(c, 14), dp(c, 12));
            setClickable(true);
            setFocusable(true);
            setOnClickListener(l);

            LinearLayout top = new LinearLayout(c);
            top.setOrientation(HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            ImageView icon = new ImageView(c);
            icon.setImageResource(iconRes);
            icon.setColorFilter(UiColors.attr(c, R.attr.tx_text));
            top.addView(icon, new LayoutParams(dp(c, 24), dp(c, 24)));
            View spacer = new View(c);
            top.addView(spacer, new LayoutParams(0, 1, 1f));
            mCheck = new ImageView(c);
            mCheck.setImageResource(R.drawable.ic_check_circle);
            mCheck.setColorFilter(UiColors.attr(c, R.attr.tx_accent));
            mCheck.setVisibility(INVISIBLE);
            top.addView(mCheck, new LayoutParams(dp(c, 20), dp(c, 20)));
            addView(top);

            title = new TextView(c);
            title.setText(name);
            title.setTextSize(15);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(UiColors.attr(c, R.attr.tx_text));
            LayoutParams tp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            tp.topMargin = dp(c, 12);
            addView(title, tp);

            sub = new TextView(c);
            sub.setText(produces);
            sub.setTextSize(12);
            sub.setTextColor(UiColors.attr(c, R.attr.tx_text_2));
            addView(sub);

            LinearLayout st = new LinearLayout(c);
            st.setOrientation(HORIZONTAL);
            st.setGravity(Gravity.CENTER_VERTICAL);
            mDot = new View(c);
            st.addView(mDot, new LayoutParams(dp(c, 7), dp(c, 7)));
            status = new TextView(c);
            status.setTextSize(11);
            status.setTextColor(UiColors.attr(c, R.attr.tx_text_2));
            LayoutParams sp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            sp.setMarginStart(dp(c, 6));
            st.addView(status, sp);
            LayoutParams stp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            stp.topMargin = dp(c, 10);
            addView(st, stp);
        }

        void setInstalled(boolean installed) {
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(UiColors.attr(getContext(), installed ? R.attr.tx_green : R.attr.tx_orange));
            mDot.setBackground(d);
            status.setText(installed ? "Ready" : "Not installed");
            setAlpha(installed ? 1f : 0.6f);
        }

        @Override
        public void setSelected(boolean selected) {
            super.setSelected(selected);
            mCheck.setVisibility(selected ? VISIBLE : INVISIBLE);
        }
    }

    /** "12.3 MB" style size. */
    @NonNull
    static String size(long bytes) {
        if (bytes >= 1L << 30) return String.format(Locale.US, "%.2f GB", bytes / 1073741824.0);
        if (bytes >= 1L << 20) return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        if (bytes >= 1L << 10) return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        return bytes + " B";
    }
}
