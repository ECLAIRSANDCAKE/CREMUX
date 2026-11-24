package com.termux.app.ui;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.termux.R;
import com.termux.app.TermuxService;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Sessions manager: running (recent) sessions and saved sessions. */
public class SessionsActivity extends RedesignActivity {

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
        setContentView(R.layout.activity_sessions);
        setupHeader("Sessions", null);

        findViewById(R.id.sessions_new).setOnClickListener(v ->
            startActivity(TerminalCommands.sign(new Intent(this, com.termux.app.TermuxActivity.class)
                .putExtra(TerminalCommands.EXTRA_NEW_SESSION, true))));
        findViewById(R.id.saved_save).setOnClickListener(v -> saveSessionFlow());
    }

    @Override
    protected void onStart() {
        super.onStart();
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
        refreshRecent();
        refreshSaved();
    }

    private void refreshRecent() {
        LinearLayout container = findViewById(R.id.recent_sessions);
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        List<TerminalSession> sessions = sessions();
        if (sessions.isEmpty()) {
            View row = inflater.inflate(R.layout.item_list_row, container, false);
            ((TextView) row.findViewById(R.id.rs_name)).setText("No sessions yet. Tap to start one");
            ((TextView) row.findViewById(R.id.rs_time)).setText("");
            row.setOnClickListener(v -> TerminalCommands.openTerminal(this));
            addRow(container, row);
            return;
        }
        for (TerminalSession s : sessions) {
            View row = inflater.inflate(R.layout.item_list_row, container, false);
            String name = displayName(s, indexOf(s));
            if (!s.isRunning()) name += " (finished)";
            ((TextView) row.findViewById(R.id.rs_name)).setText(name);
            ((TextView) row.findViewById(R.id.rs_time)).setText(timeAgo(s));
            row.setOnClickListener(v -> TerminalCommands.openSession(this, s.mHandle));
            row.setOnLongClickListener(v -> {
                showSessionOptions(s);
                return true;
            });
            addRow(container, row);
        }
    }

    private void refreshSaved() {
        LinearLayout container = findViewById(R.id.saved_sessions);
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        List<SessionArchive.Saved> saved = SessionArchive.list();
        if (saved.isEmpty()) {
            View row = inflater.inflate(R.layout.item_list_row, container, false);
            ((TextView) row.findViewById(R.id.rs_name)).setText("No saved sessions. Tap to save one");
            ((TextView) row.findViewById(R.id.rs_time)).setText("");
            row.setOnClickListener(v -> saveSessionFlow());
            addRow(container, row);
            return;
        }
        for (final SessionArchive.Saved item : saved) {
            View row = inflater.inflate(R.layout.item_list_row, container, false);
            ((ImageView) row.findViewById(R.id.rs_icon)).setImageResource(R.drawable.ic_file);
            ((TextView) row.findViewById(R.id.rs_name)).setText(item.name);
            ((TextView) row.findViewById(R.id.rs_time)).setText(DateUtils.getRelativeTimeSpanString(item.time,
                System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE));
            row.setOnClickListener(v -> showSavedOptions(item));
            addRow(container, row);
        }
    }

    private int indexOf(TerminalSession s) {
        if (mService == null) return 0;
        List<TermuxSession> all = new ArrayList<>(mService.getTermuxSessions());
        for (int i = 0; i < all.size(); i++) if (all.get(i).getTerminalSession() == s) return i;
        return 0;
    }

    private static String displayName(TerminalSession s, int index) {
        String name = s.mSessionName;
        if (name != null && !name.isEmpty()) return name;
        return "Session " + (index + 1);
    }

    private static String timeAgo(TerminalSession s) {
        long t = RecentSessions.lastActive(s);
        if (t == 0) return "";
        if (System.currentTimeMillis() - t < DateUtils.MINUTE_IN_MILLIS) return "just now";
        return DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE).toString();
    }

    private void addRow(LinearLayout container, View row) {
        if (container.getChildCount() > 0) {
            View divider = new View(this);
            divider.setBackgroundColor(UiColors.attr(this, R.attr.tx_stroke));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
            dlp.setMarginStart(dp(50));
            container.addView(divider, dlp);
        }
        container.addView(row);
    }

    private void showSessionOptions(TerminalSession s) {
        String[] items = {"Open", "Rename", "Save session", s.isRunning() ? "Close session" : "Remove session"};
        Picker.show(this, displayName(s, indexOf(s)), items, -1, which -> {
                if (which == 0) {
                    TerminalCommands.openSession(this, s.mHandle);
                } else if (which == 1) {
                    EditText input = new EditText(this);
                    input.setSingleLine(true);
                    input.setText(s.mSessionName == null ? "" : s.mSessionName);
                    FrameLayout wrap = new FrameLayout(this);
                    wrap.setPadding(dp(20), dp(8), dp(20), 0);
                    wrap.addView(input, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    UiPrefs.show(this, new AlertDialog.Builder(this).setTitle("Rename session").setView(wrap)
                        .setPositiveButton(android.R.string.ok, (dd, ww) -> {
                            s.mSessionName = input.getText().toString().trim();
                            refresh();
                        })
                        .setNegativeButton(android.R.string.cancel, null));
                } else if (which == 2) {
                    SessionSaver.promptAndSave(this, s);
                } else {
                    if (s.isRunning()) s.finishIfRunning();
                    else if (mService != null) mService.removeTermuxSession(s);
                    refresh();
                }
            });
    }

    private void saveSessionFlow() {
        final List<TerminalSession> sessions = sessions();
        if (sessions.isEmpty()) {
            Toast.makeText(this, "No running session. Open Terminal first.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (sessions.size() == 1) {
            SessionSaver.promptAndSave(this, sessions.get(0));
            return;
        }
        String[] labels = new String[sessions.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = displayName(sessions.get(i), indexOf(sessions.get(i)));
        Picker.show(this, "Save which session?", labels, -1, which -> SessionSaver.promptAndSave(this, sessions.get(which)));
    }

    private void showSavedOptions(final SessionArchive.Saved item) {
        String[] items = {"Restore in new session", "View output", "Delete"};
        Picker.show(this, item.name, item.cwd, items, -1, which -> {
                if (which == 0) {
                    TerminalCommands.runInNewSession(this, "cd " + TerminalCommands.quote(item.cwd)
                        + " && clear && tail -n 60 " + TerminalCommands.quote(item.transcript.getPath()));
                } else if (which == 1) {
                    TerminalCommands.run(this, "less -R " + TerminalCommands.quote(item.transcript.getPath()));
                } else {
                    UiPrefs.show(this, new AlertDialog.Builder(this).setTitle("Delete \"" + item.name + "\"?")
                        .setPositiveButton("Delete", (dd, ww) -> {
                            SessionArchive.delete(item);
                            refreshSaved();
                        })
                        .setNegativeButton(android.R.string.cancel, null));
                }
            });
    }
}
