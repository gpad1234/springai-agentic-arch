package com.example.springaiagents;

import com.example.springaiagents.agents.ConversationContext;
import com.example.springaiagents.agents.TaskDelegationTools;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/claude")
public class ClaudeController {

    private final ChatClient chatClient;

    public ClaudeController(AnthropicChatModel chatModel, ChatMemoryRepository chatMemoryRepository,
            TaskDelegationTools taskDelegationTools) {
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .build();
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("""
                        You are the orchestrator agent. Answer simple, conversational questions
                        directly. For anything that requires reading/writing files, running shell
                        commands, or searching a workspace, delegate it with the delegateToSubAgent
                        tool: use agentType 'research' for read-only search/lookups, or 'code' for
                        file edits and shell commands. Summarize the sub-agent's result for the user.
                        """)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultTools(new AgentTools(), taskDelegationTools)
                .build();
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        if (request.prompt() == null || request.prompt().isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }

        String conversationId = request.conversationId() == null || request.conversationId().isBlank()
                ? "default"
                : request.conversationId();

        ConversationContext.set(conversationId);
        try {
            String content = chatClient.prompt()
                    .user(request.prompt())
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .call()
                    .content();

            return new ChatResponse(content);
        } finally {
            ConversationContext.clear();
        }
    }

    public record ChatRequest(String prompt, String conversationId) {
    }

    public record ChatResponse(String response) {
    }
}