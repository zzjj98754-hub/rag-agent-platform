package com.example.demo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.demo.agent.AgentExecutionListener;
import com.example.demo.agent.AgentExecutor;
import com.example.demo.agent.AgentResult;
import com.example.demo.agent.tool.ToolResult;
import com.example.demo.agent.tool.ToolScheduler.ToolCallRecord;
import com.example.demo.agent.tool.ToolRegistry;
import com.example.demo.controller.AgentController;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AgentStreamingServiceTest {
    @Test
    void shouldEmitRedactedMcpLifecycleEvents() throws Exception {
        AgentExecutor executor = mock(AgentExecutor.class);
        when(executor.execute(eq("add two numbers"), isNull(), any(AgentExecutionListener.class)))
                .thenAnswer(invocation -> {
                    AgentExecutionListener listener = invocation.getArgument(2);
                    listener.onToolStart("call-1", "demo00-http-example.add", Map.of("left", 1));
                    listener.onToolComplete(ToolCallRecord.success("call-1", "demo00-http-example.add",
                            Map.of("left", 1), ToolResult.ok("demo00-http-example.add", "3", 12)));
                    return new AgentResult("session-1", "3", AgentResult.Status.COMPLETED, 1, List.of());
                });
        AgentStreamingService service = new AgentStreamingService(executor, Runnable::run);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AgentController(
                executor, mock(ToolCallService.class), mock(ToolRegistry.class), service)).build();

        String body = mvc.perform(MockMvcRequestBuilders.get("/agent/chat/stream")
                        .param("query", "add two numbers"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("event:mcp_tool", "demo00-http-example", "STARTED", "SUCCEEDED");
        assertThat(body).contains("event:done", "totalElapsedMs");
    }
}
