package com.termux.app.ai;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Blocking chat client for three wire formats: OpenAI compatible ({@code /chat/completions}, which also
 * covers OpenRouter, Ollama, LM Studio, vLLM and others), Anthropic ({@code /v1/messages}) and Gemini
 * ({@code generateContent}). Call from a background thread only.
 */
public final class AiClient {

    public enum Provider { OPENAI, ANTHROPIC, GEMINI }

    public static final class Settings {
        @NonNull public final Provider provider;
        @NonNull public final String baseUrl;
        @NonNull public final String apiKey;
        @NonNull public final String model;

        public Settings(@NonNull Provider provider, @NonNull String baseUrl, @NonNull String apiKey, @NonNull String model) {
            this.provider = provider;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.model = model;
        }
    }

    public static final class AiException extends Exception {
        /** HTTP status, or -1 for network / parse problems. */
        public final int status;

        public AiException(int status, @NonNull String message) {
            super(message);
            this.status = status;
        }
    }

    private static final int MAX_OUTPUT_TOKENS = 8192;
    private static final int MAX_RESPONSE_CHARS = 8 * 1024 * 1024;
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    /** The request currently in flight, so Stop can abort it instead of waiting for the server. */
    private static volatile HttpURLConnection sActive;

    private AiClient() { }

