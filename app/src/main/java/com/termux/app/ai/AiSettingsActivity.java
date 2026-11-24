package com.termux.app.ai;

import android.app.AlertDialog;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.ui.RedesignActivity;
import com.termux.app.ui.UiColors;
import com.termux.app.ui.Picker;
import com.termux.app.ui.UiPrefs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Provider, API key, model and safety options for the AI assistant. */
public class AiSettingsActivity extends RedesignActivity {

    private LinearLayout mBody;
    private AiConfig.Preset mPreset;
    private final List<TextView> mChips = new ArrayList<>();
    private TextView mPresetNote;
    private TextView mKeyNote;
    private TextView mResult;
    private EditText mBase;
    private EditText mKey;
    private EditText mModel;
    private EditText mSteps;
    private EditText mPrompt;
    private SwitchCompat mAutoRun;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_settings);
        setupHeader("AI Settings", null);
        mBody = findViewById(R.id.ai_body);
        mPreset = AiConfig.getActive(this);
        build();
        loadPreset(mPreset);
    }

    @Override
    protected void onPause() {
        super.onPause();
        save(false);
    }

    // -------------------------------------------------------------------- build

    private void build() {
        mBody.addView(label("Provider"));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (final AiConfig.Preset p : AiConfig.PRESETS) {
            TextView chip = new TextView(this);
            chip.setText(p.label);
            chip.setTextSize(13);
            chip.setGravity(Gravity.CENTER);
            chip.setTextColor(ContextCompat.getColorStateList(this, R.color.chip_text));
            chip.setBackgroundResource(R.drawable.bg_chip);
            chip.setPadding(dp(16), 0, dp(16), 0);
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setTag(p.id);
            chip.setOnClickListener(v -> switchPreset(p));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(36));
            lp.setMarginEnd(dp(8));
            row.addView(chip, lp);
            mChips.add(chip);
        }
        hs.addView(row);
        mBody.addView(hs);

        mPresetNote = small("");
        mBody.addView(mPresetNote);

        mBody.addView(label("Base URL"));
        mBase = edit("https://…", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, false);
        mBody.addView(mBase);

        mBody.addView(label("API key"));
        mKey = edit("Paste API key", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, false);
        mBody.addView(mKey);
        mKeyNote = small("");
        mBody.addView(mKeyNote);
        TextView remove = link("Remove saved key", v -> {
            AiConfig.saveApiKey(this, mPreset, "");
            mKey.setText("");
            refreshKeyNote();
            Toast.makeText(this, "Key removed", Toast.LENGTH_SHORT).show();
        });
        mBody.addView(remove);

        mBody.addView(label("Model"));
        LinearLayout modelRow = new LinearLayout(this);
        modelRow.setOrientation(LinearLayout.HORIZONTAL);
        modelRow.setGravity(Gravity.CENTER_VERTICAL);
        mModel = edit("Model name", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, false);
        modelRow.addView(mModel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView fetch = button("Fetch models", false, v -> fetchModels());
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48));
        fp.setMarginStart(dp(8));
        fetch.setPadding(dp(16), 0, dp(16), 0);
        modelRow.addView(fetch, fp);
        mBody.addView(modelRow);

        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        bp.topMargin = dp(14);
        mBody.addView(button("Test connection", false, v -> testConnection()), bp);
        mResult = small("");
        mBody.addView(mResult);

        mBody.addView(label("Commands"));
        mAutoRun = new SwitchCompat(this);
        mAutoRun.setText("Run commands without asking");
        mAutoRun.setTextColor(UiColors.attr(this, R.attr.tx_text));
        mAutoRun.setTextSize(15);
        mAutoRun.setChecked(AiConfig.isAutoRun(this));
        mBody.addView(mAutoRun);
        mBody.addView(small("Off: you approve every command (recommended)."));

        mBody.addView(label("Max tool steps per message"));
        mSteps = edit("12", InputType.TYPE_CLASS_NUMBER, false);
        mSteps.setText(String.valueOf(AiConfig.getMaxSteps(this)));
        mBody.addView(mSteps);

        mBody.addView(label("System prompt"));
        mPrompt = edit("", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, true);
        mPrompt.setMinLines(6);
        mPrompt.setMaxLines(14);
        mPrompt.setGravity(Gravity.TOP | Gravity.START);
        mPrompt.setText(AiConfig.getSystemPrompt(this));
        mBody.addView(mPrompt);
        mBody.addView(link("Reset to default", v -> mPrompt.setText(AiConfig.DEFAULT_SYSTEM_PROMPT)));

        mBody.addView(label("Reverse-engineering tools"));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        tp.topMargin = dp(10);
        mBody.addView(button("Install RE tools…", false, v -> ReToolkit.promptInstall(this)), tp);

        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        sp.topMargin = dp(24);
        mBody.addView(button("Save", true, v -> {
            save(true);
            finish();
        }), sp);
    }

    // -------------------------------------------------------------- behaviour

    private void switchPreset(@NonNull AiConfig.Preset p) {
        if (p.id.equals(mPreset.id)) return;
        save(false); // keep what was typed for the provider we are leaving
        mPreset = p;
        AiConfig.setActive(this, p);
        loadPreset(p);
    }

    private void loadPreset(@NonNull AiConfig.Preset p) {
        for (TextView chip : mChips) chip.setSelected(p.id.equals(chip.getTag()));
        mPresetNote.setText("");
        mPresetNote.setVisibility(View.GONE);
        mBase.setText(AiConfig.getBaseUrl(this, p));
        mModel.setText(AiConfig.getModel(this, p));
        mKey.setText("");
        mResult.setText("");
        refreshKeyNote();
    }

    private void refreshKeyNote() {
        boolean has = AiConfig.hasApiKey(this, mPreset);
        mKey.setHint(has ? "•••••••• saved — type to replace" : (mPreset.needsKey ? "Paste API key" : "Optional"));
        mKeyNote.setText(has
            ? "Key saved and encrypted."
            : (mPreset.needsKey ? "Stored encrypted on this device." : "Optional for this provider."));
    }

    private void save(boolean toast) {
        if (mBase == null) return;
        AiConfig.saveProvider(this, mPreset, mBase.getText().toString(), mModel.getText().toString());
        String key = mKey.getText().toString().trim();
        if (!key.isEmpty()) {
            boolean persisted = AiConfig.saveApiKey(this, mPreset, key);
            mKey.setText("");
            refreshKeyNote();
            if (!persisted) {
                Toast.makeText(this, "Secure storage is unavailable; the key is kept only until the app is closed.",
                    Toast.LENGTH_LONG).show();
            }
        }
        AiConfig.setActive(this, mPreset);
        AiConfig.setAutoRun(this, mAutoRun.isChecked());
        int steps = 12;
        try {
            steps = Integer.parseInt(mSteps.getText().toString().trim());
        } catch (NumberFormatException ignored) {
            // Keep the default.
        }
        AiConfig.setMaxSteps(this, steps);
        AiConfig.setSystemPrompt(this, mPrompt.getText().toString());
        if (toast) Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
    }

    /** Settings built from the fields as typed (so Test and Fetch work before saving). */
    @NonNull
    private AiClient.Settings currentSettings() {
        String typed = mKey.getText().toString().trim();
        String key = typed.isEmpty() ? AiConfig.getApiKey(this, mPreset) : typed;
        return new AiClient.Settings(mPreset.provider, mBase.getText().toString().trim(), key,
            mModel.getText().toString().trim());
    }

    /** @return an error to show, or null if the connection details look usable. */
    private String validate(boolean needModel) {
        AiClient.Settings s = currentSettings();
        if (s.baseUrl.isEmpty()) return "Enter a base URL.";
        if (!s.baseUrl.startsWith("http://") && !s.baseUrl.startsWith("https://")) return "The base URL must start with https://";
        if (s.baseUrl.startsWith("http://")) {
            String host = Uri.parse(s.baseUrl).getHost();
            boolean local = host != null && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("10.0.2.2"));
            if (!local) return "Plain http:// is only allowed for localhost (127.0.0.1). Use https:// for remote servers.";
        }
        if (mPreset.needsKey && s.apiKey.isEmpty()) return "Paste your API key first.";
        if (needModel && s.model.isEmpty()) return "Enter a model name (or tap Fetch models).";
        return null;
    }

    private void testConnection() {
        String problem = validate(true);
        if (problem != null) {
            showResult(problem, true);
            return;
        }
        final AiClient.Settings s = currentSettings();
        showResult("Testing…", false);
        new Thread(() -> {
            String msg;
            boolean error = false;
            try {
                List<AiMessage> h = new ArrayList<>();
                h.add(AiMessage.user("Reply with the single word: ok"));
                AiMessage reply = AiClient.chat(s, null, h, Collections.<AiTool>emptyList());
                String t = reply.text.trim();
                msg = "Connected. The model replied: " + (t.length() > 80 ? t.substring(0, 80) + "…" : t);
            } catch (AiClient.AiException e) {
                msg = e.getMessage();
                error = true;
            }
            final String m = msg;
            final boolean err = error;
            runOnUiThread(() -> {
                if (!isDestroyed()) showResult(m, err);
            });
        }, "cremux-ai-test").start();
    }

    private void fetchModels() {
        String problem = validate(false);
        if (problem != null) {
            showResult(problem, true);
            return;
        }
        final AiClient.Settings s = currentSettings();
        showResult("Fetching models…", false);
        new Thread(() -> {
            List<String> models = null;
            String error = null;
            try {
                models = AiClient.listModels(s);
            } catch (AiClient.AiException e) {
                error = e.getMessage();
            }
            final List<String> list = models;
            final String err = error;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (err != null) {
                    showResult(err, true);
                    return;
                }
                if (list == null || list.isEmpty()) {
                    showResult("The provider returned no models. Type the model name by hand.", true);
                    return;
                }
                showResult(list.size() + " models found.", false);
                final String[] items = list.toArray(new String[0]);
                Picker.show(this, "Pick a model", items,
                    java.util.Arrays.asList(items).indexOf(mModel.getText().toString().trim()),
                    which -> mModel.setText(items[which]));
            });
        }, "cremux-ai-models").start();
    }

    private void showResult(@NonNull String text, boolean error) {
        mResult.setText(text);
        mResult.setTextColor(UiColors.attr(this, error ? R.attr.tx_red : R.attr.tx_text_2));
    }

    // ----------------------------------------------------------------- widgets

    private TextView label(@NonNull String text) {
        TextView tv = new TextView(this);
        tv.setText(text.toUpperCase(Locale.US));
        tv.setTextSize(12);
        tv.setLetterSpacing(0.08f);
        tv.setTextColor(UiColors.attr(this, R.attr.tx_text_2));
        tv.setPadding(dp(4), dp(22), 0, dp(8));
        return tv;
    }

    private TextView small(@NonNull String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setLineSpacing(dp(2), 1f);
        tv.setTextColor(UiColors.attr(this, R.attr.tx_text_2));
        tv.setPadding(dp(4), dp(8), dp(4), 0);
        return tv;
    }

    private TextView link(@NonNull String text, @NonNull View.OnClickListener l) {
        TextView tv = small(text);
        tv.setTextSize(13);
        tv.setTextColor(UiColors.attr(this, R.attr.tx_text));
        tv.setPadding(dp(4), dp(10), dp(4), dp(4));
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setOnClickListener(l);
        return tv;
    }

    private EditText edit(@NonNull String hint, int inputType, boolean multiline) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(inputType);
        e.setTextSize(15);
        e.setTextColor(UiColors.attr(this, R.attr.tx_text));
        e.setHintTextColor(UiColors.attr(this, R.attr.tx_text_2));
        e.setBackgroundResource(multiline ? R.drawable.bg_card : R.drawable.bg_search);
        e.setPadding(dp(16), dp(12), dp(16), dp(12));
        e.setMinHeight(dp(48));
        // No setSingleLine(): the input type already decides it, and calling it would drop password masking.
        if (Build.VERSION.SDK_INT >= 26) e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        return e;
    }

    private TextView button(@NonNull String text, boolean primary, @NonNull View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(15);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);
        if (primary) {
            b.setBackgroundResource(R.drawable.bg_btn_primary);
            b.setTextColor(ContextCompat.getColor(this, R.color.tx_on_accent));
        } else {
            b.setBackgroundResource(R.drawable.ripple_card);
            b.setTextColor(UiColors.attr(this, R.attr.tx_text));
        }
        b.setOnClickListener(l);
        return b;
    }
}
