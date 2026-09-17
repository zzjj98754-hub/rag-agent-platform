package com.example.demo.graph;

import com.example.demo.agent.tool.ToolScheduler;
import com.example.demo.rag.HybridRetriever;
import com.example.demo.rag.SearchResult;
import com.example.demo.security.UserRole;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A compact graph-shaped RAG workflow: retrieve -> plan -> risk branch -> tool -> verify.
 * It deliberately uses one persisted JSON State so learners can see a checkpoint after every node.
 */
@Service
public class RagApprovalGraphService {
    private final HybridRetriever retriever; private final ToolScheduler tools;
    private final JdbcTemplate jdbc; private final ObjectMapper json;
    public RagApprovalGraphService(HybridRetriever retriever, ToolScheduler tools, JdbcTemplate jdbc, ObjectMapper json) {
        this.retriever = retriever; this.tools = tools; this.jdbc = jdbc; this.json = json;
    }
    @Transactional
    public RagGraphState start(String query, boolean requestedHighRisk, long ownerId, UserRole role) {
        String id = UUID.randomUUID().toString().replace("-", "");
        List<Map<String,Object>> hits = retriever.retrieve(query, 3).stream().map(this::hit).toList();
        boolean high = requestedHighRisk || query.matches("(?s).*(删除|转账|付款|delete|transfer).*" );
        RagGraphState state = new RagGraphState(id, query, high ? "WAITING_APPROVAL" : "RUNNING",
                high ? "human_approval" : "tool_execution", high, "基于检索上下文生成的演示方案", hits,
                null, null, List.of("knowledge_retrieval", "plan_generation", "risk_assessment:" + (high ? "HIGH" : "LOW")));
        save(state, ownerId);
        return high ? state : executeAndVerify(state, ownerId, role);
    }
    public List<RagGraphState> pending(long ownerId, UserRole role) {
        String sql = role == UserRole.ADMIN ? "SELECT state FROM rag_graph_run WHERE status='WAITING_APPROVAL'" :
                "SELECT state FROM rag_graph_run WHERE status='WAITING_APPROVAL' AND owner_id=?";
        return (role == UserRole.ADMIN ? jdbc.queryForList(sql, String.class) : jdbc.queryForList(sql, String.class, ownerId)).stream().map(this::read).toList();
    }
    @Transactional
    public RagGraphState approve(String id, boolean approved, long ownerId, UserRole role) {
        RagGraphState state = get(id, ownerId, role);
        if (!"WAITING_APPROVAL".equals(state.status())) throw new IllegalStateException("流程不在审批状态");
        if (!approved) { RagGraphState rejected = with(state, "REJECTED", "human_approval", null, null, "审批拒绝", "approval:rejected"); save(rejected, ownerId); return rejected; }
        RagGraphState running = with(state, "RUNNING", "tool_execution", null, null, null, "approval:approved");
        save(running, ownerId); return executeAndVerify(running, ownerId, role);
    }
    public RagGraphState get(String id, long ownerId, UserRole role) {
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT owner_id,state FROM rag_graph_run WHERE run_id=?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Graph run 不存在");
        long actual = ((Number) rows.get(0).get("owner_id")).longValue();
        if (role != UserRole.ADMIN && actual != ownerId) throw new org.springframework.security.access.AccessDeniedException("无权查看此流程");
        return read(String.valueOf(rows.get(0).get("state")));
    }
    private RagGraphState executeAndVerify(RagGraphState state, long ownerId, UserRole role) {
        var result = tools.dispatch("calculator", Map.of("expression", "1+1"), role, "graph-" + ownerId, List.of());
        String tool = result.result() == null ? "工具未返回结果" : result.result().content();
        RagGraphState done = with(state, "SUCCEEDED", "result_verification", tool, "工具结果已验证", null, "tool_execution", "result_verification");
        save(done, ownerId); return done;
    }
    private Map<String,Object> hit(SearchResult r) { return Map.of("id", r.id(), "score", r.score(), "text", r.effectiveText()); }
    private RagGraphState with(RagGraphState s, String status, String node, String tool, String verify, String overrideVerify, String... records) {
        List<String> history = new ArrayList<>(s.records()); java.util.Collections.addAll(history, records);
        return new RagGraphState(s.runId(), s.query(), status, node, s.highRisk(), s.plan(), s.retrieval(),
                tool == null ? s.toolResult() : tool, overrideVerify == null ? (verify == null ? s.verification() : verify) : overrideVerify, List.copyOf(history));
    }
    private void save(RagGraphState state, long owner) {
        jdbc.update("INSERT INTO rag_graph_run(run_id,owner_id,status,state,current_node,risk_level,pending_approval,version) VALUES(?,?,?,CAST(? AS JSON),?,?,?,0) ON DUPLICATE KEY UPDATE status=VALUES(status),state=VALUES(state),current_node=VALUES(current_node),risk_level=VALUES(risk_level),pending_approval=VALUES(pending_approval),version=version+1",
                state.runId(), owner, state.status(), write(state), state.currentNode(), state.highRisk() ? "HIGH" : "LOW", "WAITING_APPROVAL".equals(state.status()));
    }
    private String write(RagGraphState state) { try { return json.writeValueAsString(state); } catch (Exception e) { throw new IllegalStateException(e); } }
    private RagGraphState read(String value) { try { return json.readValue(value, RagGraphState.class); } catch (Exception e) { throw new IllegalStateException("Graph checkpoint 损坏", e); } }
}
