package com.example.demo.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import com.example.demo.security.UserRole;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Small, real Spring AI Alibaba Graph adapter kept behind an explicit feature flag.
 * The existing MySQL-backed graph remains the durable fallback until a checkpoint
 * saver is selected for the deployment.
 */
@Service
@ConditionalOnProperty(prefix = "app.graph.alibaba", name = "enabled", havingValue = "true")
public class AlibabaRagApprovalGraphService {
    private final CompiledGraph graph;

    public AlibabaRagApprovalGraphService(DataSource dataSource) {
        try {
            StateGraph builder = new StateGraph();
            AsyncNodeAction plan = state -> CompletableFuture.completedFuture(
                    Map.of("currentNode", "plan", "status", "PLANNED"));
            AsyncNodeAction approval = state -> CompletableFuture.completedFuture(
                    Map.of("currentNode", "approval", "status",
                            Boolean.TRUE.equals(state.value("approved", false)) ? "APPROVED" : "WAITING_APPROVAL"));
            AsyncNodeAction verify = state -> CompletableFuture.completedFuture(
                    Map.of("currentNode", "verify", "status", "SUCCEEDED"));
            builder.addNode("plan", plan)
                    .addNode("approval", approval)
                    .addNode("verify", verify)
                    .addEdge(StateGraph.START, "plan")
                    .addEdge("plan", "approval")
                    .addConditionalEdges("approval",
                            state -> CompletableFuture.completedFuture(
                                    Boolean.TRUE.equals(state.value("approved", false)) ? "verify" : "wait"),
                            Map.of("verify", "verify", "wait", StateGraph.END))
                    .addEdge("verify", StateGraph.END);
            if (dataSource == null) {
                this.graph = builder.compile();
            } else {
                MysqlSaver saver = MysqlSaver.builder().dataSource(dataSource).build();
                this.graph = builder.compile(CompileConfig.builder()
                        .saverConfig(SaverConfig.builder().register(saver).build())
                        .build());
            }
        } catch (Exception e) {
            throw new IllegalStateException("无法构建 Spring AI Alibaba Graph", e);
        }
    }

    public Map<String, Object> run(String query, boolean approved) {
        String runId = UUID.randomUUID().toString();
        RunnableConfig config = RunnableConfig.builder().threadId(runId).build();
        return graph.invoke(Map.of("runId", runId, "query", query, "approved", approved), config)
                .orElseThrow(() -> new IllegalStateException("Graph 未返回状态")).data();
    }

    public Map<String, Object> approve(String runId) {
        try {
            RunnableConfig config = RunnableConfig.builder().threadId(runId).build();
            graph.updateState(config, Map.of("approved", true));
            return graph.invoke(Map.of("approved", true), config)
                    .orElseThrow(() -> new IllegalStateException("Graph 审批恢复未返回状态")).data();
        } catch (Exception e) {
            throw new IllegalStateException("Graph 审批恢复失败", e);
        }
    }

    public RagGraphState start(String query, boolean highRisk, long ownerId, UserRole role) {
        return toState(run(query, !highRisk), ownerId, highRisk);
    }

    public RagGraphState approveState(String runId, boolean approved, long ownerId, UserRole role) {
        if (!approved) {
            Map<String, Object> current = state(runId);
            return new RagGraphState(runId, String.valueOf(current.getOrDefault("query", "")),
                    "REJECTED", "human_approval", true, "官方 Graph 审批拒绝", List.of(),
                    null, "审批拒绝", List.of("approval:rejected"));
        }
        return toState(approve(runId), ownerId, true);
    }

    public Map<String, Object> state(String runId) {
        try {
            RunnableConfig config = RunnableConfig.builder().threadId(runId).build();
            return graph.getState(config).state().data();
        } catch (Exception e) {
            throw new IllegalArgumentException("Graph run 不存在", e);
        }
    }

    private RagGraphState toState(Map<String, Object> data, long ownerId, boolean highRisk) {
        String status = String.valueOf(data.getOrDefault("status", "RUNNING"));
        return new RagGraphState(String.valueOf(data.get("runId")),
                String.valueOf(data.getOrDefault("query", "")), status,
                String.valueOf(data.getOrDefault("currentNode", "plan")), highRisk,
                "官方 Spring AI Alibaba Graph 审批流程", List.of(), null,
                "SUCCEEDED".equals(status) ? "官方 Graph 已验证" : null,
                List.of("official_state_graph", "mysql_checkpoint"));
    }
}
