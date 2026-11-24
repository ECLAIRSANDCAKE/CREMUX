package com.termux.app.ai;

import androidx.annotation.NonNull;

import java.util.regex.Pattern;

/**
 * Best-effort heuristics deciding when a model-written command needs the user's eyes even in auto-run mode,
 * and which files the model may not read directly. This is a speed bump, not a sandbox: the real protection
 * is that commands are shown to the user and that auto-run is off by default.
 */
public final class AiSafety {

    // MULTILINE: a model-written command can hide a second command on a new line, and "^" must see it.
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.MULTILINE;

    /**
     * A command name position: start of a line, after a separator (; & | ( ` { newline), after a keyword such as
     * then / do, or after a wrapper such as xargs / sudo / env. Callers add an optional path prefix themselves.
     */
    private static final String CMD = "(^|[;&|(`{\\n]|\\b(then|do|else|xargs|sudo|time|nohup|env|exec|command|busybox|watch)\\s)\\s*(\\S*/)?";

    private static final Pattern[] RISKY = {
        // Deleting, moving, overwriting, changing permissions, killing, privilege.
        Pattern.compile(CMD + "(rm|rmdir|mv|dd|shred|truncate|chmod|chown|kill|killall|pkill|su|sudo|reboot|shutdown|mkfs\\S*|tee|unlink|install|ln)(\\s|$)", FLAGS),
        // In-place edits and any redirection into a file (anything but /dev/null and fd duplication such as 2>&1).
        Pattern.compile("\\b(sed|perl|ruby)\\s+(-[a-z-]+\\s+)*(-[a-z]*i|--in-place)", FLAGS),
        Pattern.compile("(?<![0-9&<>])>>?\\s*(?!&|/dev/null\\b|\\s)[^\\s;&|)]", FLAGS),
        Pattern.compile("\\b(pkg|apt|apt-get|dpkg)\\s+(remove|uninstall|purge|autoremove|autoclean|-r|-P)\\b", FLAGS),
        Pattern.compile("\\bfind\\b[^;|&]*\\s-(delete|exec|execdir|ok|okdir|fprint0?|fprintf|fls)\\b", FLAGS),
        // Writing outside the home / tmp areas.
        Pattern.compile(">>?\\s*/(dev|sdcard|storage|system|vendor|proc|sys|data/data/com\\.termux/files/usr)\\b", FLAGS),
        // Piping downloads or decoded blobs into a shell, eval.
        Pattern.compile("\\|\\s*(sudo\\s+)?(ba|z|da|k)?sh\\b", FLAGS),
        Pattern.compile("\\b(eval|source)\\b\\s+[\"'$(`]", FLAGS),
        Pattern.compile(":\\(\\)\\s*\\{", FLAGS),
        // Running a string as code, piping into an interpreter, destructive git.
        Pattern.compile("\\b(ba|z|da|k)?sh\\s+-[a-z]*c\\b", FLAGS),
        Pattern.compile("\\|\\s*(python[0-9.]*|perl|ruby|node|php|lua)\\b", FLAGS),
        Pattern.compile(CMD + "(python[0-9.]*|perl|ruby|node|php|lua)\\s+(-[a-z]*[ce]\\b|--eval)", FLAGS),
        Pattern.compile(CMD + "\\.\\s+\\S", FLAGS),
        Pattern.compile("\\bgit\\s+(clean|reset\\s+--hard|push|checkout\\s+--)\\b", FLAGS),
        // Moving data off the device.
        Pattern.compile(CMD + "(nc|ncat|netcat|socat|scp|sftp|ftp|telnet|rsync)(\\s|$)", FLAGS),
        Pattern.compile("\\b(curl|wget)\\b.*(\\s-d\\b|\\s--data|\\s-F\\b|\\s--form|\\s--upload-file|\\s-T\\b|\\s--post|\\s-X\\s*(POST|PUT|DELETE))", FLAGS),
        // Credentials and app data.
        Pattern.compile("(\\.ssh|\\.gnupg|shared_prefs|\\.netrc|git-credentials|id_rsa|id_ed25519|/databases/)", FLAGS),
    };

    private AiSafety() { }

    public static boolean isRisky(@NonNull String command) {
        for (Pattern p : RISKY) {
            if (p.matcher(command).find()) return true;
        }
        return false;
    }

