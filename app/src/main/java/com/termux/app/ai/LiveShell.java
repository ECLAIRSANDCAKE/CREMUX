package com.termux.app.ai;

import android.content.Context;

import androidx.annotation.NonNull;

import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs a bash script in the Termux environment and streams its output line by line (for the Decompile screen,
 * where jadx or Ghidra can run for minutes). Unlike a terminal session there is no window; unlike
 * {@link AiTools} nothing is truncated or buffered until the end.
 */
public final class LiveShell {

    public interface Listener {
        /** Called on a background reader thread (so it must be thread-safe) for every output line (stdout and stderr merged, ANSI removed). */
        void onLine(@NonNull String line);
    }

    /** Returned by {@link #run} when the user cancelled. */
    public static final int CANCELLED = -2;

    private LiveShell() { }

    /**
     * @param script bash source. Arguments are available as $1, $2, … and are never spliced into the script,
     *               so file names with spaces or quotes are safe.
     * @return the exit code, or {@link #CANCELLED}
     */
    public static int run(@NonNull Context context, @NonNull String script, @NonNull File workDir,
                          @NonNull Listener listener, @NonNull AtomicBoolean cancelled, @NonNull String... args)
        throws IOException {
        File bash = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "bash");
        if (!bash.canExecute()) {
            throw new IOException("The Termux environment is not installed yet. Open the Terminal once and let the "
                + "first-run setup finish.");
        }
        File timeout = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "timeout");
        File tmpDir = new File(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
        if (!tmpDir.isDirectory() && !tmpDir.mkdirs()) throw new IOException("Cannot create " + tmpDir);
        File pidFile = File.createTempFile("cremux-run", ".pid", tmpDir);
        final boolean useTimeout = timeout.canExecute();

        List<String> argv = new ArrayList<>();
        argv.add(bash.getPath());
        argv.add("-c");
        if (useTimeout) {
            // GNU timeout runs the script in its own process group and, when it receives SIGTERM, forwards it
            // to the whole group. That is what lets Stop also end jadx's Java process and friends. The wrapper
            // records the pid ($$ stays the same across exec) so the cancel path can signal it.
            argv.add("echo $$ > \"$1\"; shift; exec timeout -s TERM -k 3 86400 bash -c \"$@\"");
            argv.add("cremux-wrap");
            argv.add(pidFile.getPath());
        }
        argv.add(script);
        argv.add("cremux");
        for (String a : args) argv.add(a);

        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            HashMap<String, String> env = new TermuxShellEnvironment().getEnvironment(context.getApplicationContext(), false);
            pb.environment().clear();
            pb.environment().putAll(env);
            pb.environment().put("NO_COLOR", "1");
            pb.environment().put("TERM", "dumb");
            pb.directory(workDir);
            pb.redirectErrorStream(true);
            p = pb.start();
            p.getOutputStream().close();

            final Process proc = p;
            final BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            final Thread pump = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        // Cut very long lines (a binary dumped to stdout has no newline) instead of buffering them whole.
                        StringBuilder line = new StringBuilder();
                        int c;
                        while ((c = reader.read()) != -1) {
                            if (c == '\n' || line.length() >= 4000) {
                                String clean = AiSafety.stripAnsi(line.toString());
                                if (!clean.trim().isEmpty()) listener.onLine(clean);
                                line.setLength(0);
                                if (c == '\n') continue;
                            }
                            line.append((char) c);
                        }
                        String last = AiSafety.stripAnsi(line.toString());
                        if (!last.trim().isEmpty()) listener.onLine(last);
                    } catch (IOException ignored) {
                        // Process ended or was killed.
                    }
                }
            }, "cremux-live-pump");
            pump.setDaemon(true);
            pump.start();

            long cancelAt = 0;
            Integer exit = null;
            while (exit == null) {
                try {
                    exit = proc.exitValue();
                } catch (IllegalThreadStateException running) {
                    if (cancelled.get()) {
                        long now = System.currentTimeMillis();
                        if (cancelAt == 0) {
                            cancelAt = now;
                            signalGroup(bash, context, pidFile, useTimeout, env);
                        } else if (now - cancelAt > 5000) {
                            proc.destroy(); // last resort: the polite SIGTERM did not end it
                        }
                    }
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        proc.destroy();
                        cancelled.set(true);
                        break;
                    }
                }
            }
            try {
                pump.join(1500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return cancelled.get() ? CANCELLED : (exit == null ? -1 : exit);
        } finally {
            if (p != null) p.destroy();
            pidFile.delete();
        }
    }

    private static void signalGroup(File bash, Context context, File pidFile, boolean useTimeout, HashMap<String, String> env) {
        if (!useTimeout) return;
        try {
            ProcessBuilder kill = new ProcessBuilder(bash.getPath(), "-c",
                "p=$(cat \"$1\" 2>/dev/null); [ -n \"$p\" ] && kill -TERM \"$p\"", "cremux-kill", pidFile.getPath());
            kill.environment().clear();
            kill.environment().putAll(env);
            kill.redirectErrorStream(true);
            Process k = kill.start();
            k.getOutputStream().close();
            k.getInputStream().close();
        } catch (IOException ignored) {
            // The destroy() fallback in run() still ends the process.
        }
    }
}
