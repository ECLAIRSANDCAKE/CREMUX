package com.termux.app.ai;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;

import com.termux.R;
import com.termux.app.ui.RedesignActivity;
import com.termux.app.ui.UiColors;
import com.termux.app.ui.Picker;
import com.termux.app.ui.UiPrefs;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pick a file, tick the tools, watch them run, then zip the result at the compression level and part size you choose.
 * Everything here is point-and-tap; the same tools remain available to the AI Assistant and the terminal.
 */
public class DecompileActivity extends RedesignActivity {

    private static final int REQ_PICK = 4201;
    private static final String PREFS = "cremux_decompile";
    private static final int[] PART_MB_CHOICES = {0, 10, 25, 30, 50, 100};
    private static final int[] LEVELS = {9, 6, 1, 0};
    private static final String[] LEVEL_LABELS = {"Maximum", "Normal", "Fast", "Store only"};
    private static final int MAX_LOG_LINES = 400;

    private LinearLayout mBody;
    private UiKit.InfoRow mFileRow;
    private ImageView mDeleteBtn;
    private TextView mToolNote;
    private TextView mInstallLink;
    private SwitchCompat mAutoAi;
    private TextView mRunHint;
    private View mZipActions;
    private UiKit.ActionButton mShareBtn, mFolderBtn;
    private List<File> mLastParts = new ArrayList<>();
    private UiKit.ActionButton mRebuildBtn;
    private UiKit.ActionButton mRunBtn;
    private UiKit.ActionButton mStopBtn;
    private TextView mStatus;
    private ProgressBar mRunProgress;
    private ScrollView mLogScroll;
    private TextView mLog;
    private LinearLayout mResultBox;
    private TextView mSummary;
    private UiKit.ActionButton mZipBtn;
    private ProgressBar mZipProgress;
    private TextView mZipResult;
    private TextView mJoinLink;
    private EditText mPartMb;
    private SwitchCompat mToDownloads;

    private final Map<DecompileTools, UiKit.Tile> mToolChips = new EnumMap<>(DecompileTools.class);
    private UiKit.Segmented mLevelSeg;
    private final List<TextView> mSizeChips = new ArrayList<>();
    private final Set<DecompileTools> mSelected = EnumSet.noneOf(DecompileTools.class);

    private File mInput;
    private int mLevel = 9;
    private String mLastJoin;

    private final AtomicBoolean mCancel = new AtomicBoolean(false);
    private volatile boolean mBusy;
    private volatile int mScanGen;
    private volatile String mStepText = "";
    private volatile long mStartedAt;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> mLines = new ArrayDeque<>();
    private final AtomicBoolean mLogScheduled = new AtomicBoolean(false);

    private final Runnable mLogRefresh = new Runnable() {
        @Override
        public void run() {
            mLogScheduled.set(false);
            View content = mLogScroll.getChildAt(0);
            final boolean atEnd = content == null
                || content.getBottom() - (mLogScroll.getHeight() + mLogScroll.getScrollY()) < dp(48);
            StringBuilder sb = new StringBuilder();
            synchronized (mLines) {
                for (String l : mLines) sb.append(l).append('\n');
            }
            mLog.setText(sb);
            if (atEnd) mLogScroll.post(() -> mLogScroll.fullScroll(View.FOCUS_DOWN));
        }
    };

