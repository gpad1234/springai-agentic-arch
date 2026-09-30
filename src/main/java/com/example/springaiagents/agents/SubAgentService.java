package com.example.springaiagents.agents;

import com.example.springaiagents.tools.FileTools;
import com.example.springaiagents.tools.SearchTools;
import com.example.springaiagents.tools.ShellTool;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Runs isolated-context sub-agents, each with its own system prompt and a
 * restricted toolset, matching the "Sub-Agents" layer of the multi-agent
 * architecture: a research agent for search/read-only lookups, and a code
 * agent that can read/write files and run sandboxed shell commands. Both share
 * the same persistent {@link ChatMemoryRepository} as the orchestrator, keyed
 * by a conversation id scoped to the parent conversation and agent type.
 */
@Service
public class SubAgentService {

    private final ChatMemory chatMemory;
    private final ChatClient researchAgent;
    private final ChatClient codeAgent;

    public SubAgentService(AnthropicChatModel chatModel, ChatMemoryRepository chatMemoryRepository,
            FileTools fileTools, SearchTools searchTools, ShellTool shellTool) {
        this.chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .build();

        this.researchAgent = ChatClient.builder(chatModel)
                .defaultSystem("""
                        You are a focused research sub-agent running in an isolated context.
                        Use the grep/glob search tools and file reads to find information inside
                        the sandboxed workspace, then report a concise summary of your findings
                        back to the orchestrator. You cannot write files or run shell commands.
                        """)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultTools(searchTools, fileTools)
                .build();

        this.codeAgent = ChatClient.builder(chatModel)
                .defaultSystem("""
                        You are a focused code sub-agent running in an isolated context.
                        Use the file and shell tools to read, write, and inspect files inside
                        the sandboxed workspace to complete the requested task. Report back a
                        concise summary of what you did and any relevant output.
                        """)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultTools(fileTools, shellTool, searchTools)
                .build();
    }

    public String run(SubAgentType type, String task) {
        ChatClient client = switch (type) {
            case RESEARCH -> researchAgent;
            case CODE -> codeAgent;
        };
        return client.prompt()
                .user(task)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, subConversationId(type)))
                .call()
                .content();
    }

    /** Scopes each sub-agent's persisted memory to the parent conversation it was delegated from. */
    private String subConversationId(SubAgentType type) {
        String parent = ConversationContext.get();
        String base = (parent == null || parent.isBlank()) ? "default" : parent;
        return base + ":" + type.name().toLowerCase(Locale.ROOT);
    }
}

