package com.termux.app.ui;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Minimal blocking GitHub REST client. Call from a background thread only. */
public final class GitHubApi {

    public static final class Repo {
        public final String fullName;
        public final String defaultBranch;
        public final boolean isPrivate;

        Repo(String fullName, String defaultBranch, boolean isPrivate) {
            this.fullName = fullName;
            this.defaultBranch = defaultBranch;
            this.isPrivate = isPrivate;
        }
    }

    /** One GitHub Actions workflow run. */
    public static final class Run {
        public final long id;
        public final String name;
        public final String status;      // queued, in_progress, completed, ...
        public final String conclusion;  // success, failure, cancelled, ... (null until completed)
        public final String path;        // e.g. .github/workflows/build.yml
        public final String branch;
        public final String sha;
        public final String htmlUrl;

        Run(long id, String name, String status, String conclusion, String path, String branch, String sha, String htmlUrl) {
            this.id = id;
            this.name = name;
            this.status = status;
            this.conclusion = conclusion;
            this.path = path;
            this.branch = branch;
            this.sha = sha;
            this.htmlUrl = htmlUrl;
        }

        public boolean isCompleted() { return "completed".equals(status); }
        public boolean isSuccess() { return isCompleted() && "success".equals(conclusion); }
        public boolean isFailure() {
            return isCompleted() && !"success".equals(conclusion) && !"skipped".equals(conclusion) && !"neutral".equals(conclusion);
        }
    }

    /** Tail of a failed job's log plus the lines GitHub flagged as errors. */
    public static final class FailureLog {
        public String jobName = "";
        public String failedStep = "";
        public final List<String> errors = new ArrayList<>();
        /** Lines that explain the failure (compiler / Gradle / tool errors), found anywhere in the log. */
        public final List<String> keyLines = new ArrayList<>();
        /** The log around the last error, without the post-job cleanup noise that follows it. */
        public String tail = "";
    }

    private GitHubApi() { }

    /** @return the login name the token belongs to; throws if the token is invalid. */
    public static String getLogin(@NonNull String token) throws Exception {
        return new JSONObject(request("GET", "https://api.github.com/user", token, null)).getString("login");
    }