    private static final java.util.Set<String> READ_ONLY = new java.util.HashSet<>(java.util.Arrays.asList(
        "ls", "cat", "head", "tail", "wc", "file", "stat", "strings", "readelf", "objdump", "nm", "grep", "egrep",
        "fgrep", "rg", "find", "xxd", "hexdump", "od", "sha256sum", "sha1sum", "md5sum", "du", "df", "pwd", "echo",
        "which", "uname", "id", "whoami", "date", "basename", "dirname", "realpath", "sort", "uniq", "cut", "tr",
        "sed", "rabin2", "r2", "radare2", "unzip", "zipinfo", "tree", "less", "more", "diff", "cmp", "ldd", "test"));

    private static final java.util.Map<String, String> PLAIN = new java.util.HashMap<>();
    static {
        String[][] m = {
            {"ls", "Lists files"}, {"tree", "Lists files"}, {"cat", "Reads file contents"}, {"head", "Reads the start of a file"},
            {"tail", "Reads the end of a file"}, {"less", "Reads file contents"}, {"more", "Reads file contents"},
            {"grep", "Searches text"}, {"egrep", "Searches text"}, {"fgrep", "Searches text"}, {"rg", "Searches text"},
            {"find", "Finds files"}, {"file", "Identifies a file type"}, {"stat", "Shows file details"},
            {"strings", "Extracts readable strings"}, {"readelf", "Inspects a binary"}, {"objdump", "Disassembles a binary"},
            {"nm", "Lists symbols"}, {"rabin2", "Inspects a binary"}, {"r2", "Analyzes a binary with radare2"},
            {"radare2", "Analyzes a binary with radare2"}, {"xxd", "Shows raw bytes"}, {"hexdump", "Shows raw bytes"},
            {"od", "Shows raw bytes"}, {"sha256sum", "Computes a checksum"}, {"md5sum", "Computes a checksum"},
            {"sha1sum", "Computes a checksum"}, {"wc", "Counts lines or bytes"}, {"du", "Measures disk usage"},
            {"df", "Shows free space"}, {"unzip", "Opens a zip or APK"}, {"zipinfo", "Lists a zip or APK"},
            {"apktool", "Decodes or rebuilds an APK"}, {"jadx", "Decompiles to Java"}, {"gdb", "Debugs a program"},
            {"strace", "Traces a program"}, {"pkg", "Manages packages"}, {"apt", "Manages packages"},
            {"pip", "Installs Python packages"}, {"curl", "Talks to the network"}, {"wget", "Downloads from the network"},
            {"rm", "Deletes files"}, {"mv", "Moves or renames files"}, {"cp", "Copies files"}, {"mkdir", "Creates folders"},
            {"chmod", "Changes permissions"}, {"git", "Runs git"}, {"python", "Runs a Python script"},
            {"python3", "Runs a Python script"}, {"node", "Runs a script"}, {"echo", "Prints text"},
            {"sed", "Transforms text"}, {"awk", "Transforms text"}, {"sort", "Sorts lines"}, {"kill", "Stops a process"},
            {"pkill", "Stops a process"}, {"re-decompile", "Decompiles native code with Ghidra"},
            {"re-triage", "Runs a quick triage"}, {"re-setup", "Installs reverse-engineering tools"},
        };
        for (String[] e : m) PLAIN.put(e[0], e[1]);
    }

    private static java.util.List<String> words(@NonNull String command) {
        java.util.List<String> out = new java.util.ArrayList<>();
        command = command.replaceAll("\\d*>&\\d+|&>", " ");
        for (String seg : command.split("(&&|\\|\\||[;&|\\n])")) {
            String t = seg.trim();
            while (t.matches("^[A-Za-z_][A-Za-z0-9_]*=\\S*\\s+.*")) t = t.replaceFirst("^\\S+\\s+", "");
            if (t.isEmpty()) continue;
            String w = t.split("\\s+")[0];
            w = w.substring(w.lastIndexOf('/') + 1);
            if (!w.isEmpty()) out.add(w);
        }
        return out;
    }

