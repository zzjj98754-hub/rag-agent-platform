package com.example.demo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

/** Minimal real Elasticsearch HTTP writer; disabled by default so local fallback stays dependency-free. */
@Component
public class ElasticsearchChunkIndexer {
    private final boolean enabled; private final String baseUrl; private final String index;
    private final ObjectMapper json; private final HttpClient client = HttpClient.newHttpClient();
    private final java.util.concurrent.ConcurrentHashMap<String, String> textCache = new java.util.concurrent.ConcurrentHashMap<>();
    public ElasticsearchChunkIndexer(@Value("${app.index.elasticsearch.enabled}") boolean enabled,
            @Value("${app.vector-store.backend}") String vectorBackend,
            @Value("${app.index.elasticsearch.base-url}") String baseUrl,
            @Value("${app.index.elasticsearch.index}") String index, ObjectMapper json) {
        this.enabled = enabled || "elasticsearch".equalsIgnoreCase(vectorBackend);
        this.baseUrl = baseUrl.replaceAll("/$", ""); this.index = index; this.json = json;
    }
    @PostConstruct
    void ensureIndex() {
        if (!enabled) return;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + index))
                    .timeout(Duration.ofSeconds(5)).PUT(HttpRequest.BodyPublishers.ofString(mapping()))
                    .header("Content-Type", "application/json").build();
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status >= 300 && status != 400) throw new IllegalStateException("Elasticsearch mapping rejected: " + status);
        } catch (Exception ex) { throw new IllegalStateException("Elasticsearch mapping initialization failed", ex); }
    }
    public void index(String id, DocumentRegistry.ChunkMeta chunk) {
        if (!enabled) return;
        try {
            String body = json.writeValueAsString(Map.of(
                    "chunkId", id, "documentId", documentId(id), "documentVersion", 1,
                    "childText", chunk.text(), "parentId", chunk.parentId(), "parentText", chunk.parentText(),
                    "contentHash", "unknown", "embeddingModel", "unknown"));
            textCache.put(id, chunk.text());
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + index + "/_doc/" + java.net.URLEncoder.encode(id, java.nio.charset.StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(body)).build();
            if (client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() >= 300) throw new IllegalStateException("Elasticsearch rejected chunk");
        } catch (Exception ex) { throw new IllegalStateException("Elasticsearch index failed", ex); }
    }
    public String text(String id) { return textCache.get(id); }
    public java.util.List<String> searchText(String query, int topK) {
        if (!enabled) return java.util.List.of();
        try {
            String body = json.writeValueAsString(Map.of("size", topK, "query", Map.of("match", Map.of("childText", query))));
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + index + "/_search"))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            JsonNode root = json.readTree(client.send(request, HttpResponse.BodyHandlers.ofString()).body());
            java.util.List<String> ids = new java.util.ArrayList<>();
            root.path("hits").path("hits").forEach(hit -> ids.add(hit.path("_source").path("chunkId").asText(hit.path("_id").asText())));
            return ids;
        } catch (Exception ex) { throw new IllegalStateException("Elasticsearch BM25 search failed", ex); }
    }
    private String documentId(String chunkId) {
        int separator = chunkId.indexOf('#');
        return separator < 0 ? chunkId : chunkId.substring(0, separator);
    }
    private String mapping() {
        return """
                {"mappings":{"properties":{
                  "chunkId":{"type":"keyword"},"documentId":{"type":"keyword"},
                  "documentVersion":{"type":"integer"},"childText":{"type":"text"},
                  "parentId":{"type":"keyword"},"parentText":{"type":"text"},
                  "contentHash":{"type":"keyword"},"embeddingModel":{"type":"keyword"},
                  "embedding":{"type":"dense_vector","dims":1024,"index":true,"similarity":"cosine"}
                }}}
                """;
    }
}
