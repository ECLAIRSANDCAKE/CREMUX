package com.termux.app.ai;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The current conversation and the running agent. Kept in static fields so the agent keeps going when the screen
 * is recreated (theme change) and a re-opened screen picks up where it was.
 */
public final class AiChatState {

    public static final List<AiMessage> HISTORY = new CopyOnWriteArrayList<>();

    /** The user chose "Allow all this chat": non-risky commands run without asking until a new chat starts. */
    public static volatile boolean allowAll;

    /** The user chose "Allow read-only commands": looking-only commands run without asking in this chat. */
    public static volatile boolean allowReadOnly;

    public static volatile boolean running;
    public static volatile Thread worker;
    public static final AtomicBoolean cancel = new AtomicBoolean(false);

    /** A command waiting for the user's decision. The agent thread blocks on {@link #latch}. */
    static final class Pending {
        final AiMessage.ToolCall call;
        final boolean risky;
        final CountDownLatch latch = new CountDownLatch(1);
        volatile boolean answer;

        Pending(AiMessage.ToolCall call, boolean risky) {
            this.call = call;
            this.risky = risky;
        }

        void decide(boolean allow) {
            answer = allow;
            latch.countDown();
        }
    }

    static volatile Pending pending;

    private AiChatState() { }

    public static void clear() {
        HISTORY.clear();
        allowAll = false;
        allowReadOnly = false;
    }
}
