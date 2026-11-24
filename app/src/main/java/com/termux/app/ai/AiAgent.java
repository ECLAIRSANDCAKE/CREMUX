package com.termux.app.ai;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The model / tool loop: ask the model, run the tools it requests (after confirmation where needed),
 * feed the results back, repeat until it answers in plain text. Synchronous: run it on a worker thread.
 * Every callback is invoked on that worker thread.
 */
public final class AiAgent {

    public static final class ToolOutcome {
        @NonNull public final String content;
        public final boolean isError;

        public ToolOutcome(@NonNull String content, boolean isError) {
            this.content = content;
            this.isError = isError;
        }
    }

    public interface Tools {
        @NonNull List<AiTool> definitions();

        boolean requiresConfirmation(@NonNull AiMessage.ToolCall call);

        /** Must return promptly once {@code cancelled} becomes true. */
        @NonNull ToolOutcome run(@NonNull AiMessage.ToolCall call, @NonNull AtomicBoolean cancelled);

        /** Text appended to the system prompt on every request (saved notes). Null for none. */
        @Nullable
        default String extraSystem() {
            return null;
        }
    }

    public interface Host {
        void onAssistantMessage(@NonNull AiMessage message);

        /** Blocks until the user decides. @return true to run the call. */
        boolean confirm(@NonNull AiMessage.ToolCall call) throws InterruptedException;

        void onToolResult(@NonNull AiMessage.ToolCall call, @NonNull AiMessage result);

        /** @param error null when the model finished normally. */
        void onFinished(@Nullable String error);
    }

    private AiAgent() { }

    /** Tool results older than this many messages are shortened when sent again. */
    private static final int KEEP_RECENT = 10;
    private static final int OLD_RESULT_MAX = 1200;

    /**
     * Every request resends the whole conversation, and tool output is up to 12 000 characters each. Shortening
     * the old results keeps requests small and cheap without touching what the user sees (a copy is sent).
     */
    @NonNull
    static List<AiMessage> trimOld(@NonNull List<AiMessage> history) {
        int size = history.size();
        if (size <= KEEP_RECENT) return history;
        List<AiMessage> out = new java.util.ArrayList<>(size);
        int limit = size - KEEP_RECENT;
        for (int i = 0; i < size; i++) {
            AiMessage m = history.get(i);
            if (i < limit && AiMessage.TOOL.equals(m.role) && m.text.length() > OLD_RESULT_MAX && m.toolCallId != null) {
                String cut = m.text.substring(0, OLD_RESULT_MAX / 2) + "\n… [older output shortened] …\n"
                    + m.text.substring(m.text.length() - OLD_RESULT_MAX / 2);
                out.add(AiMessage.toolResult(m.toolCallId, m.toolName == null ? "tool" : m.toolName, cut, m.isError));
            } else {
                out.add(m);
            }
        }
        return out;
    }

    /**
     * @param history the shared conversation; this method appends the assistant and tool messages to it.
     */
    public static void run(@NonNull AiClient.Settings settings, @Nullable String system, @NonNull List<AiMessage> history,
                           @NonNull Tools tools, @NonNull Host host, int maxSteps, @NonNull AtomicBoolean cancelled) {
        List<AiTool> defs = tools.definitions();
        final String baseSystem = system;
        for (int step = 0; step < maxSteps; step++) {
            if (cancelled.get()) {
                host.onFinished("Cancelled");
                return;
            }
            AiMessage reply;
            try {
                String extra = tools.extraSystem(); // re-read each step: save_note may have just changed it
                String sys = extra == null || extra.isEmpty() ? baseSystem : (baseSystem == null ? "" : baseSystem + "\n\n") + extra;
                reply = AiClient.chat(settings, sys, trimOld(history), defs);
            } catch (AiClient.AiException e) {
                host.onFinished(cancelled.get() ? "Cancelled" : e.getMessage());
                return;
            } catch (RuntimeException e) {
                host.onFinished("Unexpected error: " + e);
                return;
            }
            if (cancelled.get()) {
                // The user pressed Stop while the request was in flight: drop the late reply.
                host.onFinished("Cancelled");
                return;
            }
            history.add(reply);
            host.onAssistantMessage(reply);
            if (reply.toolCalls.isEmpty()) {
                host.onFinished(null);
                return;
            }

            for (AiMessage.ToolCall call : reply.toolCalls) {
                ToolOutcome outcome;
                if (cancelled.get()) {
                    outcome = new ToolOutcome("Cancelled by the user before this ran.", true);
                } else {
                    boolean allowed;
                    try {
                        allowed = !tools.requiresConfirmation(call) || host.confirm(call);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        allowed = false;
                        cancelled.set(true);
                    }
                    if (allowed) {
                        try {
                            outcome = tools.run(call, cancelled);
                        } catch (RuntimeException e) {
                            outcome = new ToolOutcome("Tool crashed: " + e, true);
                        }
                    } else {
                        outcome = new ToolOutcome("The user did not allow this command to run. Do not retry it; "
                            + "ask what they would like instead.", true);
                    }
                }
                AiMessage result = AiMessage.toolResult(call.id, call.name, outcome.content, outcome.isError);
                history.add(result);
                host.onToolResult(call, result);
            }
        }
        host.onFinished("Stopped after " + maxSteps + " tool steps. Send a message to let it continue.");
    }
}
