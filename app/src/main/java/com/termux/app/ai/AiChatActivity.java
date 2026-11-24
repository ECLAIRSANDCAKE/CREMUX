package com.termux.app.ai;

import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.ui.Picker;
import com.termux.app.ui.RedesignActivity;
import com.termux.app.ui.UiColors;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
import io.noties.markwon.core.MarkwonTheme;

/** Chat with the configured model, which can run commands in the Termux environment (with your approval). */
public class AiChatActivity extends RedesignActivity {

    /** Text to pre-fill in the input box (it is not sent automatically). */
    public static final String EXTRA_PROMPT = "com.termux.app.ai.extra.prompt";

    private static final int REQ_ATTACH = 4101;

    private static final class Card {
        final TextView header;
        final TextView result;
        final View dot;
        boolean expanded;

        Card(TextView header, TextView result, View dot) {
            this.header = header;
            this.result = result;
            this.dot = dot;
        }
    }

    private LinearLayout mList;
    private ScrollView mScroll;
    private TextView mEmpty;
    private View mEmptyState;
    private TextView mEmptyCta;
    private View mSuggestions;
    private TextView mProvider;
    private EditText mInput;
    private ImageButton mSend;
    private View mStatusRow;
    private TextView mStatus;
    private Markwon mMarkwon;