    /** Abort the request in flight, if any; the blocked call then fails with a network error. */
    public static void abortActive() {
        final HttpURLConnection c = sActive;
        if (c == null) return;
        // Closing a socket must not happen on the UI thread (StrictMode / NetworkOnMainThreadException).
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                c.disconnect();
            }
        }, "cremux-ai-abort");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ chat

    /** Send the conversation and return the model's next turn (text and/or tool calls). */
    @NonNull
    public static AiMessage chat(@NonNull Settings s, @Nullable String system, @NonNull List<AiMessage> history,
                                 @NonNull List<AiTool> tools) throws AiException {
        try {
            switch (s.provider) {
                case ANTHROPIC: return chatAnthropic(s, system, history, tools);
                case GEMINI: return chatGemini(s, system, history, tools);
                default: return chatOpenAi(s, system, history, tools);
            }
        } catch (JSONException e) {
            throw new AiException(-1, "Unexpected response from the provider: " + e.getMessage());
        }
    }

    // ---- OpenAI compatible

    private static AiMessage chatOpenAi(Settings s, String system, List<AiMessage> history, List<AiTool> tools)
        throws AiException, JSONException {
        JSONArray messages = new JSONArray();
        if (system != null && !system.isEmpty()) {
            messages.put(new JSONObject().put("role", "system").put("content", system));
        }
        for (AiMessage m : history) {
            JSONObject o = new JSONObject();
            if (AiMessage.USER.equals(m.role)) {
                o.put("role", "user").put("content", m.text);
            } else if (AiMessage.ASSISTANT.equals(m.role)) {
                o.put("role", "assistant");
                if (!m.text.isEmpty() || m.toolCalls.isEmpty()) o.put("content", m.text);
                if (!m.toolCalls.isEmpty()) {
                    JSONArray calls = new JSONArray();
                    for (AiMessage.ToolCall c : m.toolCalls) {
                        calls.put(new JSONObject()
                            .put("id", c.id)
                            .put("type", "function")
                            .put("function", new JSONObject().put("name", c.name).put("arguments", c.args.toString())));
                    }
                    o.put("tool_calls", calls);
                }
            } else {
                o.put("role", "tool").put("tool_call_id", m.toolCallId).put("content", m.text);
            }
            messages.put(o);
        }

        JSONObject body = new JSONObject().put("model", s.model).put("messages", messages);
        if (!tools.isEmpty()) {
            JSONArray arr = new JSONArray();
            for (AiTool t : tools) {
                arr.put(new JSONObject().put("type", "function").put("function", new JSONObject()
                    .put("name", t.name).put("description", t.description).put("parameters", t.schema)));
            }
            body.put("tools", arr);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        if (!s.apiKey.isEmpty()) headers.put("Authorization", "Bearer " + s.apiKey);
        String url = trimBase(s.baseUrl) + "/chat/completions";
        JSONObject res = new JSONObject(http("POST", url, headers, body.toString()));

        JSONArray choices = res.optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new AiException(-1, "The provider returned no choices.");
        JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
        if (msg == null) throw new AiException(-1, "The provider returned an empty message.");

        StringBuilder text = new StringBuilder();
        Object content = msg.opt("content");
        if (content instanceof String) {
            text.append((String) content);
        } else if (content instanceof JSONArray) {
            JSONArray parts = (JSONArray) content;
            for (int i = 0; i < parts.length(); i++) {
                JSONObject p = parts.optJSONObject(i);
                if (p != null) text.append(p.optString("text", ""));
            }
        }

        List<AiMessage.ToolCall> calls = new ArrayList<>();
        JSONArray tc = msg.optJSONArray("tool_calls");
        if (tc != null) {
            for (int i = 0; i < tc.length(); i++) {
                JSONObject c = tc.getJSONObject(i);
                JSONObject fn = c.optJSONObject("function");
                if (fn == null) continue;
                String id = c.optString("id", "");
                if (id.isEmpty()) id = uniqueId("call");
                calls.add(new AiMessage.ToolCall(id, fn.optString("name", ""), parseArgs(fn.opt("arguments"))));
            }
        }
        return AiMessage.assistant(text.toString(), calls, null);
    }

    // ---- Anthropic

    private static AiMessage chatAnthropic(Settings s, String system, List<AiMessage> history, List<AiTool> tools)
        throws AiException, JSONException {
        JSONArray messages = new JSONArray();
        JSONArray pendingResults = null;
        for (AiMessage m : history) {
            if (AiMessage.TOOL.equals(m.role)) {
                if (pendingResults == null) pendingResults = new JSONArray();
                JSONObject r = new JSONObject().put("type", "tool_result").put("tool_use_id", m.toolCallId)
                    .put("content", m.text);
                if (m.isError) r.put("is_error", true);
                pendingResults.put(r);
                continue;
            }
            if (pendingResults != null) {
                messages.put(new JSONObject().put("role", "user").put("content", pendingResults));
                pendingResults = null;
            }
            if (AiMessage.USER.equals(m.role)) {
                messages.put(new JSONObject().put("role", "user").put("content", m.text));
            } else {
                JSONArray blocks = new JSONArray();
                if (!m.text.isEmpty()) blocks.put(new JSONObject().put("type", "text").put("text", m.text));
                for (AiMessage.ToolCall c : m.toolCalls) {
                    blocks.put(new JSONObject().put("type", "tool_use").put("id", c.id).put("name", c.name).put("input", c.args));
                }
                if (blocks.length() == 0) blocks.put(new JSONObject().put("type", "text").put("text", "(no content)"));
                messages.put(new JSONObject().put("role", "assistant").put("content", blocks));
            }
        }
        if (pendingResults != null) {
            messages.put(new JSONObject().put("role", "user").put("content", pendingResults));
        }

        JSONObject body = new JSONObject().put("model", s.model).put("max_tokens", MAX_OUTPUT_TOKENS).put("messages", messages);
        if (system != null && !system.isEmpty()) body.put("system", system);
        if (!tools.isEmpty()) {
            JSONArray arr = new JSONArray();
            for (AiTool t : tools) {
                arr.put(new JSONObject().put("name", t.name).put("description", t.description).put("input_schema", t.schema));
            }
            body.put("tools", arr);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-api-key", s.apiKey);
        headers.put("anthropic-version", ANTHROPIC_VERSION);
        JSONObject res = new JSONObject(http("POST", anthropicBase(s.baseUrl) + "/messages", headers, body.toString()));

        StringBuilder text = new StringBuilder();
        List<AiMessage.ToolCall> calls = new ArrayList<>();
        JSONArray content = res.optJSONArray("content");
        if (content != null) {
            for (int i = 0; i < content.length(); i++) {
                JSONObject b = content.getJSONObject(i);
                String type = b.optString("type", "");
                if ("text".equals(type)) {
                    text.append(b.optString("text", ""));
                } else if ("tool_use".equals(type)) {
                    JSONObject input = b.optJSONObject("input");
                    calls.add(new AiMessage.ToolCall(b.optString("id", uniqueId("toolu")), b.optString("name", ""),
                        input != null ? input : new JSONObject()));
                }
            }
        }
        if ("max_tokens".equals(res.optString("stop_reason", ""))) {
            text.append("\n\n_(Response was cut off: output limit reached.)_");
        }
        return AiMessage.assistant(text.toString(), calls, null);
    }

    // ---- Gemini

    private static AiMessage chatGemini(Settings s, String system, List<AiMessage> history, List<AiTool> tools)
        throws AiException, JSONException {
        JSONArray contents = new JSONArray();
        JSONArray pendingResults = null;
        for (AiMessage m : history) {
            if (AiMessage.TOOL.equals(m.role)) {
                if (pendingResults == null) pendingResults = new JSONArray();
                JSONObject resp = new JSONObject().put(m.isError ? "error" : "result", m.text);
                pendingResults.put(new JSONObject().put("functionResponse",
                    new JSONObject().put("name", m.toolName == null ? "" : m.toolName).put("response", resp)));
                continue;
            }
            if (pendingResults != null) {
                contents.put(new JSONObject().put("role", "user").put("parts", pendingResults));
                pendingResults = null;
            }
            if (AiMessage.USER.equals(m.role)) {
                contents.put(new JSONObject().put("role", "user")
                    .put("parts", new JSONArray().put(new JSONObject().put("text", m.text))));
            } else if (m.rawParts != null) {
                contents.put(new JSONObject().put("role", "model").put("parts", m.rawParts));
            } else {
                JSONArray parts = new JSONArray();
                if (!m.text.isEmpty()) parts.put(new JSONObject().put("text", m.text));
                for (AiMessage.ToolCall c : m.toolCalls) {
                    parts.put(new JSONObject().put("functionCall", new JSONObject().put("name", c.name).put("args", c.args)));
                }
                if (parts.length() == 0) parts.put(new JSONObject().put("text", "(no content)"));
                contents.put(new JSONObject().put("role", "model").put("parts", parts));
            }
        }
        if (pendingResults != null) {
            contents.put(new JSONObject().put("role", "user").put("parts", pendingResults));
        }

        JSONObject body = new JSONObject().put("contents", contents);
        if (system != null && !system.isEmpty()) {
            body.put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", system))));
        }
        if (!tools.isEmpty()) {
            JSONArray decls = new JSONArray();
            for (AiTool t : tools) {
                decls.put(new JSONObject().put("name", t.name).put("description", t.description).put("parameters", t.schema));
            }
            body.put("tools", new JSONArray().put(new JSONObject().put("functionDeclarations", decls)));
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-goog-api-key", s.apiKey);
        String url = geminiBase(s.baseUrl) + "/v1beta/models/" + stripModelsPrefix(s.model) + ":generateContent";
        JSONObject res = new JSONObject(http("POST", url, headers, body.toString()));

        JSONArray candidates = res.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            JSONObject fb = res.optJSONObject("promptFeedback");
            String reason = fb != null ? fb.optString("blockReason", "") : "";
            throw new AiException(-1, reason.isEmpty() ? "Gemini returned no candidates." : "Gemini blocked the prompt: " + reason);
        }
        JSONObject cand = candidates.getJSONObject(0);
        JSONObject contentObj = cand.optJSONObject("content");
        JSONArray parts = contentObj != null ? contentObj.optJSONArray("parts") : null;
        if (parts == null || parts.length() == 0) {
            String fr = cand.optString("finishReason", "");
            throw new AiException(-1, "Gemini returned no content" + (fr.isEmpty() ? "." : " (finish reason: " + fr + ")."));
        }

        StringBuilder text = new StringBuilder();
        List<AiMessage.ToolCall> calls = new ArrayList<>();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject p = parts.getJSONObject(i);
            JSONObject fc = p.optJSONObject("functionCall");
            if (fc != null) {
                JSONObject args = fc.optJSONObject("args");
                calls.add(new AiMessage.ToolCall(uniqueId("gemini"), fc.optString("name", ""), args != null ? args : new JSONObject()));
            } else if (p.has("text") && !p.optBoolean("thought", false)) {
                text.append(p.optString("text", ""));
            }
        }
        return AiMessage.assistant(text.toString(), calls, parts);
    }

    // ------------------------------------------------------------ list models

    /** @return model ids offered by the provider, sorted. */
    @NonNull
    public static List<String> listModels(@NonNull Settings s) throws AiException {
        try {
            List<String> out = new ArrayList<>();
            Map<String, String> headers = new LinkedHashMap<>();
            if (s.provider == Provider.ANTHROPIC) {
                headers.put("x-api-key", s.apiKey);
                headers.put("anthropic-version", ANTHROPIC_VERSION);
                JSONObject res = new JSONObject(http("GET", anthropicBase(s.baseUrl) + "/models?limit=100", headers, null));
                JSONArray data = res.optJSONArray("data");
                if (data != null) for (int i = 0; i < data.length(); i++) out.add(data.getJSONObject(i).optString("id", ""));
            } else if (s.provider == Provider.GEMINI) {
                headers.put("x-goog-api-key", s.apiKey);
                JSONObject res = new JSONObject(http("GET", geminiBase(s.baseUrl) + "/v1beta/models?pageSize=200", headers, null));
                JSONArray data = res.optJSONArray("models");
                if (data != null) {
                    for (int i = 0; i < data.length(); i++) {
                        JSONObject m = data.getJSONObject(i);
                        JSONArray methods = m.optJSONArray("supportedGenerationMethods");
                        boolean ok = methods == null;
                        for (int j = 0; methods != null && j < methods.length(); j++) {
                            if ("generateContent".equals(methods.optString(j))) ok = true;
                        }
                        if (ok) out.add(stripModelsPrefix(m.optString("name", "")));
                    }
                }
            } else {
                if (!s.apiKey.isEmpty()) headers.put("Authorization", "Bearer " + s.apiKey);
                JSONObject res = new JSONObject(http("GET", trimBase(s.baseUrl) + "/models", headers, null));
                JSONArray data = res.optJSONArray("data");
                if (data != null) for (int i = 0; i < data.length(); i++) out.add(data.getJSONObject(i).optString("id", ""));
            }
            out.removeAll(Collections.singleton(""));
            Collections.sort(out);
            return out;
        } catch (JSONException e) {
            throw new AiException(-1, "Unexpected response from the provider: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- helpers

    private static final java.util.concurrent.atomic.AtomicLong sIds = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Gemini sends no call ids and some OpenAI-compatible servers omit them. Ids index the tool cards in the chat,
     * so a per-response counter ("gemini_0" every turn) made later turns overwrite earlier cards.
     */
    @NonNull
    private static String uniqueId(@NonNull String prefix) {
        return prefix + "_" + Long.toString(System.currentTimeMillis(), 36) + "_" + sIds.incrementAndGet();
    }

    @NonNull
    static JSONObject parseArgs(@Nullable Object raw) {
        if (raw instanceof JSONObject) return (JSONObject) raw;
        if (raw instanceof String && !((String) raw).trim().isEmpty()) {
            try {
                return new JSONObject((String) raw);
            } catch (JSONException ignored) {
                // Fall through: the model produced malformed arguments; the tool will report the missing fields.
            }
        }
        return new JSONObject();
    }

    @NonNull
    static String trimBase(@NonNull String base) {
        String b = base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    @NonNull
    private static String anthropicBase(@NonNull String base) {
        String b = trimBase(base.isEmpty() ? "https://api.anthropic.com" : base);
        return b.endsWith("/v1") ? b : b + "/v1";
    }

    @NonNull
    private static String geminiBase(@NonNull String base) {
        return trimBase(base.isEmpty() ? "https://generativelanguage.googleapis.com" : base);
    }

    @NonNull
    private static String stripModelsPrefix(@NonNull String model) {
        return model.startsWith("models/") ? model.substring("models/".length()) : model;
    }

    @NonNull
    private static String http(@NonNull String method, @NonNull String url, @NonNull Map<String, String> headers,
                               @Nullable String body) throws AiException {
        int attempt = 0;
        while (true) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                sActive = c;
                // A redirect would resend the API key header (x-api-key and friends) to whatever host answers.
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(20000);
                c.setReadTimeout(180000);
                c.setRequestMethod(method);
                c.setRequestProperty("Accept", "application/json");
                for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
                if (body != null) {
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    OutputStream out = c.getOutputStream();
                    out.write(body.getBytes("UTF-8"));
                    out.close();
                }
                int code = c.getResponseCode();
                String text = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
                if (code >= 200 && code < 300) return text;
                if (code >= 300 && code < 400) {
                    throw new AiException(code, "The server redirected the request (HTTP " + code + "). "
                        + "Use the final https:// base URL in settings; redirects are not followed so your key is never forwarded.");
                }
                boolean transientError = code == 429 || code == 502 || code == 503 || code == 529;
                if (transientError && attempt < 2) {
                    attempt++;
                    try {
                        Thread.sleep(2000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new AiException(-1, "Cancelled");
                    }
                    continue;
                }
                throw new AiException(code, extractError(text, code));
            } catch (IOException e) {
                throw new AiException(-1, "Network error: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            } finally {
                if (sActive == c) sActive = null;
                if (c != null) c.disconnect();
            }
        }
    }

    @NonNull
    private static String readAll(@Nullable InputStream in) throws IOException {
        if (in == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"), 8192);
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        try {
            while ((n = r.read(buf)) != -1) {
                sb.append(buf, 0, n);
                // A wrong base URL can answer with an enormous page; stop before it exhausts memory.
                if (sb.length() > MAX_RESPONSE_CHARS) throw new IOException("the response is larger than 8 MB");
            }
        } finally {
            r.close();
        }
        return sb.toString();
    }

    @NonNull
    static String extractError(@NonNull String body, int code) {
        String message = null;
        try {
            String t = body.trim();
            JSONObject o = t.startsWith("[") ? new JSONArray(t).optJSONObject(0) : new JSONObject(t);
            if (o != null) {
                Object err = o.opt("error");
                if (err instanceof JSONObject) message = ((JSONObject) err).optString("message", null);
                else if (err instanceof String) message = (String) err;
                if (message == null) message = o.optString("message", null);
            }
        } catch (JSONException ignored) {
            // Not JSON: fall back to the raw body below.
        }
        if (message == null || message.isEmpty()) {
            message = body.trim().isEmpty() ? "no details" : body.trim();
            if (message.length() > 300) message = message.substring(0, 300) + "…";
        }
        String hint = "";
        if (code == 401 || code == 403) hint = " (check your API key)";
        else if (code == 404) hint = " (check the base URL and model name)";
        else if (code == 429) hint = " (rate limited or out of quota)";
        return "HTTP " + code + hint + ": " + message;
    }
}
