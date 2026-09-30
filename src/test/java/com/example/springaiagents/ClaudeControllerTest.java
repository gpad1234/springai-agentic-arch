package com.example.springaiagents;

import com.example.springaiagents.agents.TaskDelegationTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.servlet.ServletException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ClaudeController.class)
class ClaudeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AnthropicChatModel chatModel;

    @MockBean
    private ChatMemoryRepository chatMemoryRepository;

    @MockBean
    private TaskDelegationTools taskDelegationTools;

    @Test
    void returnsClaudeResponse() throws Exception {
        var assistantMessage = new AssistantMessage("Hello from Claude");
        given(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
            .willReturn(new ChatResponse(List.of(new Generation(assistantMessage))));

        mockMvc.perform(post("/api/claude/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"Say hello\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"response\":\"Hello from Claude\"}"));
    }

    @Test
    void rejectsBlankPrompt() throws Exception {
        var exception = assertThrows(ServletException.class, () ->
            mockMvc.perform(post("/api/claude/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"prompt\":\" \"}")));

        org.junit.jupiter.api.Assertions.assertInstanceOf(
            IllegalArgumentException.class, exception.getCause());
    }
}