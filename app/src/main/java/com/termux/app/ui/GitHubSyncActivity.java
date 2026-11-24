package com.termux.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import android.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** One-tap "pick files, pick repo, push" screen. Runs real git inside the Termux environment. */
public class GitHubSyncActivity extends RedesignActivity {

    /** Path of a zip (made by the Rebuild screen) to preselect as the source. */
    public static final String EXTRA_SOURCE_ZIP = "com.termux.app.extra.source_zip";

    private static final int REQ_ZIP = 4101;
    private static final int REQ_TREE = 4102;
    private static final int REQ_SAVE = 4103;
    private static final int REQ_SAVE_ARTIFACT = 4104;

    private TextView mAccount, mSource, mRepoText, mModeHint, mLog, mPush;
    private View mLogCard, mProgress;
    private TextView mModeUpdate, mModeFresh;
    private SwitchCompat mUnwrap, mMirror;
    private EditText mMessage;

    private Uri mSourceUri;
    private boolean mSourceIsZip;
    private GitHubApi.Repo mRepo;
    private boolean mRepoIsNew;
    private boolean mFresh;
    private volatile boolean mRunning;

    private final StringBuilder mCommitted = new StringBuilder();
    private String mCurrentLine = "";
    private long mLastRender;
    private String mSecret1, mSecret2;

    // Workflow run section
    private View mWfCard, mWfActions;
    private TextView mWfStatus, mWfDetailTitle, mWfDetail;
    private LinearLayout mWfRuns, mWfArtifacts;
    private GitHubApi.Artifact mPendingArtifact;
    private String mArtifactRepo;
    private volatile int mWatchGen;
    private String mPushedSha;
    private GitHubApi.Run mDetailRun;
    /** Passed runs of the latest listing: the first one is not always the one that uploaded the build output. */
    private final List<GitHubApi.Run> mSuccessRuns = new ArrayList<>();
    private String mDetailKey = "";
    private String mDetailFull = "";
    private String mDetailFileName = "workflow.log";
    private int mDetailLoadGen;
    private volatile String mPendingSha;

