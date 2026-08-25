package com.example.demo.agent;

import com.example.demo.agent.tool.ToolScheduler.ToolCallRecord;
import java.util.Map;

/** Optional execution observer used by transport adapters; it never changes Agent decisions. */
public interface AgentExecutionListener {
    AgentExecutionListener NOOP = new AgentExecutionListener() { };

    default void onToolStart(String toolCallId, String toolName, Map<String, Object> arguments) { }

    default void onToolComplete(ToolCallRecord record) { }
}