    /** One short plain-English line for what a shell command does, for people who do not read shell. */
    @NonNull
    public static String summarize(@NonNull String command) {
        java.util.LinkedHashSet<String> parts = new java.util.LinkedHashSet<>();
        for (String w : words(command)) {
            String p = PLAIN.get(w.toLowerCase(java.util.Locale.US));
            parts.add(p != null ? p : "Runs " + w);
        }
        if (parts.isEmpty()) return "Runs a command";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String p : parts) {
            if (n == 3) {
                sb.append(", and more");
                break;
            }
            sb.append(n == 0 ? p : ", then " + Character.toLowerCase(p.charAt(0)) + p.substring(1));
            n++;
        }
        return sb.toString();
    }

    /**
     * True for commands that only look at things: every part of the pipeline is an inspection tool, nothing is
     * written, nothing is nested in $( ) or backticks, and {@link #isRisky} agrees.
     */
    public static boolean isReadOnly(@NonNull String command) {
        if (isRisky(command) || command.contains("$(") || command.contains("`")) return false;
        java.util.List<String> ws = words(command);
        if (ws.isEmpty()) return false;
        for (String w : ws) if (!READ_ONLY.contains(w.toLowerCase(java.util.Locale.US))) return false;
        // Flags that turn an inspection tool into a writer: r2 -w, sort -o, unzip without a list/print flag.
        if (java.util.regex.Pattern.compile("\\b(r2|radare2)\\b[^|;&]*\\s-n?w\\b|\\bsort\\b[^|;&]*\\s-o\\b|--output").matcher(command).find()) return false;
        // r2 can run shell commands (!cmd, -i script); rg --pre runs a program per file; date -s sets the clock;
        // sed has w/e commands (write a file / run a command) and -f scripts.
        if (java.util.regex.Pattern.compile("\\b(r2|radare2)\\b[^\\n]*(!|\\s-[a-zA-Z]*[iI]\\b)|\\brg\\b[^|;&]*--pre|\\bdate\\b[^|;&]*\\s-[a-zA-Z]*s\\b"
            + "|\\bsed\\b[^|;&\\n]*(\\s-[a-zA-Z]*f\\b|--file|[\\s'\"{;0-9$/,!][weWr]\\s+\\S)").matcher(command).find()) return false;
        return !ws.contains("unzip") || java.util.regex.Pattern.compile("\\bunzip\\s+(-\\S+\\s+)*-[a-zA-Z]*[lZpc]").matcher(command).find();
    }

    /** Files the read_file tool refuses to open (keys, credentials, this app's own preference files). */
    public static boolean isSensitivePath(@NonNull String canonicalPath) {
        String p = canonicalPath.toLowerCase(java.util.Locale.US);
        if (p.contains("/.ssh") || p.contains("/.gnupg") || p.contains("/shared_prefs") || p.contains("/databases/")) return true;
        String name = p.substring(p.lastIndexOf('/') + 1);
        return name.startsWith("id_rsa") || name.startsWith("id_ed25519") || name.startsWith("id_ecdsa")
            || name.equals(".netrc") || name.equals(".git-credentials") || name.equals(".npmrc")
            || name.endsWith(".pem") || name.endsWith(".p12") || name.endsWith(".jks") || name.endsWith(".keystore");
    }

    /** Remove terminal colour / cursor escape sequences so tool output is clean text for the model. */
    @NonNull
    public static String stripAnsi(@NonNull String s) {
        return s.replaceAll("\u001B\\[[0-9;?]*[ -/]*[@-~]", "").replaceAll("\u001B\\][^\u0007]*\u0007", "").replace("\r\n", "\n");
    }

    /** Keeps the start and the end of long output, which is where the useful lines usually are. */
    public static final class Capture {
        private final int head;
        private final int tail;
        private final StringBuilder h = new StringBuilder();
        private final StringBuilder t = new StringBuilder();
        private long total;

        public Capture(int head, int tail) {
            this.head = head;
            this.tail = tail;
        }

        public synchronized void append(char[] buf, int n) {
            total += n;
            int i = 0;
            if (h.length() < head) {
                int take = Math.min(n, head - h.length());
                h.append(buf, 0, take);
                i = take;
            }
            if (i < n) {
                t.append(buf, i, n - i);
                if (t.length() > tail * 2) t.delete(0, t.length() - tail);
            }
        }

        @NonNull
        public synchronized String result() {
            if (t.length() > tail) t.delete(0, t.length() - tail);
            long omitted = total - h.length() - t.length();
            if (omitted <= 0) return h.toString() + t;
            return h + "\n… [" + omitted + " characters omitted; narrow the command with head/grep/sed -n] …\n" + t;
        }
    }
}
