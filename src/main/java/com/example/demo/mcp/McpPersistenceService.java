package com.example.demo.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** MySQL truth source for MCP server definitions. */
@Service
public class McpPersistenceService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final McpSecretCipher cipher;

    public McpPersistenceService(
            JdbcTemplate jdbc, ObjectMapper mapper, McpSecretCipher cipher) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.cipher = cipher;
    }

    @Transactional
    public void save(McpServerDefinition definition) {
        jdbc.update("""
                INSERT INTO mcp_server
                    (name, transport, command, command_args, url,
                     auth_header_enc, enabled, connection_status, update_time)
                VALUES (?, ?, ?, CAST(? AS JSON), ?, ?, ?, 'UNKNOWN', NOW(3))
                ON DUPLICATE KEY UPDATE
                    transport=VALUES(transport), command=VALUES(command),
                    command_args=VALUES(command_args), url=VALUES(url),
                    auth_header_enc=VALUES(auth_header_enc), enabled=VALUES(enabled),
                    connection_status='UNKNOWN', last_error=NULL, update_time=NOW(3)
                """,
                definition.name(),
                definition.transport().name(),
                definition.command(),
                write(definition.commandArgs()),
                definition.url(),
                cipher.encrypt(definition.authHeader()),
                definition.enabled());
    }

    public List<McpServerDefinition> findEnabled() {
        return jdbc.query("""
                SELECT name, transport, command, command_args, url,
                       auth_header_enc, enabled
                FROM mcp_server WHERE enabled=TRUE ORDER BY id
                """, (rs, row) -> new McpServerDefinition(
                rs.getString("name"),
                McpServerDefinition.Transport.valueOf(rs.getString("transport")),
                rs.getString("command"),
                readArgs(rs.getString("command_args")),
                rs.getString("url"),
                cipher.decrypt(rs.getString("auth_header_enc")),
                rs.getBoolean("enabled")));
    }

    public void updateStatus(String name, String status, String error) {
        jdbc.update("""
                UPDATE mcp_server SET connection_status=?, last_error=?,
                    last_connected_at=IF(?='CONNECTED', NOW(3), last_connected_at),
                    update_time=NOW(3) WHERE name=?
                """, status, truncate(error), status, name);
    }

    public void delete(String name) {
        jdbc.update("DELETE FROM mcp_server WHERE name=?", name);
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid MCP configuration", ex);
        }
    }

    private List<String> readArgs(String json) {
        try {
            return json == null ? List.of() : mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid persisted MCP command arguments", ex);
        }
    }

    private String truncate(String value) {
        return value == null || value.length() <= 2000
                ? value
                : value.substring(0, 2000);
    }
}