    private final Runnable mTicker = new Runnable() {
        @Override
        public void run() {
            if (!mBusy) return;
            long s = (System.currentTimeMillis() - mStartedAt) / 1000;
            mStatus.setText(mStepText + " · " + String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60));
            mHandler.postDelayed(this, 1000);
        }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_decompile);
        setupHeader("REAPER", null);
        mBody = findViewById(R.id.dc_body);
        build();
        refreshChips();
        if (!handleIncoming(getIntent())) restoreLast();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncoming(intent);
    }

    /** A file shared or opened from another app: copy it into ~/re and select it. Returns true if there was one. */
    private boolean handleIncoming(@Nullable Intent intent) {
        if (intent == null) return false;
        String action = intent.getAction();
        Uri uri = null;
        if (Intent.ACTION_SEND.equals(action)) uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        else if (Intent.ACTION_VIEW.equals(action)) uri = intent.getData();
        // Only content:// URIs: a file:// URI from another app could point at this app's private files.
        if (uri == null || !"content".equals(uri.getScheme())) return false;
        intent.setAction(null); // do not re-import on rotation / resume
        importUri(uri);
        return true;
    }

    /** Re-selects the last file and tool choice so a repeat run is one tap. */
    private void restoreLast() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String name = sp.getString("last_file", null);
        if (name == null || name.contains("/")) return;
        File f = new File(FileImport.reDir(), name);
        if (!f.isFile()) return;
        String saved = sp.getString("last_tools", null); // read before setInput() overwrites it
        setInput(f);
        if (saved == null) return;
        mSelected.clear();
        for (String id : saved.split(",")) {
            for (DecompileTools t : DecompileTools.values()) if (t.name().equals(id) && available(t)) mSelected.add(t);
        }
        refreshChips();
    }

    private void rememberChoice() {
        StringBuilder sb = new StringBuilder();
        for (DecompileTools t : mSelected) sb.append(sb.length() == 0 ? "" : ",").append(t.name());
        SharedPreferences.Editor ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        if (mInput != null) ed.putString("last_file", mInput.getName());
        ed.putString("last_tools", sb.toString()).apply();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshChips(); // tools may have been installed from the terminal in the meantime
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) mCancel.set(true);
        mHandler.removeCallbacksAndMessages(null);
    }

    // -------------------------------------------------------------------- build

    private void build() {
        final SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);

        // ---- 1 · File
        LinearLayout fileCard = UiKit.step(this, 1, "File", null);
        mFileRow = UiKit.infoRow(this, R.drawable.ic_file);
        mFileRow.title.setText("No file chosen yet");
        mFileRow.sub.setText("APK, DEX, JAR or binary");
        LinearLayout.LayoutParams frp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        frp.topMargin = dp(16);
        fileCard.addView(mFileRow.view, frp);

        // Bin button in the card header: deletes this file's decompiled output and returns the page to its default state.
        LinearLayout fileHead = (LinearLayout) fileCard.getChildAt(0);
        mDeleteBtn = new ImageView(this);
        mDeleteBtn.setImageResource(R.drawable.ic_bin);
        mDeleteBtn.setColorFilter(UiColors.attr(this, R.attr.tx_text));
        mDeleteBtn.setBackgroundResource(R.drawable.bg_icon_tile);
        mDeleteBtn.setPadding(dp(10), dp(10), dp(10), dp(10));
        mDeleteBtn.setContentDescription("Delete decompiled output");
        mDeleteBtn.setOnClickListener(v -> confirmDelete());
        mDeleteBtn.setVisibility(View.GONE);
        fileHead.addView(mDeleteBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout pickRow = new LinearLayout(this);
        pickRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(46), 1f);
        half.topMargin = dp(14);
        half.setMarginEnd(dp(8));
        pickRow.addView(new UiKit.ActionButton(this, "Choose file", R.drawable.ic_folder_outline, false, v -> pickFile()), half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        half2.topMargin = dp(14);
        pickRow.addView(new UiKit.ActionButton(this, "From ~/re", R.drawable.ic_list, false, v -> chooseFromRe()), half2);
        fileCard.addView(pickRow);
        mBody.addView(fileCard, UiKit.cardParams(this));

        // ---- 2 · Tools
        LinearLayout toolCard = UiKit.step(this, 2, "Tools", null);
        DecompileTools[] all = DecompileTools.values();
        for (int i = 0; i < all.length; i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int k = 0; k < 2; k++) {
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                if (k == 0) cp.setMarginEnd(dp(5));
                else cp.setMarginStart(dp(5));
                if (i + k < all.length) {
                    final DecompileTools t = all[i + k];
                    UiKit.Tile tile = new UiKit.Tile(this, toolIcon(t), t.label, t.produces, v -> onToolChip(t));
                    row.addView(tile, cp);
                    mToolChips.put(t, tile);
                } else {
                    row.addView(new View(this), cp); // keeps the last tile half width
                }
            }
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rp.topMargin = dp(i == 0 ? 16 : 10);
            toolCard.addView(row, rp);
        }
        mToolNote = UiKit.small(this, "");
        toolCard.addView(mToolNote);
        mInstallLink = UiKit.link(this, "Install the missing tools…", v -> ReToolkit.promptInstall(this, this::refreshChips));
        toolCard.addView(mInstallLink);
        mBody.addView(toolCard, UiKit.cardParams(this));

        // ---- run
        mRunBtn = new UiKit.ActionButton(this, "Decompile", R.drawable.ic_play, true, v -> startDecompile());
        LinearLayout.LayoutParams runLp = UiKit.fullWidth(this, 54, 18);
        mBody.addView(mRunBtn, runLp);
        mRunHint = UiKit.small(this, "");
        mRunHint.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        mBody.addView(mRunHint);
        mAutoAi = new SwitchCompat(this);
        mAutoAi.setText("Open the AI chat when finished");
        mAutoAi.setTextColor(UiColors.attr(this, R.attr.tx_text_2));
        mAutoAi.setTextSize(14);
        mAutoAi.setChecked(sp.getBoolean("auto_ai", false));
        mAutoAi.setOnCheckedChangeListener((b, on) ->
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("auto_ai", on).apply());
        LinearLayout.LayoutParams aap = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        aap.topMargin = dp(10);
        mBody.addView(mAutoAi, aap);
        mStopBtn = new UiKit.ActionButton(this, "Stop", R.drawable.ic_stop, false, v -> stop());
        mStopBtn.setVisibility(View.GONE);
        mBody.addView(mStopBtn, UiKit.fullWidth(this, 46, 8));

        mRunProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mRunProgress.setIndeterminate(true);
        mRunProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4));
        pp.topMargin = dp(14);
        mBody.addView(mRunProgress, pp);
        mStatus = UiKit.small(this, "");
        mBody.addView(mStatus);

        mLog = new TextView(this);
        mLog.setTypeface(Typeface.MONOSPACE);
        mLog.setTextSize(11);
        mLog.setTextColor(UiColors.attr(this, R.attr.tx_text_2));
        mLog.setPadding(dp(14), dp(12), dp(14), dp(12));
        mLog.setTextIsSelectable(true);
        mLogScroll = new UiKit.NestedScroll(this);
        mLogScroll.setBackgroundResource(R.drawable.bg_step_card);
        mLogScroll.setVisibility(View.GONE);
        mLogScroll.addView(mLog);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220));
        lp.topMargin = dp(8);
        mBody.addView(mLogScroll, lp);

        // ---- result + zip (shown once there is output for the chosen file)
        mResultBox = new LinearLayout(this);
        mResultBox.setOrientation(LinearLayout.VERTICAL);
        mResultBox.setVisibility(View.GONE);

        LinearLayout resultCard = UiKit.step(this, 3, "Result", null);
        mSummary = new TextView(this);
        mSummary.setTextSize(13);
        mSummary.setTypeface(Typeface.MONOSPACE);
        mSummary.setLineSpacing(dp(2), 1f);
        mSummary.setTextColor(UiColors.attr(this, R.attr.tx_text));
        mSummary.setBackgroundResource(R.drawable.bg_icon_tile);
        mSummary.setPadding(dp(14), dp(12), dp(14), dp(12));
        mSummary.setTextIsSelectable(true);
        LinearLayout.LayoutParams sump = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sump.topMargin = dp(14);
        resultCard.addView(mSummary, sump);
        resultCard.addView(new UiKit.ActionButton(this, "Ask the AI about this result", R.drawable.ic_brand, false,
            v -> askAi()), UiKit.fullWidth(this, 46, 12));
        mRebuildBtn = new UiKit.ActionButton(this, "Edit, then Rebuild & sign", R.drawable.ic_wrench, false, v -> {
            Intent i = new Intent(this, RebuildActivity.class);
            i.putExtra(RebuildActivity.EXTRA_FOLDER, new File(outRoot(), DecompileTools.APKTOOL.subdir).getPath());
            startActivity(i);
        });
        mRebuildBtn.setVisibility(View.GONE);
        resultCard.addView(mRebuildBtn, UiKit.fullWidth(this, 46, 8));
        mResultBox.addView(resultCard, UiKit.cardParams(this));

        LinearLayout zipCard = UiKit.step(this, 4, "Zip the result", null);
        zipCard.addView(UiKit.small(this, "Compression"));
        mLevel = sp.getInt("level", 9);
        String[] levelLabels = LEVEL_LABELS;
        mLevelSeg = UiKit.segmented(this, levelLabels, i -> {
            mLevel = LEVELS[i];
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("level", mLevel).apply();
            refreshLevelChips();
        });
        LinearLayout.LayoutParams segp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        segp.topMargin = dp(8);
        zipCard.addView(mLevelSeg.view, segp);

        TextView splitLabel = UiKit.small(this, "Split into parts of (MB)");
        splitLabel.setPadding(dp(4), dp(16), dp(4), 0);
        zipCard.addView(splitLabel);
        HorizontalScrollView shs = new HorizontalScrollView(this);
        shs.setHorizontalScrollBarEnabled(false);
        LinearLayout sizeRow = new LinearLayout(this);
        sizeRow.setOrientation(LinearLayout.HORIZONTAL);
        for (final int mb : PART_MB_CHOICES) {
            TextView c = UiKit.chip(this, mb == 0 ? "No split" : mb + " MB", v -> mPartMb.setText(String.valueOf(mb)));
            c.setTag(mb);
            sizeRow.addView(c, UiKit.chipParams(this));
            mSizeChips.add(c);
        }
        shs.addView(sizeRow);
        LinearLayout.LayoutParams shp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        shp.topMargin = dp(8);
        zipCard.addView(shs, shp);
        mPartMb = UiKit.edit(this, "Custom size in MB (0 = one file)", InputType.TYPE_CLASS_NUMBER);
        mPartMb.setText(String.valueOf(sp.getInt("part_mb", 30)));
        mPartMb.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override
            public void afterTextChanged(Editable s) {
                refreshSizeChips();
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("part_mb", parseMb()).apply();
            }
        });
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ep.topMargin = dp(4);
        zipCard.addView(mPartMb, ep);

        mToDownloads = new SwitchCompat(this);
        mToDownloads.setText("Also copy to Downloads/Cremux");
        mToDownloads.setTextColor(UiColors.attr(this, R.attr.tx_text));
        mToDownloads.setTextSize(15);
        mToDownloads.setChecked(sp.getBoolean("downloads", true));
        mToDownloads.setOnCheckedChangeListener((b, on) ->
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("downloads", on).apply());
        LinearLayout.LayoutParams sw = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sw.topMargin = dp(16);
        zipCard.addView(mToDownloads, sw);

        mZipBtn = new UiKit.ActionButton(this, "Create zip", R.drawable.ic_package, true, v -> startZip());
        zipCard.addView(mZipBtn, UiKit.fullWidth(this, 52, 16));
        mZipProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mZipProgress.setMax(100);
        mZipProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams zp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4));
        zp.topMargin = dp(14);
        zipCard.addView(mZipProgress, zp);
        mZipResult = new TextView(this);
        mZipResult.setTextSize(13);
        mZipResult.setTextColor(UiColors.attr(this, R.attr.tx_text));
        mZipResult.setPadding(dp(4), dp(8), dp(4), 0);
        mZipResult.setTextIsSelectable(true);
        zipCard.addView(mZipResult);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams a1 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        a1.topMargin = dp(12);
        a1.setMarginEnd(dp(8));
        mShareBtn = new UiKit.ActionButton(this, "Share", R.drawable.ic_share, false,
            v -> ShareKit.share(this, mLastParts, "application/zip"));
        actions.addView(mShareBtn, a1);
        LinearLayout.LayoutParams a2 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        a2.topMargin = dp(12);
        mFolderBtn = new UiKit.ActionButton(this, "Open folder", R.drawable.ic_folder_outline, false,
            v -> ShareKit.openDownloads(this));
        actions.addView(mFolderBtn, a2);
        actions.setVisibility(View.GONE);
        mZipActions = actions;
        zipCard.addView(actions);
        mJoinLink = UiKit.link(this, "Copy the command that joins the parts", v -> copyJoin());
        mJoinLink.setVisibility(View.GONE);
        zipCard.addView(mJoinLink);
        mResultBox.addView(zipCard, UiKit.cardParams(this));

        mBody.addView(mResultBox);
        refreshLevelChips();
        refreshSizeChips();
    }

    @DrawableRes
    private static int toolIcon(@NonNull DecompileTools t) {
        switch (t) {
            case JADX: return R.drawable.ic_code;
            case APKTOOL: return R.drawable.ic_package;
            case GHIDRA: return R.drawable.ic_cube;
            case RADARE2: return R.drawable.ic_search;
            case NATIVE: return R.drawable.ic_list;
            default: return R.drawable.ic_bolt;
        }
    }

    // ------------------------------------------------------------------ chips

    private boolean available(@NonNull DecompileTools t) {
        for (String exe : t.requires) {
            if (!new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, exe).canExecute()) return false;
        }
        return true;
    }

    private void refreshChips() {
        boolean anyMissing = false;
        StringBuilder note = new StringBuilder();
        for (DecompileTools t : DecompileTools.values()) {
            UiKit.Tile tile = mToolChips.get(t);
            boolean ok = available(t);
            if (!ok) {
                anyMissing = true;
                mSelected.remove(t);
            }
            tile.setInstalled(ok);
            tile.setSelected(mSelected.contains(t));
        }
        mToolNote.setText(mSelected.isEmpty() ? "Tap a tool to select it" : "");
        mToolNote.setVisibility(mSelected.isEmpty() ? View.VISIBLE : View.GONE);
        mInstallLink.setVisibility(anyMissing ? View.VISIBLE : View.GONE);
        refreshRunState();
    }

    /** Enables Decompile only when it can run, and says what is missing instead of failing after the tap. */
    private void refreshRunState() {
        if (mRunBtn == null || mRunHint == null) return;
        String why = null;
        if (mInput == null || !mInput.isFile()) why = "Choose a file to enable Decompile";
        else if (mSelected.isEmpty()) why = "Tap at least one ready tool to enable Decompile";
        mRunBtn.setEnabled(!mBusy && why == null);
        mRunHint.setText(mBusy || why == null ? "" : why);
        mRunHint.setVisibility(mBusy || why == null ? View.GONE : View.VISIBLE);
    }

    private void onToolChip(@NonNull DecompileTools t) {
        if (mBusy) return;
        if (!available(t)) {
            UiPrefs.show(this, new AlertDialog.Builder(this)
                .setTitle(t.label + " is not installed")
                .setMessage("Install it now? It runs right here with a progress bar; you do not need to open the terminal.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Install", (d, w) ->
                    ReToolkit.installInline(this, t == DecompileTools.GHIDRA ? "ghidra" : "core", this::refreshChips)));
            return;
        }
        if (!mSelected.add(t)) mSelected.remove(t);
        refreshChips();
        rememberChoice();
    }

    private void refreshLevelChips() {
        int index = 0;
        for (int i = 0; i < LEVELS.length; i++) if (LEVELS[i] == mLevel) index = i;
        mLevelSeg.select(index);
    }

    /** Part size in MB: empty means the default (30), 0 means one file, huge numbers are capped. */
    private int parseMb() {
        String t = mPartMb.getText().toString().trim();
        if (t.isEmpty()) return 30;
        if (!t.matches("\\d+")) return 30;
        if (t.length() > 4) return 4096;
        return Math.min(4096, Integer.parseInt(t));
    }

    private void refreshSizeChips() {
        int mb = parseMb();
        for (TextView c : mSizeChips) c.setSelected(((Integer) c.getTag()) == mb);
    }

    // ------------------------------------------------------------------ input

    private void pickFile() {
        if (mBusy) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        importUri(data.getData());
    }

    private void importUri(@NonNull final Uri uri) {
        Toast.makeText(this, "Copying file…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            File f = null;
            String err = null;
            try {
                f = FileImport.copyToHome(getApplicationContext(), uri);
            } catch (IOException | RuntimeException e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final File file = f;
            final String error = err;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (error != null) Toast.makeText(this, "Could not copy the file: " + error, Toast.LENGTH_LONG).show();
                else setInput(file);
            });
        }, "cremux-decompile-import").start();
    }

    private void chooseFromRe() {
        if (mBusy) return;
        File[] all = FileImport.reDir().listFiles();
        final List<File> files = new ArrayList<>();
        if (all != null) for (File f : all) if (f.isFile()) files.add(f);
        if (files.isEmpty()) {
            Toast.makeText(this, "~/re is empty. Use Choose file… first.", Toast.LENGTH_LONG).show();
            return;
        }
        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        final List<File> shown = files.size() > 100 ? files.subList(0, 100) : files;
        String[] labels = new String[shown.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = shown.get(i).getName() + "  ·  " + UiKit.size(shown.get(i).length());
        Picker.show(this, "Files in ~/re", labels, -1, which -> setInput(shown.get(which)));
    }

    private void setInput(@NonNull File f) {
        if (mBusy) {
            Toast.makeText(this, "Finish or stop the current job first.", Toast.LENGTH_SHORT).show();
            return;
        }
        mInput = f;
        mFileRow.title.setText(f.getName());
        String smart = ApkScan.scan(f).summary();
        mFileRow.sub.setText(UiKit.size(f.length()) + "  ·  ~/re/" + f.getName() + (smart.isEmpty() ? "" : "\n" + smart));
        mSelected.clear();
        for (DecompileTools t : DecompileTools.suggest(f)) if (available(t)) mSelected.add(t);
        refreshChips();
        rememberChoice();
        mDeleteBtn.setVisibility(View.VISIBLE);
        mLogScroll.setVisibility(View.GONE);
        mStatus.setText("");
        mZipResult.setText("");
        mJoinLink.setVisibility(View.GONE);
        refreshResult();
    }

    /** Asks before deleting the decompiled output of the chosen file. */
    private void confirmDelete() {
        if (mInput == null) return;
        if (mBusy) {
            Toast.makeText(this, "Finish or stop the current job first.", Toast.LENGTH_SHORT).show();
            return;
        }
        final File root = outRoot();
        UiPrefs.show(this, new AlertDialog.Builder(this)
            .setTitle("Delete decompiled output?")
            .setMessage("Removes ~/re/out/" + DecompileTools.baseName(mInput) + "/ (everything the tools produced, including "
                + "any edits you made there) and resets this page. The original file stays in ~/re.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete", (d, w) -> deleteOutput(root)));
    }

    private void deleteOutput(@NonNull final File root) {
        mScanGen++; // drop any scan still running for this folder
        new Thread(() -> {
            deleteTree(root, new File(FileImport.reDir(), "out"));
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                resetPage();
                Toast.makeText(this, "Decompiled output deleted.", Toast.LENGTH_SHORT).show();
            });
        }, "cremux-decompile-delete").start();
    }

    /** Puts the page back to how it looks on first open: no file, no output, no log. */
    private void resetPage() {
        mInput = null;
        mSelected.clear();
        mFileRow.title.setText("No file chosen yet");
        mFileRow.sub.setText("Pick one below");
        mDeleteBtn.setVisibility(View.GONE);
        synchronized (mLines) {
            mLines.clear();
        }
        mLog.setText("");
        mLogScroll.setVisibility(View.GONE);
        mStatus.setText("");
        mZipResult.setText("");
        mZipProgress.setVisibility(View.GONE);
        mJoinLink.setVisibility(View.GONE);
        mResultBox.setVisibility(View.GONE);
        mRebuildBtn.setVisibility(View.GONE);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove("last_file").apply();
        refreshChips();
    }

    // --------------------------------------------------------------- decompile

    private File outRoot() {
        return new File(FileImport.reDir(), "out/" + DecompileTools.baseName(mInput));
    }

    private void setBusy(boolean busy, boolean zipping) {
        mBusy = busy;
        mZipBtn.setEnabled(!busy);
        refreshRunState();
        mStopBtn.setVisibility(busy ? View.VISIBLE : View.GONE);
        mRunProgress.setVisibility(busy && !zipping ? View.VISIBLE : View.GONE);
        if (busy) {
            mStartedAt = System.currentTimeMillis();
            mHandler.removeCallbacks(mTicker);
            mHandler.postDelayed(mTicker, 1000);
        }
    }

    private void stop() {
        if (!mBusy) return;
        mCancel.set(true);
        mStopBtn.setEnabled(false);
        mStepText = "Stopping";
        mStatus.setText("Stopping…");
    }

    private void startDecompile() {
        if (mBusy) return;
        if (mInput == null || !mInput.isFile()) {
            Toast.makeText(this, "Choose a file first.", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<DecompileTools> tools = DecompileTools.ordered(mSelected);
        if (tools.isEmpty()) {
            Toast.makeText(this, "Pick at least one tool.", Toast.LENGTH_SHORT).show();
            return;
        }
        final File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        String warning = preflight(mInput, tools, home);
        if (warning == null) {
            runDecompile(tools);
            return;
        }
        UiPrefs.show(this, new AlertDialog.Builder(this)
            .setTitle("Before you start")
            .setMessage(warning)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Run anyway", (d, w) -> runDecompile(tools)));
    }

    /** Returns a plain-language warning, or null when nothing looks wrong. */
    @Nullable
    private String preflight(@NonNull File input, @NonNull List<DecompileTools> tools, @NonNull File home) {
        StringBuilder sb = new StringBuilder();
        long size = input.length();
        long free = home.getUsableSpace();
        // Decompiled output is commonly several times the input; Ghidra's project is larger still.
        long need = Math.max(200L << 20, size * (tools.contains(DecompileTools.GHIDRA) ? 12 : 6));
        if (free > 0 && free < need) {
            sb.append("Free storage is ").append(UiKit.size(free)).append(", and this run may need about ")
                .append(UiKit.size(need)).append(". It may stop half way.\n\n");
        }
        if (size > (150L << 20) && (tools.contains(DecompileTools.JADX) || tools.contains(DecompileTools.GHIDRA))) {
            sb.append(input.getName()).append(" is ").append(UiKit.size(size))
                .append(". Large inputs can take a long time and run out of memory on a phone.\n\n");
        }
        return sb.length() == 0 ? null : sb.toString().trim();
    }

    private void runDecompile(@NonNull final List<DecompileTools> tools) {
        if (mBusy) return;
        final File input = mInput;
        final File root = outRoot();
        final File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        final Context app = getApplicationContext();

        mCancel.set(false);
        mStopBtn.setEnabled(true);
        synchronized (mLines) {
            mLines.clear();
        }
        mLog.setText("");
        mLogScroll.setVisibility(View.VISIBLE);
        mZipResult.setText("");
        mJoinLink.setVisibility(View.GONE);
        mZipActions.setVisibility(View.GONE);
        mStepText = "Starting";
        setBusy(true, false);

        new Thread(() -> {
            int idx = 0;
            boolean cancelled = false;
            ApkScan scanned = ApkScan.scan(input);
            if (scanned.valid) {
                String sum = scanned.summary();
                if (!sum.isEmpty()) log("[smart] " + sum);
                for (String advice : scanned.advice()) log("[smart] " + advice);
                if (scanned.nativeLibs > 0 && !tools.contains(DecompileTools.NATIVE) && available(DecompileTools.NATIVE)) {
                    log("[smart] tip: tick \"Native libs\" to analyse the .so files too.");
                }
            }
            for (DecompileTools t : tools) {
                if (mCancel.get()) {
                    cancelled = true;
                    break;
                }
                idx++;
                mStepText = "Step " + idx + "/" + tools.size() + " · " + t.label;
                File sub = new File(root, t.subdir);
                deleteTree(sub, root);
                log("== " + t.label + " ==");
                int rc;
                try {
                    rc = LiveShell.run(app, t.script, home, this::log, mCancel, input.getPath(), sub.getPath());
                } catch (IOException | RuntimeException e) {
                    log("! " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                    rc = -1;
                }
                if (rc == LiveShell.CANCELLED) {
                    cancelled = true;
                    log("-> stopped");
                    break;
                }
                long[] sc = scan(sub);
                String verdict = rc == 0 ? "done" : (sc[0] > 0 ? "finished with warnings (exit " + rc + ")" : "failed (exit " + rc + ")");
                log("-> " + t.label + ": " + verdict + " · " + sc[0] + " files · " + UiKit.size(sc[1]));
            }
            final boolean wasCancelled = cancelled;
            final long[] total = scan(root);
            final File in = mInput;
            if (!wasCancelled && in != null) {
                DoneAlert.notify(this, total[0] > 0 ? "Decompile finished" : "Decompile finished with no output",
                    in.getName() + (total[0] > 0 ? " · " + total[0] + " files" : ""), DecompileActivity.class);
            }
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                setBusy(false, false);
                mHandler.removeCallbacks(mTicker);
                mStatus.setText(wasCancelled ? "Stopped." : (total[0] > 0 ? "Finished." : "Finished, but no output was produced. See the log."));
                refreshResult();
                if (!wasCancelled && total[0] > 0 && mAutoAi.isChecked()) askAi();
            });
        }, "cremux-decompile").start();
    }

    private void log(@NonNull String line) {
        synchronized (mLines) {
            mLines.addLast(line);
            while (mLines.size() > MAX_LOG_LINES) mLines.removeFirst();
        }
        if (mLogScheduled.compareAndSet(false, true)) mHandler.postDelayed(mLogRefresh, 200);
    }

    /** Shows or hides the result/zip section depending on whether the chosen file has output. */
    private void refreshResult() {
        if (mInput == null) {
            mResultBox.setVisibility(View.GONE);
            return;
        }
        final File root = outRoot();
        final int gen = ++mScanGen;
        new Thread(() -> {
            final long[] sc = scan(root);
            runOnUiThread(() -> {
                if (isDestroyed() || gen != mScanGen) return; // a newer scan superseded this one
                if (sc[0] == 0) {
                    mResultBox.setVisibility(View.GONE);
                    return;
                }
                mResultBox.setVisibility(View.VISIBLE);
                mRebuildBtn.setVisibility(new File(root, DecompileTools.APKTOOL.subdir + "/apktool.yml").isFile()
                    ? View.VISIBLE : View.GONE);
                StringBuilder sb = new StringBuilder();
                sb.append(sc[0]).append(" files · ").append(UiKit.size(sc[1])).append("\n~/re/out/")
                    .append(DecompileTools.baseName(mInput)).append("/");
                File[] subs = root.listFiles();
                if (subs != null) {
                    Arrays.sort(subs);
                    for (File s : subs) if (s.isDirectory()) sb.append("\n   ").append(s.getName()).append("/");
                }
                mSummary.setText(sb);
            });
        }, "cremux-decompile-scan").start();
    }

    private void askAi() {
        if (mInput == null) return;
        String base = DecompileTools.baseName(mInput);
        Intent i = new Intent(this, AiChatActivity.class);
        i.putExtra(AiChatActivity.EXTRA_PROMPT, "I decompiled ~/re/" + mInput.getName() + ". The results are in ~/re/out/" + base
            + "/ (one subfolder per tool). Look at the structure first, then summarize what this app or binary does and "
            + "point out anything suspicious: permissions, network endpoints, hard-coded secrets, crypto, obfuscation. "
            + "Do not print huge files; use grep and head.");
        startActivity(i);
    }

    // --------------------------------------------------------------------- zip

    private void startZip() {
        if (mBusy || mInput == null) return;
        final File root = outRoot();
        if (!root.isDirectory()) {
            Toast.makeText(this, "Nothing to zip yet. Run a tool first.", Toast.LENGTH_SHORT).show();
            return;
        }
        final int level = mLevel;
        final int mb = parseMb();
        final long partBytes = mb * 1024L * 1024L;
        final boolean toDownloads = mToDownloads.isChecked();
        final String base = DecompileTools.baseName(mInput);
        final File zipDir = new File(FileImport.reDir(), "zips");

        if (toDownloads && !FileImport.hasStorageAccess(this)) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 4202);
            Toast.makeText(this, "Allow storage access, then tap Create zip again.", Toast.LENGTH_LONG).show();
            return;
        }

        mCancel.set(false);
        mStopBtn.setEnabled(true);
        mStepText = "Zipping";
        mZipResult.setText("");
        mJoinLink.setVisibility(View.GONE);
        mZipProgress.setProgress(0);
        mZipProgress.setVisibility(View.VISIBLE);
        mStatus.setText("Zipping…");
        setBusy(true, true);

        new Thread(() -> {
            String message;
            String join = null;
            List<File> parts = new ArrayList<>();
            try {
                final int[] lastPct = {-1};
                ZipSplitter.Result r = ZipSplitter.zipFolder(root, zipDir, base, level, partBytes, (done, total, cur) -> {
                    int pct = total <= 0 ? 100 : (int) (done * 100 / total);
                    if (pct != lastPct[0]) {
                        lastPct[0] = pct;
                        runOnUiThread(() -> {
                            mZipProgress.setProgress(pct);
                            mStepText = "Zipping " + pct + "%  " + cur;
                            mStatus.setText(mStepText);
                        });
                    }
                }, mCancel);

                StringBuilder sb = new StringBuilder();
                long saved = r.inputBytes <= 0 ? 0 : 100 - (r.outputBytes * 100 / r.inputBytes);
                sb.append(r.parts.size() == 1 ? "Created 1 file, " : "Created " + r.parts.size() + " parts, ")
                    .append(UiKit.size(r.outputBytes)).append(" from ").append(UiKit.size(r.inputBytes))
                    .append(" (").append(Math.max(0, saved)).append("% smaller, ").append(r.fileCount).append(" files)\n");
                for (File p : r.parts) sb.append("\n~/re/zips/").append(p.getName()).append("  ·  ").append(UiKit.size(p.length()));
                if (toDownloads) {
                    try {
                        File dl = copyToDownloads(r.parts, base);
                        sb.append("\n\nCopied to ").append(dl.getPath().replace(Environment.getExternalStorageDirectory().getPath(), "Internal storage"));
                    } catch (IOException e) {
                        sb.append("\n\nCould not copy to Downloads: ").append(e.getMessage())
                            .append("\nThe zip is still in ~/re/zips. Allow Storage permission for this app in Android settings "
                                + "(or run termux-setup-storage in the terminal), then zip again.");
                    }
                }
                parts.addAll(r.parts);
                if (r.parts.size() > 1) join = "cat -- " + base + ".zip.* > " + base + ".zip";
                message = sb.toString();
            } catch (ZipSplitter.CancelledException e) {
                message = "Zipping stopped.";
            } catch (IOException | RuntimeException e) {
                message = "Zip failed: " + (e.getMessage() == null ? e.toString() : e.getMessage());
            }
            final String msg = message;
            final String joinCmd = join;
            final List<File> partsDone = parts;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                setBusy(false, true);
                mHandler.removeCallbacks(mTicker);
                mZipProgress.setVisibility(View.GONE);
                mStatus.setText("");
                mZipResult.setText(msg);
                mLastJoin = joinCmd;
                mLastParts = partsDone;
                mZipActions.setVisibility(partsDone.isEmpty() ? View.GONE : View.VISIBLE);
                mFolderBtn.setVisibility(toDownloads ? View.VISIBLE : View.GONE);
                mJoinLink.setVisibility(joinCmd == null ? View.GONE : View.VISIBLE);
            });
        }, "cremux-decompile-zip").start();
    }

    @NonNull
    private File copyToDownloads(@NonNull List<File> parts, @NonNull String base) throws IOException {
        File dl = FileImport.downloadsDir();
        if (!dl.isDirectory() && !dl.mkdirs()) throw new IOException("the Downloads folder is not writable");
        // Remove an earlier run's files first, otherwise old .00N parts would be glued into the new archive.
        File[] old = dl.listFiles();
        if (old != null) {
            String zip = base + ".zip";
            for (File f : old) {
                String n = f.getName();
                if (n.equals(zip) || (n.startsWith(zip + ".") && n.substring(zip.length() + 1).matches("\\d{3,}"))) f.delete();
            }
        }
        for (File p : parts) FileImport.copyToDownloads(p);
        return dl;
    }

    private void copyJoin() {
        if (mLastJoin == null) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("join zip parts", mLastJoin));
        Toast.makeText(this, "Copied: " + mLastJoin, Toast.LENGTH_LONG).show();
    }

    // ----------------------------------------------------------------- helpers

    /** @return {file count, total bytes} below {@code dir}, not following symbolic links. */
    @NonNull
    private static long[] scan(@NonNull File dir) {
        long[] out = {0, 0};
        scanInto(dir, out, 0);
        return out;
    }

    private static void scanInto(File dir, long[] out, int depth) {
        File[] kids = dir.listFiles();
        if (kids == null || depth > 60) return;
        for (File k : kids) {
            if (isLink(k)) continue;
            if (k.isDirectory()) {
                scanInto(k, out, depth + 1);
            } else if (k.isFile()) {
                out[0]++;
                out[1] += k.length();
            }
        }
    }

    private static boolean isLink(@NonNull File f) {
        try {
            File parent = f.getParentFile();
            return parent != null && !f.getCanonicalPath().equals(new File(parent.getCanonicalFile(), f.getName()).getPath());
        } catch (IOException e) {
            return true;
        }
    }

    /** Deletes {@code dir} only if it really lies inside {@code root}; never follows links. */
    private static void deleteTree(@NonNull File dir, @NonNull File root) {
        try {
            if (!dir.exists()) return;
            String r = root.getCanonicalPath() + File.separator;
            if (!dir.getCanonicalPath().startsWith(r) && !isLink(dir)) return;
            deleteInner(dir, 0);
        } catch (IOException ignored) {
            // Leave it alone if the path cannot be resolved.
        }
    }

    private static void deleteInner(File f, int depth) {
        if (!isLink(f) && f.isDirectory() && depth < 60) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteInner(k, depth + 1);
        }
        f.delete();
    }
}
