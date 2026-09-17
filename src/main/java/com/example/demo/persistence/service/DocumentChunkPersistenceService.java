package com.example.demo.persistence.service;

import com.example.demo.rag.Chunk;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable Small-to-Big mapping; retrieval remains in-memory for a compact local demo. */
@Service
public class DocumentChunkPersistenceService {
    private final JdbcTemplate jdbc;
    public DocumentChunkPersistenceService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public void replace(long documentId, List<Chunk> chunks) {
        jdbc.update("DELETE FROM document_chunk WHERE document_id=?", documentId);
        for (Chunk chunk : chunks) {
            jdbc.update("INSERT INTO document_chunk (document_id, chunk_key, parent_chunk_key, chunk_text, parent_text, sequence_no) VALUES (?, ?, ?, ?, ?, ?)", documentId, chunk.id(), chunk.parentId(),
                    chunk.text(), chunk.parentText(), chunk.chunkIndex());
        }
    }

    public int count(long documentId) {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM document_chunk WHERE document_id=?", Integer.class, documentId);
        return value == null ? 0 : value;
    }
}