    // Build output that was downloaded automatically when a run finished
    private long mAutoRunId = -1;
    private final List<File> mAutoFiles = new ArrayList<>();
    private String mAutoError;
    private static final long AUTO_SAVE_MAX_BYTES = 400L * 1024 * 1024;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_github_sync);
        setupHeader("GitHub Sync", null);

        mAccount = findViewById(R.id.tv_account);
        mSource = findViewById(R.id.tv_source);
        mRepoText = findViewById(R.id.tv_repo);
        mModeHint = findViewById(R.id.tv_mode_hint);
        mLog = findViewById(R.id.tv_log);
        mLogCard = findViewById(R.id.log_card);
        mProgress = findViewById(R.id.progress);
        mModeUpdate = findViewById(R.id.mode_update);
        mModeFresh = findViewById(R.id.mode_fresh);
        mUnwrap = findViewById(R.id.sw_unwrap);
        mMirror = findViewById(R.id.sw_mirror);
        mMessage = findViewById(R.id.et_message);
        mPush = findViewById(R.id.btn_push);
        mUnwrap.setChecked(true);
        mWfCard = findViewById(R.id.wf_card);
        mWfActions = findViewById(R.id.wf_actions);
        mWfStatus = findViewById(R.id.wf_status);
        mWfDetailTitle = findViewById(R.id.wf_detail_title);
        mWfDetail = findViewById(R.id.wf_detail);
        mWfRuns = findViewById(R.id.wf_runs);
        mWfArtifacts = findViewById(R.id.wf_artifacts);
        findViewById(R.id.wf_refresh).setOnClickListener(v -> watchRuns(mPushedSha, false));
        findViewById(R.id.wf_btn_copy).setOnClickListener(v -> copyDetail());
        findViewById(R.id.wf_btn_save).setOnClickListener(v -> saveDetail());
        findViewById(R.id.wf_btn_share).setOnClickListener(v -> shareDetail());
        findViewById(R.id.wf_btn_open).setOnClickListener(v -> {
            if (mDetailRun != null && !mDetailRun.htmlUrl.isEmpty())
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(mDetailRun.htmlUrl)));
        });

        findViewById(R.id.btn_account).setOnClickListener(v -> showCredentialsDialog(null));
        findViewById(R.id.btn_signout).setOnClickListener(v -> {
            GitHubCredentials.clear(this);
            mRepo = null;
            mWatchGen++;
            mWfCard.setVisibility(View.GONE);
            refreshAll();
            toast("Signed out. Token removed from this device.");
        });
        findViewById(R.id.btn_pick_zip).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
            startActivityForResult(i, REQ_ZIP);
        });
        findViewById(R.id.btn_pick_folder).setOnClickListener(v ->
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE));
        findViewById(R.id.btn_repo).setOnClickListener(v -> ensureCredentials(this::loadRepos));
        mModeUpdate.setOnClickListener(v -> setMode(false));
        mModeFresh.setOnClickListener(v -> setMode(true));
        mPush.setOnClickListener(v -> onPushClicked());

        setMode(false);
        refreshAll();

        String preset = getIntent().getStringExtra(EXTRA_SOURCE_ZIP);
        if (preset != null && new java.io.File(preset).isFile()) {
            mSourceUri = Uri.fromFile(new java.io.File(preset));
            mSourceIsZip = true;
            mSource.setText("Zip: " + new java.io.File(preset).getName());
        }
    }

    // ---------- UI state ----------

    private void refreshAll() {
        if (GitHubCredentials.hasCredentials(this)) {
            mAccount.setText("Signed in as " + GitHubCredentials.getUser(this) + " · token stored encrypted");
        } else {
            mAccount.setText("Not connected. Tap Connect to add your username and token.");
        }
        findViewById(R.id.btn_signout).setVisibility(GitHubCredentials.hasCredentials(this) ? View.VISIBLE : View.GONE);
        mRepoText.setText(mRepo == null ? "No repository chosen"
            : mRepo.fullName + (mRepo.isPrivate ? " · private" : " · public") + (mRepoIsNew ? " · new" : ""));
    }

    private void setMode(boolean fresh) {
        mFresh = fresh;
        mModeUpdate.setSelected(!fresh);
        mModeFresh.setSelected(fresh);
        mMirror.setVisibility(fresh ? View.GONE : View.VISIBLE);
        mModeHint.setText(fresh
            ? "Starts a brand new history from your files and force-pushes it, replacing what the repo has."
            : "Adds/overwrites files on top of what is already in the repo.");
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void showError(String title, String msg) {
        UiPrefs.show(this, new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null));
    }

    // ---------- Source ----------

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQ_SAVE) {
            writeDetail(data.getData());
            return;
        }
        if (requestCode == REQ_SAVE_ARTIFACT) {
            downloadArtifactTo(data.getData());
            return;
        }
        mSourceUri = data.getData();
        mSourceIsZip = requestCode == REQ_ZIP;
        if (requestCode == REQ_TREE) {
            try {
                getContentResolver().takePersistableUriPermission(mSourceUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }
        }
        mSource.setText((mSourceIsZip ? "Zip: " : "Folder: ") + SourceStager.displayName(this, mSourceUri));
    }

    // ---------- Credentials ----------

    private void ensureCredentials(Runnable next) {
        if (GitHubCredentials.hasCredentials(this)) next.run();
        else showCredentialsDialog(next);
    }

    private void showCredentialsDialog(Runnable then) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        TextView hint = new TextView(this);
        hint.setText("Create a Personal Access Token at github.com/settings/tokens with the \"repo\" and \"workflow\" scopes "
            + "(workflow lets you push .github/workflows files; repo also covers reading Actions runs). "
            + "It is stored encrypted with the Android Keystore and only sent to github.com.");
        hint.setTextSize(12);
        box.addView(hint);

        final EditText user = new EditText(this);
        user.setHint("GitHub username (optional, detected from token)");
        user.setSingleLine(true);
        String saved = GitHubCredentials.getUser(this);
        if (saved != null) user.setText(saved);
        box.addView(user);

        final EditText token = new EditText(this);
        token.setHint("Personal access token");
        token.setSingleLine(true);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.addView(token);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
            .setTitle("GitHub account")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Verify & save", (d, w) -> {
                String t = token.getText().toString().trim();
                if (t.isEmpty()) {
                    toast("Token is required");
                    return;
                }
                verifyAndSave(user.getText().toString().trim(), t, then);
            });
        UiPrefs.show(this, b);
    }

    private void verifyAndSave(String typedUser, String token, Runnable then) {
        toast("Checking token…");
        new Thread(() -> {
            try {
                String login = GitHubApi.getLogin(token);
                boolean persisted = GitHubCredentials.save(this, login, token);
                runOnUiThread(() -> {
                    refreshAll();
                    if (!typedUser.isEmpty() && !typedUser.equalsIgnoreCase(login)) {
                        toast("Token belongs to " + login + ", using that.");
                    } else if (!persisted) {
                        toast("Saved for this session only (Keystore unavailable).");
                    } else {
                        toast("Connected as " + login);
                    }
                    if (then != null) then.run();
                });
            } catch (Exception e) {
                runOnUiThread(() -> showError("Could not verify token", String.valueOf(e.getMessage())));
            }
        }).start();
    }

    // ---------- Repo ----------

    private void loadRepos() {
        final String token = GitHubCredentials.getToken(this);
        if (token == null) return;
        toast("Loading your repositories…");
        new Thread(() -> {
            try {
                List<GitHubApi.Repo> repos = GitHubApi.listRepos(token);
                runOnUiThread(() -> showRepoPicker(repos));
            } catch (Exception e) {
                runOnUiThread(() -> showError("Could not load repositories", String.valueOf(e.getMessage())));
            }
        }).start();
    }

    private void showRepoPicker(List<GitHubApi.Repo> repos) {
        List<Picker.Entry> entries = new ArrayList<>();
        entries.add(new Picker.Entry("Create new repository", R.drawable.ic_plus, 0));
        for (GitHubApi.Repo r : repos) entries.add(new Picker.Entry(r.fullName, 0, 0));
        Picker.showEntries(this, "Choose repository", entries, which -> {
            if (which == 0) {
                showCreateRepoDialog();
            } else {
                mRepo = repos.get(which - 1);
                mRepoIsNew = false;
                mPushedSha = null;
                refreshAll();
                watchRuns(null, false);
            }
        });
    }

    private void showCreateRepoDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText name = new EditText(this);
        name.setHint("Repository name");
        name.setSingleLine(true);
        box.addView(name);
        final CheckBox priv = new CheckBox(this);
        priv.setText("Private repository");
        priv.setChecked(true);
        box.addView(priv);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
            .setTitle("New repository")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create", (d, w) -> {
                String n = name.getText().toString().trim().replace(' ', '-');
                if (n.isEmpty()) {
                    toast("Name is required");
                    return;
                }
                createRepo(n, priv.isChecked());
            });
        UiPrefs.show(this, b);
    }

    private void createRepo(String name, boolean isPrivate) {
        final String token = GitHubCredentials.getToken(this);
        if (token == null) return;
        toast("Creating " + name + "…");
        new Thread(() -> {
            try {
                GitHubApi.Repo r = GitHubApi.createRepo(token, name, isPrivate);
                runOnUiThread(() -> {
                    mRepo = r;
                    mRepoIsNew = true;
                    mPushedSha = null;
                    mWatchGen++;
                    mWfCard.setVisibility(View.GONE);
                    setMode(true);
                    refreshAll();
                    toast("Created " + r.fullName);
                });
            } catch (Exception e) {
                runOnUiThread(() -> showError("Could not create repository", String.valueOf(e.getMessage())));
            }
        }).start();
    }

    // ---------- Push ----------

    private void onPushClicked() {
        if (mRunning) return;
        if (mSourceUri == null) {
            toast("Pick a zip or folder first");
            return;
        }
        if (!GitHubCredentials.hasCredentials(this)) {
            showCredentialsDialog(this::onPushClicked);
            return;
        }
        if (mRepo == null) {
            toast("Choose or create a repository first");
            return;
        }
        if (mFresh && !mRepoIsNew) {
            UiPrefs.show(this, new AlertDialog.Builder(this)
                .setTitle("Replace repository content?")
                .setMessage("Fresh files force-pushes a new history to " + mRepo.fullName + " (" + mRepo.defaultBranch
                    + "). Existing commits on that branch will be overwritten.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Push", (d, w) -> startPush()));
        } else {
            startPush();
        }
    }

    private void startPush() {
        final String user = GitHubCredentials.getUser(this);
        final String token = GitHubCredentials.getToken(this);
        if (user == null || token == null) return;
        final GitHubApi.Repo repo = mRepo;
        final Uri uri = mSourceUri;
        final boolean isZip = mSourceIsZip;
        final boolean unwrap = mUnwrap.isChecked();
        final boolean fresh = mFresh;
        final boolean mirror = mMirror.isChecked();
        String m = mMessage.getText().toString().trim();
        final String message = m.isEmpty() ? "Update from Crémux" : m;

        mSecret1 = token;
        mSecret2 = Base64.encodeToString((user + ":" + token).getBytes(java.nio.charset.StandardCharsets.UTF_8), Base64.NO_WRAP);
        mRunning = true;
        mPush.setEnabled(false);
        mPush.setAlpha(0.5f);
        mCommitted.setLength(0);
        mCurrentLine = "";
        mPendingSha = null;
        mWatchGen++;
        mLogCard.setVisibility(View.VISIBLE);
        mProgress.setVisibility(View.VISIBLE);
        render(true);
        scrollToLog();

        new Thread(() -> {
            File base = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".cremux_sync");
            int code = -1;
            try {
                File bash = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "bash");
                if (!bash.canExecute()) {
                    throw new IOException("Termux environment is not installed yet. Open Terminal once and let it finish setup.");
                }
                File src = new File(base, "src");
                File work = new File(base, "work");
                line("==> Reading " + (isZip ? "zip" : "folder") + " from your phone");
                int n = isZip ? SourceStager.stageZip(this, uri, src, unwrap) : SourceStager.stageTree(this, uri, src);
                line("Staged " + n + " files");

                String script = readRaw(R.raw.cremux_push);
                ProcessBuilder pb = new ProcessBuilder(bash.getPath(), "-c", script, "cremux",
                    src.getPath(), work.getPath(), repo.fullName,
                    fresh && !mRepoIsNew ? repo.defaultBranch : (fresh ? "main" : repo.defaultBranch),
                    fresh ? "fresh" : "update", message, mirror ? "1" : "0",
                    user, user + "@users.noreply.github.com");
                HashMap<String, String> env = new TermuxShellEnvironment().getEnvironment(this, false);
                pb.environment().clear();
                pb.environment().putAll(env);
                pb.environment().put("GIT_CONFIG_COUNT", "1");
                pb.environment().put("GIT_CONFIG_KEY_0", "http.extraheader");
                pb.environment().put("GIT_CONFIG_VALUE_0", "Authorization: Basic " + mSecret2);
                pb.directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
                pb.redirectErrorStream(true);
                Process p = pb.start();
                p.getOutputStream().close();
                InputStream in = p.getInputStream();
                int c;
                while ((c = in.read()) != -1) feed((char) c);
                flushPartial();
                code = p.waitFor();
            } catch (Exception e) {
                line("ERROR: " + e.getMessage());
            } finally {
                SourceStager.deleteRecursive(new File(base, "work"));
                SourceStager.deleteRecursive(new File(base, "src"));
            }
            final int exit = code;
            runOnUiThread(() -> finishPush(exit, repo));
        }).start();
    }

    private void finishPush(int exit, GitHubApi.Repo repo) {
        mRunning = false;
        mSecret1 = null;
        mSecret2 = null;
        mPush.setEnabled(true);
        mPush.setAlpha(1f);
        mProgress.setVisibility(View.GONE);
        render(true);
        if (exit == 0) {
            UiPrefs.show(this, new AlertDialog.Builder(this)
                .setTitle("Push complete")
                .setMessage(repo.fullName)
                .setNegativeButton("Close", null)
                .setPositiveButton("Open on GitHub", (d, w) ->
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/" + repo.fullName)))));
            GitHubCredentials.setLastPush(this, repo.fullName);
            mRepoIsNew = false;
            refreshAll();
            mPushedSha = mPendingSha;
            watchRuns(mPushedSha, true);
        } else {
            toast("Push failed. See the log below.");
            if (mCommitted.indexOf("workflow") >= 0 && mCommitted.indexOf("scope") >= 0) {
                showError("Token needs the workflow scope",
                    "GitHub refused to create or update files in .github/workflows. Add the \"workflow\" scope to your token and connect again.");
            }
        }
    }

    // ---------- Workflow runs ----------

    /**
     * Shows the Actions runs of the chosen repo. With {@code sha} only runs of that commit are shown and the
     * screen keeps polling until they finish; without it the latest runs of the default branch are shown.
     */
    private void watchRuns(String sha, boolean afterPush) {
        if (afterPush) mAutoRunId = -1;
        final GitHubApi.Repo repo = mRepo;
        final String token = GitHubCredentials.getToken(this);
        if (repo == null || token == null) return;
        final int gen = ++mWatchGen;
        mWfCard.setVisibility(View.VISIBLE);
        mWfStatus.setText(afterPush ? "Waiting for GitHub to start the workflow…" : "Checking workflow runs…");
        new Thread(() -> {
            final long started = System.currentTimeMillis();
            int pause = 4000;
            while (gen == mWatchGen && !isFinishing()) {
                List<GitHubApi.Run> runs;
                String error = null;
                try {
                    runs = GitHubApi.listRuns(token, repo.fullName, sha, sha == null ? repo.defaultBranch : null);
                } catch (Exception e) {
                    runs = new ArrayList<>();
                    error = String.valueOf(e.getMessage());
                }
                if (gen != mWatchGen) return;
                final List<GitHubApi.Run> shown = runs;
                final String err = error;
                final long waited = System.currentTimeMillis() - started;
                runOnUiThread(() -> {
                    if (gen == mWatchGen) renderRuns(repo, shown, err, sha, afterPush, waited);
                });
                if (error != null) return;
                boolean active = false;
                for (GitHubApi.Run r : runs) if (!r.isCompleted()) active = true;
                boolean waitingForFirst = sha != null && runs.isEmpty() && waited < 90000;
                if (!active && !waitingForFirst) return;
                if (waited > 45 * 60 * 1000L) return;
                try { Thread.sleep(pause); } catch (InterruptedException e) { return; }
                if (waited > 120000) pause = 10000;
            }
        }).start();
    }

    private static String stateLabel(GitHubApi.Run r) {
        if (!r.isCompleted()) return "queued".equals(r.status) || "waiting".equals(r.status) || "pending".equals(r.status)
            ? "Queued" : "Running…";
        String c = r.conclusion == null ? "" : r.conclusion;
        switch (c) {
            case "success": return "Passed";
            case "failure": return "Failed";
            case "cancelled": return "Cancelled";
            case "timed_out": return "Timed out";
            case "skipped": return "Skipped";
            case "neutral": return "Neutral";
            case "action_required": return "Action required";
            default: return c.isEmpty() ? "Done" : c;
        }
    }

    private static int dotFor(GitHubApi.Run r) {
        if (!r.isCompleted()) return R.drawable.dot_orange;
        if (r.isSuccess()) return R.drawable.dot_green;
        if (r.isFailure()) return R.drawable.dot_ui_red;
        return R.drawable.dot_ui_grey;
    }

    private void renderRuns(GitHubApi.Repo repo, List<GitHubApi.Run> runs, String error, String sha, boolean afterPush, long waited) {
        mWfRuns.removeAllViews();
        if (error != null) {
            String hint = error.contains("404") || error.contains("403")
                ? "\nThe token needs the \"repo\" scope (or Actions: read for a fine-grained token)." : "";
            mWfStatus.setText("Could not read workflow runs: " + error + hint);
            hideDetail();
            return;
        }
        if (runs.isEmpty()) {
            mWfStatus.setText(sha != null && waited < 90000
                ? "Waiting for GitHub to start the workflow…"
                : sha != null ? "This commit did not trigger any workflow run." : "No workflow runs on " + repo.defaultBranch + " yet.");
            hideDetail();
            if (sha != null && waited >= 90000) GitHubCredentials.setLastRun(this, null);
            return;
        }
        int active = 0, failed = 0, passed = 0;
        mSuccessRuns.clear();
        for (GitHubApi.Run r : runs) {
            if (r.isSuccess()) mSuccessRuns.add(r);
            if (!r.isCompleted()) active++;
            else if (r.isFailure()) failed++;
            else if (r.isSuccess()) passed++;
            mWfRuns.addView(runRow(r));
        }
        String summary;
        if (active > 0) summary = active + (active == 1 ? " run in progress" : " runs in progress");
        else if (failed > 0) summary = failed == 1 ? "Workflow failed" : failed + " workflows failed";
        else if (passed > 0) summary = passed == 1 ? "Workflow passed" : "All " + passed + " workflows passed";
        else summary = "Workflow finished";
        mWfStatus.setText(summary + " · " + repo.fullName + (sha != null ? " · " + sha.substring(0, Math.min(7, sha.length())) : ""));
        if (sha != null) {
            GitHubCredentials.setLastRun(this, active > 0 ? "Running" : failed > 0 ? "Failed" : passed > 0 ? "Passed" : null);
        }

        // Pick what to show below: the first failure wins, otherwise the first success.
        GitHubApi.Run pick = null;
        if (active == 0) {
            for (GitHubApi.Run r : runs) if (r.isFailure()) { pick = r; break; }
            if (pick == null) for (GitHubApi.Run r : runs) if (r.isSuccess()) { pick = r; break; }
        }
        if (mDetailRun != null) {
            // Keep a run the user tapped, as long as it is still listed and finished.
            for (GitHubApi.Run r : runs) if (r.id == mDetailRun.id && r.isCompleted()) { pick = r; break; }
        }
        if (pick == null) hideDetail(); else showDetail(repo, pick, false);
    }

    private View runRow(GitHubApi.Run r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(44));
        row.setBackgroundResource(R.drawable.ripple_borderless);
        row.setClickable(true);
        row.setFocusable(true);

        ImageView dot = new ImageView(this);
        dot.setImageResource(dotFor(r));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(10), dp(10));
        dlp.setMarginEnd(dp(12));
        row.addView(dot, dlp);

        TextView name = new TextView(this);
        name.setText(r.name);
        name.setTextColor(UiColors.attr(this, R.attr.tx_text));
        name.setTextSize(14);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView st = new TextView(this);
        st.setText(stateLabel(r));
        st.setTextColor(UiColors.attr(this, R.attr.tx_text_2));
        st.setTextSize(12);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.setMarginStart(dp(8));
        row.addView(st, slp);

        if (r.isCompleted()) {
            row.setOnClickListener(v -> showDetail(mRepo, r, true));
        } else {
            row.setOnClickListener(v -> { if (!r.htmlUrl.isEmpty()) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(r.htmlUrl))); });
        }
        return row;
    }

    private void hideDetail() {
        mDetailRun = null;
        mDetailKey = "";
        mDetailFull = "";
        mDetailLoadGen++;
        mWfDetailTitle.setVisibility(View.GONE);
        mWfDetail.setVisibility(View.GONE);
        mWfArtifacts.setVisibility(View.GONE);
        mWfActions.setVisibility(View.GONE);
    }

    /** Failure: tail of the failed job's log. Success: the build output (artifacts) the run produced. */
    private void showDetail(GitHubApi.Repo repo, GitHubApi.Run run, boolean force) {
        if (repo == null) return;
        String key = run.id + ":" + run.conclusion;
        if (!force && key.equals(mDetailKey)) return;
        mDetailKey = key;
        mDetailRun = run;
        final int gen = ++mDetailLoadGen;
        final String token = GitHubCredentials.getToken(this);
        if (token == null) return;
        final boolean ok = run.isSuccess();
        mWfDetailTitle.setVisibility(View.VISIBLE);
        mWfDetail.setVisibility(View.VISIBLE);
        mWfActions.setVisibility(View.GONE);
        mWfArtifacts.removeAllViews();
        mWfArtifacts.setVisibility(View.GONE);
        mWfDetailTitle.setText(ok ? "Build output" : "Failure log");
        mWfDetail.setText(ok ? "Looking for build output…" : "Loading log…");
        final List<GitHubApi.Run> others = new ArrayList<>(mSuccessRuns);

        new Thread(() -> {
            String title, body, full, fileName;
            try {
                if (ok) {
                    List<GitHubApi.Artifact> found = GitHubApi.listArtifacts(token, repo.fullName, run.id);
                    GitHubApi.Run source = run;
                    if (!force && !hasUsable(found)) {
                        // Not tapped by the user: another passed run of this commit (the "Build" workflow, while a
                        // Dependabot or update run came first in the list) may be the one that has the output.
                        for (GitHubApi.Run o : others) {
                            if (o.id == run.id) continue;
                            List<GitHubApi.Artifact> oa = GitHubApi.listArtifacts(token, repo.fullName, o.id);
                            if (hasUsable(oa)) { found = oa; source = o; break; }
                        }
                    }
                    final List<GitHubApi.Artifact> arts = found;
                    final GitHubApi.Run shown = source;
                    runOnUiThread(() -> {
                        if (gen != mDetailLoadGen) return;
                        mDetailRun = shown;
                        mDetailKey = shown.id + ":" + shown.conclusion;
                        renderArtifacts(repo, shown, arts);
                    });
                    return;
                } else {
                    GitHubApi.FailureLog log = GitHubApi.getFailureLog(token, repo.fullName, run.id);
                    fileName = run.name.replaceAll("[^A-Za-z0-9._-]+", "-") + "-failure.log";
                    title = "Failure log" + (log.jobName.isEmpty() ? "" : " · " + log.jobName)
                        + (log.failedStep.isEmpty() ? "" : " › " + log.failedStep);
                    StringBuilder sb = new StringBuilder();
                    java.util.LinkedHashSet<String> why = new java.util.LinkedHashSet<>(log.keyLines);
                    why.addAll(log.errors);
                    if (!why.isEmpty()) {
                        sb.append("What went wrong\n");
                        int shownLines = 0;
                        for (String e : why) {
                            if (shownLines++ >= 30) break;
                            sb.append("• ").append(e).append('\n');
                        }
                        sb.append("\nLog around the failure\n");
                    }
                    sb.append(log.tail);
                    full = sb.toString();
                    body = full;
                }
            } catch (Exception e) {
                title = ok ? "Build output" : "Failure log";
                body = "Could not load: " + e.getMessage();
                full = "";
                fileName = "workflow.log";
            }
            final String t = title, b = body, f = full, fn = fileName;
            runOnUiThread(() -> {
                if (gen != mDetailLoadGen) return;
                mWfDetailTitle.setText(t);
                mWfDetail.setText(b);
                mDetailFull = f;
                mDetailFileName = fn;
                mWfActions.setVisibility(View.VISIBLE);
                findViewById(R.id.wf_btn_copy).setVisibility(f.isEmpty() ? View.GONE : View.VISIBLE);
                findViewById(R.id.wf_btn_save).setVisibility(f.isEmpty() ? View.GONE : View.VISIBLE);
                findViewById(R.id.wf_btn_share).setVisibility(f.isEmpty() ? View.GONE : View.VISIBLE);
            });
        }).start();
    }

    private static boolean hasUsable(List<GitHubApi.Artifact> arts) {
        for (GitHubApi.Artifact a : arts) if (!a.expired) return true;
        return false;
    }

    /** ABI an artifact name refers to ("Cremux-arm64-v8a" → arm64-v8a), or null when it names none. */
    private static String abiOf(String name) {
        String n = name.toLowerCase(java.util.Locale.US).replace('_', '-');
        if (n.contains("arm64") || n.contains("v8a")) return "arm64-v8a";
        if (n.contains("armeabi") || n.contains("v7a")) return "armeabi-v7a";
        if (n.contains("x86-64") || n.contains("x64")) return "x86_64";
        if (n.contains("x86")) return "x86";
        return null;
    }

    /**
     * Keeps the builds this device can run, preferring its best ABI (arm64-v8a over armeabi-v7a on a 64-bit phone).
     * Artifacts that name no ABI are kept. When nothing matches the device, everything is kept.
     */
    private static List<GitHubApi.Artifact> forThisDevice(List<GitHubApi.Artifact> arts) {
        java.util.Set<String> present = new java.util.HashSet<>();
        for (GitHubApi.Artifact a : arts) {
            String abi = a.expired ? null : abiOf(a.name);
            if (abi != null) present.add(abi);
        }
        String chosen = null;
        for (String supported : android.os.Build.SUPPORTED_ABIS) {
            if (present.contains(supported)) { chosen = supported; break; }
        }
        if (chosen == null) return arts;
        List<GitHubApi.Artifact> out = new ArrayList<>();
        for (GitHubApi.Artifact a : arts) {
            String abi = abiOf(a.name);
            if (abi == null || abi.equals(chosen)) out.add(a);
        }
        return out;
    }

    private void renderArtifacts(GitHubApi.Repo repo, GitHubApi.Run run, List<GitHubApi.Artifact> arts) {
        mWfArtifacts.removeAllViews();
        mWfActions.setVisibility(View.VISIBLE);
        findViewById(R.id.wf_btn_copy).setVisibility(View.GONE);
        findViewById(R.id.wf_btn_save).setVisibility(View.GONE);
        findViewById(R.id.wf_btn_share).setVisibility(View.GONE);
        mArtifactRepo = repo.fullName;
        int usable = 0;
        for (GitHubApi.Artifact a : arts) {
            if (!a.expired) usable++;
            mWfArtifacts.addView(artifactRow(a));
        }
        if (usable == 0) {
            mWfArtifacts.setVisibility(View.GONE);
            mWfDetail.setVisibility(View.VISIBLE);
            mWfDetail.setText(arts.isEmpty()
                ? "This run passed but uploaded no build output. Add an actions/upload-artifact step to your workflow to get one here."
                : "The build output of this run has expired on GitHub (default retention is 90 days).");
        } else if (mAutoRunId == run.id && mAutoError == null && !mAutoFiles.isEmpty()) {
            showSavedOutput(repo);
        } else if (mAutoRunId != run.id && UiPrefs.getBoolean(this, UiPrefs.KEY_GH_AUTO_DOWNLOAD, false)
            && autoSizeOk(forThisDevice(arts))) {
            autoSaveArtifacts(repo, run, forThisDevice(arts));
        } else {
            mWfDetail.setVisibility(View.GONE);
            mWfArtifacts.setVisibility(View.VISIBLE);
            mWfDetailTitle.setText(mAutoRunId == run.id && mAutoError != null
                ? "Build output · automatic save failed, tap to download"
                : "Build output · tap to download");
        }
    }

    private static boolean autoSizeOk(List<GitHubApi.Artifact> arts) {
        long total = 0;
        for (GitHubApi.Artifact a : arts) if (!a.expired) total += a.size;
        return total > 0 && total <= AUTO_SAVE_MAX_BYTES;
    }

    private static String safeName(String s) {
        String n = s.replaceAll("[^A-Za-z0-9._-]+", "_");
        return n.isEmpty() ? "output" : n;
    }

    /**
     * A run just passed: download its build output into Downloads/Cremux, pull any APK out of the zips and show
     * the files, so nothing has to be picked or saved by hand.
     */
    private void autoSaveArtifacts(final GitHubApi.Repo repo, final GitHubApi.Run run, final List<GitHubApi.Artifact> arts) {
        final String token = GitHubCredentials.getToken(this);
        if (token == null) return;
        mAutoRunId = run.id;
        mAutoError = null;
        mAutoFiles.clear();
        final int gen = mDetailLoadGen;
        long sum = 0;
        for (GitHubApi.Artifact a : arts) if (!a.expired) sum += a.size;
        final long total = sum;
        mWfArtifacts.setVisibility(View.GONE);
        mWfDetail.setVisibility(View.VISIBLE);
        mWfDetailTitle.setText("Build finished · saving the output…");
        mWfDetail.setText("Starting download (" + android.text.format.Formatter.formatShortFileSize(this, total) + ")…");

        new Thread(() -> {
            final List<File> saved = new ArrayList<>();
            String error = null;
            try {
                File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "Cremux");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("the Downloads folder is not writable");
                String shortName = safeName(repo.fullName.substring(repo.fullName.indexOf('/') + 1));
                long before = 0;
                for (final GitHubApi.Artifact a : arts) {
                    if (a.expired) continue;
                    File zip = new File(dir, shortName + "-" + safeName(a.name) + ".zip");
                    final long base = before;
                    final int[] lastPct = {-1};
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(zip);
                    try {
                        GitHubApi.downloadArtifact(token, repo.fullName, a.id, fo, done -> {
                            int pct = (int) Math.min(100, (base + done) * 100 / Math.max(1, total));
                            if (pct != lastPct[0] && gen == mDetailLoadGen) {
                                lastPct[0] = pct;
                                runOnUiThread(() -> {
                                    if (gen == mDetailLoadGen) mWfDetail.setText("Downloading " + a.name + "… " + pct + "%");
                                });
                            }
                        });
                    } finally {
                        fo.close();
                    }
                    before += a.size;
                    saved.addAll(extractPackages(zip, dir));
                    saved.add(zip);
                }
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final String err = error;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                mAutoFiles.clear();
                mAutoFiles.addAll(saved);
                mAutoError = err == null && saved.isEmpty() ? "no files were downloaded" : err;
                if (gen != mDetailLoadGen) return; // another run is shown now; the result is kept for later
                if (mAutoError == null) {
                    showSavedOutput(repo);
                    toast("Build output saved to Downloads/Cremux");
                } else {
                    mWfDetail.setVisibility(View.GONE);
                    mWfArtifacts.setVisibility(View.VISIBLE);
                    mWfDetailTitle.setText("Automatic save failed (" + mAutoError + ") · tap to download");
                }
            });
        }).start();
    }

    /** Copies every .apk / .aab / .apks inside the artifact zip next to it (file names only, never paths). */
    private static List<File> extractPackages(File zip, File dir) throws IOException {
        List<File> out = new ArrayList<>();
        java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(
            new java.io.BufferedInputStream(new java.io.FileInputStream(zip)));
        try {
            java.util.zip.ZipEntry e;
            byte[] buf = new byte[65536];
            long written = 0;
            while ((e = in.getNextEntry()) != null) {
                String n = e.getName();
                String lower = n.toLowerCase(java.util.Locale.US);
                if (e.isDirectory() || !(lower.endsWith(".apk") || lower.endsWith(".aab") || lower.endsWith(".apks"))) continue;
                File target = new File(dir, safeName(n.substring(n.lastIndexOf('/') + 1)));
                java.io.FileOutputStream fo = new java.io.FileOutputStream(target);
                try {
                    int r;
                    while ((r = in.read(buf)) != -1) {
                        written += r;
                        if (written > (2L << 30)) throw new IOException("The downloaded build is larger than 2 GB; stopped.");
                        fo.write(buf, 0, r);
                    }
                } finally {
                    fo.close();
                }
                out.add(target);
            }
        } finally {
            in.close();
        }
        return out;
    }

    private void showSavedOutput(GitHubApi.Repo repo) {
        mWfArtifacts.removeAllViews();
        mWfArtifacts.setVisibility(View.VISIBLE);
        mWfDetail.setVisibility(View.VISIBLE);
        boolean hasApk = false;
        for (File f : mAutoFiles) {
            mWfArtifacts.addView(savedRow(f));
            if (f.getName().toLowerCase(java.util.Locale.US).endsWith(".apk")) hasApk = true;
        }
        mWfDetailTitle.setText("Build output · saved to Downloads/Cremux");
        mWfDetail.setText(hasApk
            ? "Your APK is ready. Tap a file to open the Downloads folder, then tap the APK there to install it."
            : "Tap a file to open the Downloads folder.");
    }

    private View savedRow(File f) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        row.setBackgroundResource(R.drawable.ripple_borderless);
        row.setClickable(true);
        row.setFocusable(true);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_download);
        icon.setColorFilter(UiColors.attr(this, R.attr.tx_text));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(20), dp(20));
        ilp.setMarginEnd(dp(12));
        row.addView(icon, ilp);
        TextView name = new TextView(this);
        name.setText(f.getName());
        name.setTextColor(UiColors.attr(this, R.attr.tx_text));
        name.setTextSize(14);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView size = new TextView(this);
        size.setText(android.text.format.Formatter.formatShortFileSize(this, f.length()));
        size.setTextColor(UiColors.attr(this, R.attr.tx_text_2));
        size.setTextSize(12);
        row.addView(size);
        row.setOnClickListener(v -> {
            try {
                startActivity(new Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                toast("Open the Downloads app and look in the Cremux folder.");
            }
        });
        return row;
    }

    private View artifactRow(GitHubApi.Artifact a) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(44));
        row.setBackgroundResource(R.drawable.ripple_borderless);
        row.setClickable(true);
        row.setFocusable(true);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_download);
        icon.setColorFilter(UiColors.attr(this, R.attr.tx_text));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(20), dp(20));
        ilp.setMarginEnd(dp(12));
        row.addView(icon, ilp);
        TextView name = new TextView(this);
        name.setText(a.name + ".zip");
        name.setTextColor(UiColors.attr(this, R.attr.tx_text));
        name.setTextSize(14);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView size = new TextView(this);
        size.setText(a.expired ? "Expired" : android.text.format.Formatter.formatShortFileSize(this, a.size));
        size.setTextColor(UiColors.attr(this, R.attr.tx_text_2));
        size.setTextSize(12);
        row.addView(size);
        row.setAlpha(a.expired ? 0.5f : 1f);
        if (!a.expired) {
            row.setOnClickListener(v -> {
                mPendingArtifact = a;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/zip");
                i.putExtra(Intent.EXTRA_TITLE, a.name + ".zip");
                startActivityForResult(i, REQ_SAVE_ARTIFACT);
            });
        }
        return row;
    }

    private void downloadArtifactTo(Uri uri) {
        final GitHubApi.Artifact a = mPendingArtifact;
        final String repo = mArtifactRepo;
        final String token = GitHubCredentials.getToken(this);
        if (a == null || repo == null || token == null) return;
        toast("Downloading " + a.name + "…");
        new Thread(() -> {
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new IOException("Could not open the file");
                GitHubApi.downloadArtifact(token, repo, a.id, os, null);
                runOnUiThread(() -> UiPrefs.show(this, new AlertDialog.Builder(this)
                    .setTitle("Build output saved")
                    .setMessage(a.name + ".zip (" + android.text.format.Formatter.formatShortFileSize(this, a.size)
                        + "). Unzip it to get your APK or other files.")
                    .setPositiveButton("OK", null)));
            } catch (Exception e) {
                runOnUiThread(() -> showError("Download failed", String.valueOf(e.getMessage())));
            }
        }).start();
    }

    private void copyDetail() {
        if (mDetailFull.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(mDetailFileName, mDetailFull));
        toast("Copied");
    }

    private void saveDetail() {
        if (mDetailFull.isEmpty()) return;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE, mDetailFileName);
        startActivityForResult(i, REQ_SAVE);
    }

    private void writeDetail(Uri uri) {
        final String text = mDetailFull;
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os == null) throw new IOException("Could not open the file");
            os.write(text.getBytes("UTF-8"));
            toast("Saved " + mDetailFileName);
        } catch (Exception e) {
            showError("Could not save", String.valueOf(e.getMessage()));
        }
    }

    private void shareDetail() {
        if (mDetailFull.isEmpty()) return;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, mDetailFileName);
        i.putExtra(Intent.EXTRA_TEXT, mDetailFull);
        startActivity(Intent.createChooser(i, "Share " + mDetailFileName));
    }

    @Override
    protected void onDestroy() {
        mWatchGen++;
        super.onDestroy();
    }

    // ---------- Log ----------

    private String mask(String s) {
        if (mSecret1 != null && !mSecret1.isEmpty()) s = s.replace(mSecret1, "***");
        if (mSecret2 != null && !mSecret2.isEmpty()) s = s.replace(mSecret2, "***");
        return s;
    }

    private void captureSha(String done) {
        if (done.startsWith("==> Commit ")) mPendingSha = done.substring(11).trim();
    }

    private void line(String s) {
        synchronized (mCommitted) {
            mCommitted.append(mask(s)).append('\n');
            mCurrentLine = "";
        }
        render(false);
    }

    /** Git draws progress with carriage returns: '\r' rewrites the current line, '\n' commits it. */
    private void feed(char c) {
        synchronized (mCommitted) {
            if (c == '\n') {
                captureSha(mCurrentLine);
                mCommitted.append(mask(mCurrentLine)).append('\n');
                mCurrentLine = "";
            } else if (c == '\r') {
                mCurrentLine = "";
            } else if (mCurrentLine.length() < 4000) {
                mCurrentLine += c;
            }
            if (mCommitted.length() > 60000) mCommitted.delete(0, 20000);
        }
        render(false);
    }

    private void flushPartial() {
        synchronized (mCommitted) {
            if (!mCurrentLine.isEmpty()) {
                mCommitted.append(mask(mCurrentLine)).append('\n');
                mCurrentLine = "";
            }
        }
        render(true);
    }

    private void render(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - mLastRender < 120) return;
        mLastRender = now;
        final String text;
        synchronized (mCommitted) {
            text = mCommitted.toString() + mask(mCurrentLine);
        }
        runOnUiThread(() -> mLog.setText(text));
    }

    private void scrollToLog() {
        final ScrollView sv = findViewById(R.id.log_card).getParent() instanceof View
            ? findScroll((View) findViewById(R.id.log_card).getParent()) : null;
        if (sv != null) sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
    }

    private ScrollView findScroll(View v) {
        while (v != null) {
            if (v instanceof ScrollView) return (ScrollView) v;
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return null;
    }

    private String readRaw(int id) throws IOException {
        InputStream in = getResources().openRawResource(id);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toString("UTF-8");
    }
}
