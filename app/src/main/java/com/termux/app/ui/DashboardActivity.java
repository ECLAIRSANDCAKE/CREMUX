package com.termux.app.ui;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.termux.R;
import com.termux.app.TermuxService;
import com.termux.app.activities.SettingsActivity;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The Home screen: environment status, quick actions and recent sessions. */
public class DashboardActivity extends RedesignActivity {

    private TermuxService mService;
    private boolean mBound = false;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            mService = ((TermuxService.LocalBinder) binder).service;
            refresh();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
            refresh();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        findViewById(R.id.btn_search).setOnClickListener(v ->
            startActivity(new Intent(this, PackageManagerActivity.class).putExtra("focus_search", true)));
        findViewById(R.id.btn_settings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.status_card).setOnClickListener(v -> TerminalCommands.openTerminal(this));

        findViewById(R.id.qa_templates).setOnClickListener(v -> startActivity(new Intent(this, CommandTemplatesActivity.class)));
        findViewById(R.id.qa_install).setOnClickListener(v -> startActivity(new Intent(this, PackageManagerActivity.class)));
        findViewById(R.id.qa_storage).setOnClickListener(v -> TerminalCommands.run(this, "termux-setup-storage"));
        findViewById(R.id.qa_sessions).setOnClickListener(v -> startActivity(new Intent(this, SessionsActivity.class)));
        findViewById(R.id.quick_see_all).setOnClickListener(v -> startActivity(new Intent(this, CommandTemplatesActivity.class)));

        findViewById(R.id.sync_card).setOnClickListener(v -> startActivity(new Intent(this, GitHubSyncActivity.class)));
        findViewById(R.id.decompile_card).setOnClickListener(v -> startActivity(new Intent(this, com.termux.app.ai.DecompileActivity.class)));
        findViewById(R.id.rebuild_card).setOnClickListener(v -> startActivity(new Intent(this,
            com.termux.app.ai.RebuildActivity.class)));
        findViewById(R.id.ai_card).setOnClickListener(v -> startActivity(new Intent(this,
            com.termux.app.ai.AiConfig.isConfigured(this) ? com.termux.app.ai.AiChatActivity.class
                : com.termux.app.ai.AiSettingsActivity.class)));

        findViewById(R.id.nav_terminal).setOnClickListener(v -> TerminalCommands.openTerminal(this));
        findViewById(R.id.nav_sync).setOnClickListener(v -> startActivity(new Intent(this, GitHubSyncActivity.class)));
        findViewById(R.id.nav_files).setOnClickListener(v -> startActivity(new Intent(this, FileManagerActivity.class)));
        findViewById(R.id.nav_settings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Bind only if the service is already running; Home never starts it by itself.
        try {
            mBound = bindService(new Intent(this, TermuxService.class), mConnection, 0);
        } catch (Exception e) {
            mBound = false;
        }
        refresh();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mBound) {
            try {
                unbindService(mConnection);
            } catch (Exception ignored) { }
            mBound = false;
        }
        mService = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @NonNull
    private List<TerminalSession> sessions() {
        List<TerminalSession> out = new ArrayList<>();
        if (mService == null) return out;
        for (TermuxSession ts : new ArrayList<>(mService.getTermuxSessions())) {
            if (ts.getTerminalSession() != null) out.add(ts.getTerminalSession());
        }
        Collections.sort(out, (a, b) -> Long.compare(RecentSessions.lastActive(b), RecentSessions.lastActive(a)));
        return out;
    }

    private void refresh() {
        refreshSync();
        refreshAi();
        boolean installed = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/bin/login").exists();
        List<TerminalSession> sessions = sessions();

        TextView title = findViewById(R.id.status_title);
        TextView subtitle = findViewById(R.id.status_subtitle);
        View dot = findViewById(R.id.status_dot);
        if (!installed) {
            title.setText("Setup required");
            subtitle.setText("Tap to install the environment");
            dot.setBackgroundResource(R.drawable.dot_orange);
        } else {
            title.setText("Ready");
            subtitle.setText(sessions.isEmpty() ? "Tap to open a terminal" : "Active");
            dot.setBackgroundResource(R.drawable.dot_green);
        }
    }

    private void refreshAi() {
        ((TextView) findViewById(R.id.ai_subtitle)).setText(com.termux.app.ai.AiConfig.summary(this));
    }

    private void refreshSync() {
        TextView sub = findViewById(R.id.sync_subtitle);
        if (!GitHubCredentials.hasCredentials(this)) {
            sub.setText("Not connected");
            return;
        }
        String repo = GitHubCredentials.getLastRepo(this);
        long t = GitHubCredentials.getLastTime(this);
        if (repo == null || t == 0) {
            sub.setText("Signed in as " + GitHubCredentials.getUser(this) + "");
        } else {
            String run = GitHubCredentials.getLastRun(this);
            sub.setText("" + DateUtils.getRelativeTimeSpanString(t,
                System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
                + (run != null ? " · " + run.toLowerCase(java.util.Locale.US) : ""));
        }
    }
}
