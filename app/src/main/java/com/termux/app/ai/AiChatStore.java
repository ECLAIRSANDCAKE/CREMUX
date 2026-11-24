package com.termux.app.ai;

import android.content.Context;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Saves the conversation in the app's private storage so it survives the app being closed. The file can contain
 * command output, so it is never placed in shared storage.
 */
final class AiChatStore {

    private static final String FILE = "ai_chat.json";
    private static final int MAX_BYTES = 3 * 1024 * 1024;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private static volatile Context sApp;
    private static boolean sLoaded;

    private AiChatStore() { }

    static void init(@NonNull Context context) {
        sApp = context.getApplicationContext();
    }

    /** Fills {@link AiChatState#HISTORY} from disk once per process (does nothing if a chat is already in memory). */
    static synchronized void loadOnce() {
        if (sLoaded || sApp == null) return;
        sLoaded = true;
        if (!AiChatState.HISTORY.isEmpty()) return;
        File f = new File(sApp.getFilesDir(), FILE);
        if (!f.isFile() || f.length() > MAX_BYTES * 2L) return;
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) != -1) buf.write(b, 0, n);
            }
            JSONArray arr = new JSONArray(buf.toString("UTF-8"));
            List<AiMessage> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) list.add(AiMessage.fromJson(arr.getJSONObject(i)));
            AiChatState.HISTORY.addAll(complete(list));
        } catch (IOException | JSONException | RuntimeException e) {
            // A damaged file must never block the chat: start empty.
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /**
     * Keeps the longest prefix in which every tool call has its result. A chat saved mid-run (app killed while a
     * command was running) would otherwise be rejected by the provider on the next request.
     */
    @NonNull
    static List<AiMessage> complete(@NonNull List<AiMessage> in) {
        for (int i = 0; i < in.size(); i++) {
            AiMessage m = in.get(i);
            if (!AiMessage.ASSISTANT.equals(m.role) || m.toolCalls.isEmpty()) continue;
            Set<String> need = new HashSet<>();
            for (AiMessage.ToolCall c : m.toolCalls) need.add(c.id);
            int j = i + 1;
            while (j < in.size() && AiMessage.TOOL.equals(in.get(j).role)) {
                if (in.get(j).toolCallId != null) need.remove(in.get(j).toolCallId);
                j++;
            }
            if (!need.isEmpty()) return new ArrayList<>(in.subList(0, i));
        }
        return in;
    }

    /** Writes the current chat in the background. */
    static void saveAsync() {
        final Context app = sApp;
        if (app == null) return;
        final List<AiMessage> snapshot = new ArrayList<>(AiChatState.HISTORY);
        IO.execute(() -> write(app, snapshot));
    }

    static void clear() {
        final Context app = sApp;
        if (app == null) return;
        IO.execute(() -> {
            //noinspection ResultOfMethodCallIgnored
            new File(app.getFilesDir(), FILE).delete();
        });
    }

    private static void write(@NonNull Context app, @NonNull List<AiMessage> messages) {
        if (messages.isEmpty()) return;
        try {
            // Drop the oldest whole turns until the file is a sane size; always start on a user message.
            String json = toJson(messages);
            int start = 0;
            while (json.length() > MAX_BYTES && start < messages.size()) {
                start++;
                while (start < messages.size() && !AiMessage.USER.equals(messages.get(start).role)) start++;
                json = start >= messages.size() ? "[]" : toJson(messages.subList(start, messages.size()));
            }
            File dir = app.getFilesDir();
            File tmp = new File(dir, FILE + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(json.getBytes("UTF-8"));
            }
            File target = new File(dir, FILE);
            if (!tmp.renameTo(target)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (IOException | JSONException | RuntimeException ignored) {
            // Saving is a convenience; failing must not disturb the chat.
        }
    }

    @NonNull
    private static String toJson(@NonNull List<AiMessage> messages) throws JSONException {
        JSONArray arr = new JSONArray();
        for (AiMessage m : messages) arr.put(m.toJson());
        return arr.toString();
    }
}
