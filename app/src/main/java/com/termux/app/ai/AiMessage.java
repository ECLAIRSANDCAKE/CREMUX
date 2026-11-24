package com.termux.app.ai;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One turn of the conversation, in a provider independent form. */
public final class AiMessage {

    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";
    public static final String TOOL = "tool";

    /** A function call requested by the model. */
    public static final class ToolCall {
        @NonNull public final String id;
        @NonNull public final String name;
        @NonNull public final JSONObject args;

        public ToolCall(@NonNull String id, @NonNull String name, @NonNull JSONObject args) {
            this.id = id;
            this.name = name;
            this.args = args;
        }
    }

    @NonNull public final String role;
    @NonNull public final String text;
    @NonNull public final List<ToolCall> toolCalls;
    /** TOOL role only: the call this message answers. */
    @Nullable public final String toolCallId;
    @Nullable public final String toolName;
    public final boolean isError;
    /**
     * Gemini only: the raw {@code parts} of the model turn. Gemini 3 models attach a
     * {@code thoughtSignature} to function calls that must be sent back unchanged.
     */
    @Nullable public final JSONArray rawParts;

    private AiMessage(@NonNull String role, @NonNull String text, @NonNull List<ToolCall> toolCalls,
                      @Nullable String toolCallId, @Nullable String toolName, boolean isError,
                      @Nullable JSONArray rawParts) {
        this.role = role;
        this.text = text;
        this.toolCalls = toolCalls;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.isError = isError;
        this.rawParts = rawParts;
    }

    @NonNull
    org.json.JSONObject toJson() throws org.json.JSONException {
        org.json.JSONObject o = new org.json.JSONObject();
        o.put("role", role).put("text", text).put("error", isError);
        if (toolCallId != null) o.put("callId", toolCallId);
        if (toolName != null) o.put("tool", toolName);
        if (rawParts != null) o.put("raw", rawParts);
        JSONArray calls = new JSONArray();
        for (ToolCall c : toolCalls) calls.put(new org.json.JSONObject().put("id", c.id).put("name", c.name).put("args", c.args));
        if (calls.length() > 0) o.put("calls", calls);
        return o;
    }

    @NonNull
    static AiMessage fromJson(@NonNull org.json.JSONObject o) throws org.json.JSONException {
        List<ToolCall> calls = new ArrayList<>();
        JSONArray arr = o.optJSONArray("calls");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject c = arr.getJSONObject(i);
                calls.add(new ToolCall(c.getString("id"), c.getString("name"), c.getJSONObject("args")));
            }
        }
        return new AiMessage(o.getString("role"), o.optString("text", ""), calls, o.optString("callId", null),
            o.optString("tool", null), o.optBoolean("error", false), o.optJSONArray("raw"));
    }

    public static AiMessage user(@NonNull String text) {
        return new AiMessage(USER, text, Collections.<ToolCall>emptyList(), null, null, false, null);
    }

    public static AiMessage assistant(@NonNull String text, @Nullable List<ToolCall> calls, @Nullable JSONArray rawParts) {
        return new AiMessage(ASSISTANT, text, calls == null ? new ArrayList<ToolCall>() : calls, null, null, false, rawParts);
    }

    public static AiMessage toolResult(@NonNull String callId, @NonNull String toolName, @NonNull String content, boolean isError) {
        return new AiMessage(TOOL, content, Collections.<ToolCall>emptyList(), callId, toolName, isError, null);
    }
}
