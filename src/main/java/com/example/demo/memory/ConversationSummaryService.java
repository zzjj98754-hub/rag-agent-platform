package com.example.demo.memory;

import com.example.demo.service.ChatSessionService;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;

/** Sliding-window summary compression with a deterministic local fallback. */
@Service
public class ConversationSummaryService {
    private final ChatSessionService sessions;
    private final int threshold;
    private final JdbcTemplate jdbc;
    private final Map<String, Summary> summaries = new ConcurrentHashMap<>();

    public ConversationSummaryService(ChatSessionService sessions, JdbcTemplate jdbc,
            @Value("${app.session.summary-threshold}") int threshold) {
        this.sessions = sessions;
        this.jdbc = jdbc;
        this.threshold = Math.max(4, threshold);
    }

    public Summary maybeCompress(String sessionId) {
        if (sessionId == null || sessions.getFullHistory(sessionId).size() <= threshold) {
            return summaries.get(sessionId);
        }
        var history = sessions.getFullHistory(sessionId);
        int end = Math.max(1, history.size() - threshold / 2);
        StringBuilder text = new StringBuilder("历史摘要：\n");
        history.subList(0, end).forEach(message -> text.append(message.role())
                .append(": ").append(message.content()).append('\n'));
        Summary summary = new Summary(sessionId, text.toString(), end, Instant.now());
        summaries.put(sessionId, summary);
        try {
            jdbc.update("""
                    INSERT INTO conversation_summary
                        (session_id, summary, message_count, token_count,
                         model_name, create_time, update_time)
                    VALUES (?, ?, ?, ?, 'deterministic-local', NOW(3), NOW(3))
                    ON DUPLICATE KEY UPDATE summary=VALUES(summary),
                        message_count=VALUES(message_count),
                        token_count=VALUES(token_count), model_name=VALUES(model_name),
                        update_time=NOW(3)
                    """, sessionId, summary.text(), end,
                    Math.max(1, summary.text().length() / 2));
        } catch (DataAccessException ignored) {
            // The hot fallback keeps chat available during a database outage.
        }
        return summary;
    }

    public Summary get(String sessionId) {
        try {
            var values = jdbc.query("""
                    SELECT summary, message_count, update_time
                    FROM conversation_summary WHERE session_id=?
                    """, (rs, row) -> new Summary(
                    sessionId, rs.getString("summary"),
                    rs.getInt("message_count"),
                    rs.getTimestamp("update_time").toInstant()), sessionId);
            return values.isEmpty() ? summaries.get(sessionId) : values.get(0);
        } catch (DataAccessException ignored) {
            return summaries.get(sessionId);
        }
    }

    public record Summary(String sessionId, String text, int compressedMessages, Instant updatedAt) {}
}