    public static List<Repo> listRepos(@NonNull String token) throws Exception {
        List<Repo> out = new ArrayList<>();
        for (int page = 1; page <= 5; page++) {
            String body = request("GET", "https://api.github.com/user/repos?per_page=100&sort=pushed&affiliation=owner,collaborator&page=" + page, token, null);
            JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                JSONObject perms = o.optJSONObject("permissions");
                if (perms != null && !perms.optBoolean("push", true)) continue;
                out.add(new Repo(o.getString("full_name"), o.optString("default_branch", "main"), o.optBoolean("private")));
            }
            if (arr.length() < 100) break;
        }
        return out;
    }

    public static Repo createRepo(@NonNull String token, @NonNull String name, boolean isPrivate) throws Exception {
        JSONObject req = new JSONObject();
        req.put("name", name);
        req.put("private", isPrivate);
        req.put("auto_init", false);
        JSONObject o = new JSONObject(request("POST", "https://api.github.com/user/repos", token, req.toString()));
        return new Repo(o.getString("full_name"), o.optString("default_branch", "main"), o.optBoolean("private"));
    }

    /**
     * Workflow runs of a repo. With {@code sha} only runs of that commit are returned, otherwise the latest
     * runs of {@code branch} (may be null for all branches).
     */
    public static List<Run> listRuns(@NonNull String token, @NonNull String repo, String sha, String branch) throws Exception {
        String url = "https://api.github.com/repos/" + repo + "/actions/runs?per_page=" + (sha != null ? 20 : 5);
        if (sha != null) url += "&head_sha=" + URLEncoder.encode(sha, "UTF-8");
        else if (branch != null) url += "&branch=" + URLEncoder.encode(branch, "UTF-8");
        JSONArray arr = new JSONObject(request("GET", url, token, null)).optJSONArray("workflow_runs");
        List<Run> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            out.add(new Run(o.getLong("id"),
                o.optString("name", o.optString("display_title", "Workflow")),
                o.optString("status", ""),
                o.isNull("conclusion") ? null : o.optString("conclusion", null),
                o.optString("path", ""),
                o.optString("head_branch", ""),
                o.optString("head_sha", ""),
                o.optString("html_url", "")));
        }
        return out;
    }

    /** Finds the first failed job of a run and returns the tail of its log. */
    public static FailureLog getFailureLog(@NonNull String token, @NonNull String repo, long runId) throws Exception {
        JSONArray jobs = new JSONObject(request("GET",
            "https://api.github.com/repos/" + repo + "/actions/runs/" + runId + "/jobs?per_page=100", token, null)).optJSONArray("jobs");
        FailureLog out = new FailureLog();
        JSONObject failed = null;
        if (jobs != null) {
            for (int i = 0; i < jobs.length() && failed == null; i++) {
                JSONObject j = jobs.getJSONObject(i);
                String c = j.isNull("conclusion") ? "" : j.optString("conclusion", "");
                if (c.equals("failure") || c.equals("timed_out") || c.equals("cancelled")) failed = j;
            }
        }
        if (failed == null) {
            out.tail = "No failed job found. The run may have been cancelled or failed before any job started "
                + "(for example an invalid workflow file). Open it on GitHub for details.";
            return out;
        }
        out.jobName = failed.optString("name", "");
        JSONArray steps = failed.optJSONArray("steps");
        if (steps != null) {
            for (int i = 0; i < steps.length(); i++) {
                JSONObject st = steps.getJSONObject(i);
                String c = st.isNull("conclusion") ? "" : st.optString("conclusion", "");
                if (c.equals("failure") || c.equals("timed_out")) {
                    out.failedStep = st.optString("name", "");
                    break;
                }
            }
        }
        fetchLogTail("https://api.github.com/repos/" + repo + "/actions/jobs/" + failed.getLong("id") + "/logs", token, out);
        return out;
    }

    /** A build output (artifact) uploaded by a workflow run. GitHub always packs these as a zip. */
    public static final class Artifact {
        public final long id;
        public final String name;
        public final long size;
        public final boolean expired;

        Artifact(long id, String name, long size, boolean expired) {
            this.id = id;
            this.name = name;
            this.size = size;
            this.expired = expired;
        }
    }

    public interface Progress { void onBytes(long done); }

    public static List<Artifact> listArtifacts(@NonNull String token, @NonNull String repo, long runId) throws Exception {
        JSONArray arr = new JSONObject(request("GET",
            "https://api.github.com/repos/" + repo + "/actions/runs/" + runId + "/artifacts?per_page=50", token, null)).optJSONArray("artifacts");
        List<Artifact> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            out.add(new Artifact(o.getLong("id"), o.optString("name", "artifact"), o.optLong("size_in_bytes"), o.optBoolean("expired")));
        }
        return out;
    }

    /** Streams an artifact zip into {@code out}. The pre-signed redirect is followed without the token. */
    public static void downloadArtifact(@NonNull String token, @NonNull String repo, long artifactId,
                                        @NonNull OutputStream out, Progress progress) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(
            "https://api.github.com/repos/" + repo + "/actions/artifacts/" + artifactId + "/zip").openConnection();
        String target;
        try {
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "Cremux");
            int code = c.getResponseCode();
            if (code != 302 && code != 301 && code != 307) {
                throw new IOException("GitHub " + code + " while downloading (the artifact may have expired).");
            }
            target = c.getHeaderField("Location");
        } finally {
            c.disconnect();
        }
        if (target == null) throw new IOException("GitHub did not return a download location.");
        if (!target.regionMatches(true, 0, "https://", 0, 8)) throw new IOException("Refusing a non-https download location.");
        HttpURLConnection d = (HttpURLConnection) new URL(target).openConnection();
        try {
            d.setConnectTimeout(15000);
            d.setReadTimeout(60000);
            d.setRequestProperty("User-Agent", "Cremux");
            int code = d.getResponseCode();
            if (code >= 400) throw new IOException("Download failed (" + code + ").");
            InputStream in = d.getInputStream();
            byte[] buf = new byte[32768];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (progress != null) progress.onBytes(done);
            }
            in.close();
        } finally {
            d.disconnect();
        }
    }

    /**
     * The log endpoint answers with a 302 to a pre-signed blob URL. The redirect is followed by hand so the
     * token is never sent to the storage host. Only the last lines are kept in memory.
     */
    private static void fetchLogTail(String url, String token, FailureLog out) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        String target = null;
        try {
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "Cremux");
            int code = c.getResponseCode();
            if (code == 302 || code == 301 || code == 307) {
                target = c.getHeaderField("Location");
            } else if (code == 200) {
                readTail(c.getInputStream(), out);
                return;
            } else {
                throw new IOException("GitHub " + code + " while fetching the log (the log may have expired).");
            }
        } finally {
            c.disconnect();
        }
        if (target == null) throw new IOException("GitHub did not return a log location.");
        if (!target.regionMatches(true, 0, "https://", 0, 8)) throw new IOException("Refusing a non-https log location.");
        HttpURLConnection d = (HttpURLConnection) new URL(target).openConnection();
        try {
            d.setConnectTimeout(15000);
            d.setReadTimeout(60000);
            d.setRequestProperty("User-Agent", "Cremux");
            int code = d.getResponseCode();
            if (code >= 400) throw new IOException("Log download failed (" + code + ").");
            readTail(d.getInputStream(), out);
        } finally {
            d.disconnect();
        }
    }

    private static final java.util.regex.Pattern KEY_LINE = java.util.regex.Pattern.compile(
        "(?i)(^e: |\\berror\\b[: ]|error:|FAILURE:|What went wrong|Execution failed|\\bFAILED\\b|cannot find symbol|"
            + "no such file|could not resolve|could not find|unresolved reference|exception in|\\bfatal\\b|"
            + "no space left|out of memory|permission denied|command not found|not found:|exit code [1-9])");

    private static void readTail(InputStream in, FailureLog out) throws IOException {
        final int keep = 4000;
        ArrayDeque<String> all = new ArrayDeque<>();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                // Lines start with an ISO timestamp: "2026-01-01T00:00:00.0000000Z text".
                int sp = line.indexOf(' ');
                if (sp > 20 && sp < 40 && line.length() > 10 && line.charAt(4) == '-' && line.charAt(10) == 'T') line = line.substring(sp + 1);
                if (line.length() > 600) line = line.substring(0, 600) + "…";
                if (line.startsWith("##[error]") && out.errors.size() < 20) out.errors.add(line.substring(9));
                all.addLast(line);
                if (all.size() > keep) all.removeFirst();
            }
        } finally {
            r.close();
        }
        List<String> lines = new ArrayList<>(all);

        // The failing step ends at the last ##[error]; what follows is runner cleanup ("Post job cleanup").
        int lastErr = -1;
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).startsWith("##[error]")) { lastErr = i; break; }
        }
        int from, to;
        if (lastErr >= 0) {
            from = Math.max(0, lastErr - 80);
            to = Math.min(lines.size(), lastErr + 3);
        } else {
            to = lines.size();
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                if (l.startsWith("##[group]Post ") || l.contains("Post job cleanup")) { to = i; break; }
            }
            from = Math.max(0, to - 150);
        }

        // Explanatory lines: Gradle "What went wrong" blocks first, then anything that looks like an error.
        java.util.LinkedHashSet<String> key = new java.util.LinkedHashSet<>();
        for (int i = 0; i < lines.size() && key.size() < 30; i++) {
            String l = lines.get(i).trim();
            if (l.isEmpty() || l.startsWith("##[group]") || l.startsWith("##[endgroup]") || l.startsWith("##[warning]")) continue;
            if (l.contains("What went wrong")) {
                key.add(l);
                for (int k = i + 1; k < Math.min(lines.size(), i + 4); k++) {
                    String n = lines.get(k).trim();
                    if (!n.isEmpty() && !n.startsWith("##[")) key.add(n);
                }
                continue;
            }
            if (l.startsWith("##[error]")) continue; // already listed in errors
            if (l.matches("(?i).*\\b0 errors?\\b.*") || l.matches("(?i).*(no|without) errors?.*")) continue;
            if (KEY_LINE.matcher(l).find()) key.add(l);
        }
        out.keyLines.addAll(key);

        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) sb.append(lines.get(i)).append('\n');
        out.tail = sb.toString().trim();
    }

    private static String request(String method, String url, String token, String body) throws Exception {
        return request(method, url, token, body, "application/vnd.github+json");
    }

    private static String request(String method, String url, String token, String body, String accept) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Accept", accept);
            c.setRequestProperty("User-Agent", "Cremux");
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                OutputStream os = c.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.close();
            }
            int code = c.getResponseCode();
            String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) {
                String msg = text;
                try {
                    JSONObject e = new JSONObject(text);
                    msg = e.optString("message", text);
                    JSONArray errs = e.optJSONArray("errors");
                    if (errs != null && errs.length() > 0) {
                        Object first = errs.get(0);
                        String detail = first instanceof JSONObject ? ((JSONObject) first).optString("message", "") : String.valueOf(first);
                        if (!detail.isEmpty()) msg += " (" + detail + ")";
                    }
                } catch (Exception ignored) { }
                if (code == 401) msg = "Token rejected (401). " + msg;
                throw new IOException("GitHub " + code + ": " + msg);
            }
            return text;
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toString("UTF-8");
    }
}
