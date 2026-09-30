package com.example.springaiagents.agents;

/**
 * Carries the current top-level conversation id onto the thread executing a chat
 * request, so that delegated sub-agents can scope their own persisted chat memory
 * to the same conversation without the model having to pass it as a tool argument.
 */
public final class ConversationContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private ConversationContext() {
    }

    public static void set(String conversationId) {
        CURRENT.set(conversationId);
    }

    public static String get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