    private final Map<String, Card> mCards = new HashMap<>();
    /** The screen currently showing the chat; the agent thread talks to it through {@link #ui}. */
    private static AiChatActivity sActive;
    private static volatile long sStartedAt;
    private Picker.Sheet mSheet;
    private AiChatState.Pending mShownPending;
    private java.io.File mAttached;
    private LinearLayout mChipRow;
    private View mChipBar;
    /** Dismisses the open approval sheet and denies the call; null when none is showing. */
    private Runnable mDenyPending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_chat);
        setupHeader("AI Assistant", null);

        // The header has a search slot; use it for settings, plus an overflow menu for the rest.
        ImageButton gear = findViewById(R.id.btn_header_search);
        gear.setVisibility(View.VISIBLE);
        gear.setImageResource(R.drawable.ic_settings);
        gear.setContentDescription("AI settings");
        gear.setOnClickListener(v -> startActivity(new Intent(this, AiSettingsActivity.class)));
        ImageButton more = findViewById(R.id.btn_header_more);
        more.setVisibility(View.VISIBLE);
        more.setOnClickListener(v -> showMoreSheet());

        mMarkwon = Markwon.builder(this).usePlugin(new AbstractMarkwonPlugin() {
            @Override
            public void configureTheme(@NonNull MarkwonTheme.Builder builder) {
                int surface = UiColors.attr(AiChatActivity.this, R.attr.tx_surface_2);
                int text = UiColors.attr(AiChatActivity.this, R.attr.tx_text);
                builder.codeBackgroundColor(surface).codeBlockBackgroundColor(surface)
                    .codeTextColor(text).codeBlockTextColor(text)
                    .linkColor(text);
            }
        }).build();
        mList = findViewById(R.id.chat_list);
        mScroll = findViewById(R.id.chat_scroll);
        mEmpty = findViewById(R.id.chat_empty);
        mEmptyState = findViewById(R.id.chat_empty_state);
        mEmptyCta = findViewById(R.id.chat_empty_cta);
        mSuggestions = findViewById(R.id.chat_suggestions);
        mProvider = findViewById(R.id.chat_provider);
        mInput = findViewById(R.id.chat_input);
        mSend = findViewById(R.id.chat_send);
        mStatusRow = findViewById(R.id.chat_status_row);
        mStatus = findViewById(R.id.chat_status);

        mSend.setOnClickListener(v -> {
            if (AiChatState.running) {
                stop();
                return;
            }
            String text = mInput.getText().toString().trim();
            if (!text.isEmpty()) send(text);
        });
        findViewById(R.id.chat_attach).setOnClickListener(v -> pickFile());
        mEmptyCta.setOnClickListener(v -> startActivity(new Intent(this, AiSettingsActivity.class)));
        findViewById(R.id.sug_attach).setOnClickListener(v -> pickFile());
        findViewById(R.id.sug_check).setOnClickListener(v -> checkTools());
        findViewById(R.id.sug_install).setOnClickListener(v -> ReToolkit.promptInstall(this));

        buildAttachBar();
        AiChatStore.init(getApplicationContext());
        AiChatStore.loadOnce();
        sActive = this;
        renderHistory();
        if (AiChatState.running) {
            // The screen was recreated (theme change) while the assistant was working: keep going.
            setBusy(true);
            setStatus(AiChatState.pending != null ? "Waiting for your approval…" : "Working…");
        }
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateEmpty();
        showPending();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sActive == this) sActive = null;
        if (mSheet != null) {
            mSheet.dismiss(); // no answer: a new screen asks again
            mSheet = null;
        }
        mShownPending = null;
        mDenyPending = null;
        if (isFinishing()) {
            // Leaving the chat ends the run. A recreate (theme change) does not.
            AiChatState.cancel.set(true);
            AiChatState.Pending p = AiChatState.pending;
            if (p != null) p.decide(false);
            Thread w = AiChatState.worker;
            if (w != null) w.interrupt();
            AiClient.abortActive();
        }
    }

    private void handleIntent(@Nullable Intent intent) {
        if (intent == null) return;
        String prompt = intent.getStringExtra(EXTRA_PROMPT);
        if (prompt != null && !prompt.isEmpty()) {
            mInput.setText(prompt);
            mInput.setSelection(mInput.getText().length());
            mInput.requestFocus();
            intent.removeExtra(EXTRA_PROMPT);
        }
    }

    // ------------------------------------------------------------------ actions

    private void send(@NonNull String text) {
        if (AiChatState.running) {
            Toast.makeText(this, "Wait for the current answer, or press Stop.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!AiConfig.isConfigured(this)) {
            Picker.show(this, "Set up an AI provider",
                "Choose a provider and add your API key first. You can switch providers any time.",
                new String[]{"Open settings", "Later"}, -1, which -> {
                    if (which == 0) startActivity(new Intent(this, AiSettingsActivity.class));
                });
            return;
        }
        if (mAttached != null && !text.contains(mAttached.getName())) {
            text = text + "\n\n(File: ~/re/" + mAttached.getName() + ")";
        }
        clearAttachment();
        AiChatState.HISTORY.add(AiMessage.user(text));
        AiChatStore.saveAsync();
        mInput.setText("");
        updateEmpty();
        addUser(text);
        startAgent();
    }

    private void checkTools() {
        send("Check which reverse-engineering tools are installed here (radare2/r2, jadx, apktool, readelf/objdump, "
            + "gdb, re-decompile for Ghidra) and tell me what is missing and how to install it.");
    }

    private void showMoreSheet() {
        final String[] labels = {"Attach file", "Decompile (GUI)", "Install RE tools", "Rebuild & sign APK", "What's installed?", "New chat"};
        Picker.show(this, "AI Assistant", labels, -1, which -> {
            switch (which) {
                case 0: pickFile(); break;
                case 1: startActivity(new Intent(this, DecompileActivity.class)); break;
                case 2: ReToolkit.promptInstall(this); break;
                case 3: startActivity(new Intent(this, RebuildActivity.class)); break;
                case 4: checkTools(); break;
                default: newChat();
            }
        });
    }

    private void stop() {
        if (!AiChatState.running) return;
        AiChatState.cancel.set(true);
        setStatus("Stopping…");
        AiClient.abortActive();
        // The worker may be parked on an approval sheet; answer "deny" so it can see the cancel flag.
        Runnable deny = mDenyPending;
        if (deny != null) deny.run();
        else if (AiChatState.pending != null) AiChatState.pending.decide(false);
    }

    private void newChat() {
        if (AiChatState.running) {
            Toast.makeText(this, "Press Stop first.", Toast.LENGTH_SHORT).show();
            return;
        }
        AiChatState.clear();
        AiChatStore.clear();
        clearAttachment();
        renderHistory();
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_ATTACH);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ATTACH && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importFile(data.getData());
        }
    }

    /** Copy the picked file into ~/re so commands can reach it, then offer one-tap questions about it. */
    private void importFile(@NonNull final Uri uri) {
        Toast.makeText(this, "Copying file…", Toast.LENGTH_SHORT).show();
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            String error = null;
            String name = null;
            try {
                name = FileImport.copyToHome(app, uri).getName();
            } catch (IOException | RuntimeException e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final String fname = name;
            final String err = error;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (err != null) {
                    Toast.makeText(this, "Could not copy the file: " + err, Toast.LENGTH_LONG).show();
                    return;
                }
                attach(new java.io.File(FileImport.reDir(), fname));
            });
        }, "cremux-ai-import").start();
    }

    // ------------------------------------------------------------------- agent

    private interface UiTask {
        void run(@NonNull AiChatActivity a);
    }

    /** Runs {@code t} on whichever screen is showing the chat now; does nothing while none is. */
    private static void ui(@NonNull final UiTask t) {
        final AiChatActivity a = sActive;
        if (a == null) return;
        a.runOnUiThread(() -> {
            if (sActive == a && !a.isDestroyed()) t.run(a);
        });
    }

    private void startAgent() {
        AiChatState.running = true;
        sStartedAt = System.currentTimeMillis();
        AiChatState.cancel.set(false);
        setBusy(true);
        setStatus("Thinking…");

        final AiClient.Settings settings = AiConfig.toSettings(this);
        final String system = AiConfig.getSystemPrompt(this);
        final int maxSteps = AiConfig.getMaxSteps(this);
        final AiTools tools = new AiTools(getApplicationContext(), AiConfig.isAutoRun(this));
        final AiAgent.Host host = new ChatHost();

        AiChatState.worker = new Thread(() -> AiAgent.run(settings, system, AiChatState.HISTORY, tools, host, maxSteps,
            AiChatState.cancel), "cremux-ai-agent");
        AiChatState.worker.start();
    }

    /** Bridges the agent thread to whichever screen is current, so a recreated screen does not lose the run. */
    private static final class ChatHost implements AiAgent.Host {
        @Override
        public void onAssistantMessage(@NonNull final AiMessage message) {
            AiChatStore.saveAsync();
            ui(a -> {
                if (!message.text.trim().isEmpty()) a.addAssistant(message.text);
                for (AiMessage.ToolCall c : message.toolCalls) a.addCard(c, null);
                if (!message.toolCalls.isEmpty()) a.setStatus("Running tools…");
                a.scrollToEnd();
            });
        }

        @Override
        public boolean confirm(@NonNull final AiMessage.ToolCall call) throws InterruptedException {
            final boolean risky = AiTools.isRisky(call);
            if (AiChatState.allowAll && !risky) return true;
            if (AiChatState.allowReadOnly && !risky && AiTools.isReadOnly(call)) return true;
            final AiChatState.Pending p = new AiChatState.Pending(call, risky);
            AiChatState.pending = p;
            // If no screen is showing right now, the next one to resume shows it (see showPending()).
            ui(AiChatActivity::showPending);
            try {
                p.latch.await();
            } finally {
                if (AiChatState.pending == p) AiChatState.pending = null;
            }
            return p.answer;
        }

        @Override
        public void onToolResult(@NonNull final AiMessage.ToolCall call, @NonNull final AiMessage result) {
            AiChatStore.saveAsync();
            ui(a -> {
                a.fillCard(call.id, result);
                a.setStatus("Thinking…");
                a.scrollToEnd();
            });
        }

        @Override
        public void onFinished(@Nullable final String error) {
            AiChatState.running = false;
            AiChatState.worker = null;
            AiChatStore.saveAsync();
            // Only long runs are worth an alert; a quick answer arrives while you are looking at it.
            if (error == null && System.currentTimeMillis() - sStartedAt > 15000 && sActive != null) {
                DoneAlert.notify(sActive, "Assistant finished", "Your answer is ready.", AiChatActivity.class);
            }
            ui(a -> {
                a.setBusy(false);
                if (error != null) a.addNote("Cancelled".equals(error) ? "Stopped." : error, !"Cancelled".equals(error));
                a.scrollToEnd();
            });
        }
    }

    /** Shows the approval sheet for the waiting command, once, on this screen. */
    private void showPending() {
        final AiChatState.Pending p = AiChatState.pending;
        if (p == null || mShownPending == p || isFinishing() || isDestroyed()) return;
        mShownPending = p;
        setStatus("Waiting for your approval…");
        askConfirm(p.call, p.risky, allow -> {
            p.decide(allow);
            runOnUiThread(() -> setStatus(allow ? "Running command…" : "Thinking…"));
        });
    }

    /** One plain sentence for what the call does, shown above the raw command. */
    @NonNull
    private static String plain(@NonNull AiMessage.ToolCall c) {
        if (AiTools.RUN_COMMAND.equals(c.name)) return AiSafety.summarize(c.args.optString("command", ""));
        if (AiTools.WRITE_FILE.equals(c.name)) return "Writes a file";
        if (AiTools.EDIT_FILE.equals(c.name)) return "Edits a file";
        if (AiTools.REBUILD_APK.equals(c.name)) return "Rebuilds and signs an APK";
        if (AiTools.READ_FILE.equals(c.name)) return "Reads a file";
        if (AiTools.SEARCH.equals(c.name)) return "Searches files";
        return "Runs a tool";
    }

    private interface Decision {
        void decide(boolean allow);
    }

    private void askConfirm(@NonNull final AiMessage.ToolCall call, boolean risky, @NonNull final Decision decision) {
        final Picker.Sheet sheet = Picker.sheet(this, risky ? "Risky command" : "Run this command?",
            risky ? "Deletes, overwrites, sends data out, or touches credentials." : "The assistant wants to run this in your terminal.");
        mSheet = sheet;
        final boolean[] answered = {false};

        TextView what = new TextView(this);
        what.setText(plain(call));
        what.setTextSize(16);
        what.setTypeface(Typeface.DEFAULT_BOLD);
        what.setTextColor(UiColors.attr(this, risky ? R.attr.tx_red : R.attr.tx_text));
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wp.setMargins(dp(20), dp(4), dp(20), dp(10));
        sheet.body.addView(what, wp);

        TextView body = new TextView(this);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextSize(13);
        body.setTextIsSelectable(true);
        body.setLineSpacing(dp(2), 1f);
        body.setPadding(dp(14), dp(12), dp(14), dp(12));
        body.setTextColor(UiColors.attr(this, R.attr.tx_text));
        body.setBackground(rounded(UiColors.attr(this, R.attr.tx_surface_2),
            UiColors.attr(this, risky ? R.attr.tx_red : R.attr.tx_stroke), 14));
        body.setText(describe(call));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.setMargins(dp(20), 0, dp(20), dp(16));
        sheet.body.addView(body, bp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(48), 1f);
        half.setMarginStart(dp(4));
        half.setMarginEnd(dp(4));
        buttons.addView(UiKit.button(this, "Deny", false, v -> {
            answered[0] = true;
            sheet.dismiss();
            decision.decide(false);
        }), half);
        buttons.addView(UiKit.button(this, "Run", true, v -> {
            answered[0] = true;
            sheet.dismiss();
            decision.decide(true);
        }), half);
        LinearLayout.LayoutParams brow = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        brow.setMargins(dp(16), 0, dp(16), 0);
        sheet.body.addView(buttons, brow);

        if (!risky) {
            if (AiTools.isReadOnly(call)) {
                TextView ro = UiKit.link(this, "Always allow read-only commands in this chat", v -> {
                    answered[0] = true;
                    AiChatState.allowReadOnly = true;
                    sheet.dismiss();
                    decision.decide(true);
                });
                ro.setGravity(Gravity.CENTER);
                ro.setPadding(dp(4), dp(16), dp(4), dp(4));
                sheet.body.addView(ro);
            }
            TextView all = UiKit.link(this, "Allow all commands in this chat", v -> {
                answered[0] = true;
                AiChatState.allowAll = true;
                sheet.dismiss();
                decision.decide(true);
            });
            all.setGravity(Gravity.CENTER);
            all.setPadding(dp(4), dp(8), dp(4), dp(4));
            sheet.body.addView(all);
        }

        sheet.dialog.setCancelable(true);
        sheet.dialog.setOnCancelListener(d -> {
            mDenyPending = null;
            if (!answered[0]) {
                answered[0] = true;
                decision.decide(false);
            }
        });
        sheet.dialog.setOnDismissListener(d -> {
            mDenyPending = null;
            if (mSheet == sheet) mSheet = null;
        });
        mDenyPending = () -> {
            if (answered[0]) return;
            answered[0] = true;
            mDenyPending = null;
            sheet.dismiss();
            decision.decide(false);
        };
        sheet.show();
    }

    // ------------------------------------------------------------ attachments

    private void buildAttachBar() {
        View composer = (View) mInput.getParent().getParent();
        ViewGroup root = (ViewGroup) composer.getParent();
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setPadding(dp(12), dp(4), dp(12), dp(4));
        hs.setClipToPadding(false);
        mChipRow = new LinearLayout(this);
        mChipRow.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(mChipRow);
        hs.setVisibility(View.GONE);
        root.addView(hs, root.indexOfChild(composer));
        mChipBar = hs;
    }

    private void attach(@NonNull java.io.File f) {
        mAttached = f;
        mChipRow.removeAllViews();
        addChip(f.getName() + "  ·  " + UiKit.size(f.length()) + "  ✕".replace("✕", "×"), v -> clearAttachment());
        for (final String[] q : questionsFor(f)) {
            addChip(q[0], v -> {
                String prompt = q[1];
                if (!AiChatState.running) send(prompt);
            });
        }
        mChipBar.setVisibility(View.VISIBLE);
        mInput.requestFocus();
    }

    private void addChip(@NonNull String label, @NonNull View.OnClickListener l) {
        TextView c = UiKit.chip(this, label, l);
        mChipRow.addView(c, UiKit.chipParams(this));
    }

    private void clearAttachment() {
        mAttached = null;
        if (mChipRow != null) mChipRow.removeAllViews();
        if (mChipBar != null) mChipBar.setVisibility(View.GONE);
    }

    /** One-tap questions that fit the file type: {label, prompt}. */
    @NonNull
    private static java.util.List<String[]> questionsFor(@NonNull java.io.File f) {
        String name = f.getName();
        String ref = "~/re/" + name;
        String lower = name.toLowerCase(java.util.Locale.US);
        java.util.List<String[]> q = new java.util.ArrayList<>();
        q.add(new String[]{"Quick triage", "Analyze " + ref + ": what is it, and what does it do? Start with a quick triage."});
        if (lower.endsWith(".apk") || lower.endsWith(".xapk") || lower.endsWith(".aab")) {
            q.add(new String[]{"Manifest & exports", "For " + ref + ": decode the manifest and list the exported components, "
                + "the permissions and anything unusual."});
            q.add(new String[]{"Network & secrets", "For " + ref + ": find network endpoints, API keys, tokens and other "
                + "hard-coded secrets. Show where each one is."});
            q.add(new String[]{"Obfuscation?", "Is " + ref + " obfuscated or packed? Look for native libraries, dynamic loading "
                + "and anti-debugging, and explain what you find."});
        } else if (lower.endsWith(".dex") || lower.endsWith(".jar") || lower.endsWith(".aar") || lower.endsWith(".class")) {
            q.add(new String[]{"Main classes", "For " + ref + ": list the main classes and summarize what each one does."});
            q.add(new String[]{"Crypto & secrets", "For " + ref + ": find crypto usage and hard-coded secrets."});
        } else {
            q.add(new String[]{"Strings & imports", "For " + ref + ": show the interesting strings and the imported functions, "
                + "and say what they suggest."});
            q.add(new String[]{"Crypto?", "Does " + ref + " use cryptography? Find the algorithms, keys and where they are used."});
            q.add(new String[]{"Explain main()", "Find the entry point of " + ref + " and explain what it does, step by step."});
        }
        return q;
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setStroke(dp(1), stroke);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    // --------------------------------------------------------------------- UI

    private void setBusy(boolean busy) {
        mStatusRow.setVisibility(busy ? View.VISIBLE : View.GONE);
        mSend.setImageResource(busy ? R.drawable.ic_stop : R.drawable.ic_arrow_up);
        mSend.setContentDescription(busy ? "Stop" : "Send");
    }

    private void setStatus(@NonNull String text) {
        mStatus.setText(text);
    }

    private void updateEmpty() {
        if (!AiChatState.HISTORY.isEmpty()) {
            mEmptyState.setVisibility(View.GONE);
            return;
        }
        mEmptyState.setVisibility(View.VISIBLE);
        if (AiConfig.isConfigured(this)) {
            mEmpty.setVisibility(View.GONE);
            mEmptyCta.setVisibility(View.GONE);
            mSuggestions.setVisibility(View.VISIBLE);
            mProvider.setVisibility(View.VISIBLE);
            mProvider.setText(AiConfig.summary(this));
        } else {
            mEmpty.setVisibility(View.VISIBLE);
            mEmpty.setText("Connect OpenAI, Anthropic, Gemini, OpenRouter or a local Ollama model "
                + "(no key needed) to get started.");
            mEmptyCta.setVisibility(View.VISIBLE);
            mSuggestions.setVisibility(View.GONE);
            mProvider.setVisibility(View.GONE);
        }
    }

    private void renderHistory() {
        mList.removeAllViews();
        mCards.clear();
        for (AiMessage m : AiChatState.HISTORY) {
            if (AiMessage.USER.equals(m.role)) {
                addUser(m.text);
            } else if (AiMessage.ASSISTANT.equals(m.role)) {
                if (!m.text.trim().isEmpty()) addAssistant(m.text);
                for (AiMessage.ToolCall c : m.toolCalls) addCard(c, null);
            } else {
                fillCard(m.toolCallId == null ? "" : m.toolCallId, m);
            }
        }
        updateEmpty();
        scrollToEnd();
    }

    private void scrollToEnd() {
        mScroll.post(() -> mScroll.fullScroll(View.FOCUS_DOWN));
    }

    private LinearLayout.LayoutParams rowParams(int gravity, int marginStart, int marginEnd) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = gravity;
        lp.topMargin = dp(10);
        lp.setMarginStart(marginStart);
        lp.setMarginEnd(marginEnd);
        return lp;
    }

    private void addUser(@NonNull String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setLineSpacing(dp(2), 1f);
        tv.setTextColor(ContextCompat.getColor(this, R.color.tx_on_accent));
        tv.setBackgroundResource(R.drawable.bg_bubble_user);
        tv.setPadding(dp(16), dp(11), dp(16), dp(11));
        tv.setTextIsSelectable(true);
        mList.addView(tv, rowParams(Gravity.END, dp(56), 0));
    }

    private void addAssistant(@NonNull String markdown) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        ImageView avatar = new ImageView(this);
        avatar.setBackgroundResource(R.drawable.bg_avatar);
        avatar.setImageResource(R.drawable.ic_brand);
        avatar.setColorFilter(UiColors.attr(this, R.attr.tx_text));
        avatar.setPadding(dp(5), dp(5), dp(5), dp(5));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(28), dp(28));
        ap.topMargin = dp(2);
        row.addView(avatar, ap);

        TextView tv = new TextView(this);
        tv.setTextSize(15);
        tv.setTextColor(UiColors.attr(this, R.attr.tx_text));
        tv.setLineSpacing(dp(3), 1f);
        mMarkwon.setMarkdown(tv, markdown);
        tv.setTextIsSelectable(true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tp.setMarginStart(dp(10));
        row.addView(tv, tp);

        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(14);
        mList.addView(row, rp);
    }

    private void addNote(@NonNull String text, boolean isError) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(UiColors.attr(this, isError ? R.attr.tx_red : R.attr.tx_text_2));
        tv.setBackground(rounded(UiColors.attr(this, R.attr.tx_surface),
            UiColors.attr(this, isError ? R.attr.tx_red : R.attr.tx_stroke), 14));
        tv.setPadding(dp(14), dp(7), dp(14), dp(7));
        mList.addView(tv, rowParams(Gravity.CENTER_HORIZONTAL, dp(16), dp(16)));
    }

    @NonNull
    private static String clip(@NonNull String s) {
        String t = s.length() > 300 ? s.substring(0, 300) + "…" : s;
        return t.replace("\n", "\n  ");
    }

    @NonNull
    private static String describe(@NonNull AiMessage.ToolCall c) {
        if (AiTools.RUN_COMMAND.equals(c.name)) {
            String wd = c.args.optString("workdir", "");
            return "$ " + c.args.optString("command", "") + (wd.isEmpty() ? "" : "\n(in " + wd + ")");
        }
        if (AiTools.READ_FILE.equals(c.name)) return "read " + c.args.optString("path", "");
        if (AiTools.WRITE_FILE.equals(c.name)) return "write " + c.args.optString("path", "") + " (" + c.args.optString("content", "").length() + " chars)";
        if (AiTools.EDIT_FILE.equals(c.name)) {
            return "edit " + c.args.optString("path", "") + "\n- " + clip(c.args.optString("old_text", "")) + "\n+ " + clip(c.args.optString("new_text", ""));
        }
        if (AiTools.SAVE_NOTE.equals(c.name)) return "note: " + clip(c.args.optString("note", ""));
        if (AiTools.APP_MAP.equals(c.name)) return "map " + c.args.optString("project", "");
        if (AiTools.SEARCH.equals(c.name)) return "search \"" + c.args.optString("pattern", "") + "\" in " + c.args.optString("path", "~");
        if (AiTools.REBUILD_APK.equals(c.name)) return "rebuild & sign " + c.args.optString("project", "");
        return c.name + " " + c.args;
    }

    private void addCard(@NonNull AiMessage.ToolCall call, @Nullable AiMessage result) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.bg_tool_card);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        View dot = new View(this);
        dot.setBackground(dot(UiColors.attr(this, R.attr.tx_orange)));
        top.addView(dot, new LinearLayout.LayoutParams(dp(8), dp(8)));

        final TextView header = new TextView(this);
        header.setTypeface(Typeface.MONOSPACE);
        header.setTextSize(12);
        header.setTextColor(UiColors.attr(this, R.attr.tx_text));
        header.setText(describe(call));
        header.setMaxLines(2);
        header.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        hp.setMarginStart(dp(10));
        top.addView(header, hp);

        final ImageView chevron = new ImageView(this);
        chevron.setImageResource(R.drawable.ic_expand_more);
        chevron.setColorFilter(UiColors.attr(this, R.attr.tx_text_2));
        top.addView(chevron, new LinearLayout.LayoutParams(dp(20), dp(20)));
        box.addView(top);

        final TextView res = new TextView(this);
        res.setTypeface(Typeface.MONOSPACE);
        res.setTextSize(11);
        res.setLineSpacing(dp(1), 1f);
        res.setTextColor(UiColors.attr(this, R.attr.tx_text_2));
        res.setText("Running…");
        res.setMaxLines(4);
        res.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(8);
        box.addView(res, rp);

        final Card card = new Card(header, res, dot);
        box.setOnClickListener(v -> {
            card.expanded = !card.expanded;
            res.setMaxLines(card.expanded ? Integer.MAX_VALUE : 4);
            res.setEllipsize(card.expanded ? null : android.text.TextUtils.TruncateAt.END);
            header.setMaxLines(card.expanded ? Integer.MAX_VALUE : 2);
            chevron.setRotation(card.expanded ? 180f : 0f);
        });
        mCards.put(call.id, card);
        // Full width, indented under the assistant text (avatar width + gap).
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(10);
        cp.setMarginStart(dp(38));
        mList.addView(box, cp);
        if (result != null) fillCard(call.id, result);
    }

    private GradientDrawable dot(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    private void fillCard(@NonNull String callId, @NonNull AiMessage result) {
        Card card = mCards.get(callId);
        if (card == null) return;
        String text = result.text.trim();
        card.result.setText(text.isEmpty() ? "(no output)" : text);
        card.result.setTextColor(UiColors.attr(this, result.isError ? R.attr.tx_red : R.attr.tx_text_2));
        card.dot.setBackground(dot(UiColors.attr(this, result.isError ? R.attr.tx_red : R.attr.tx_green)));
    }
}
