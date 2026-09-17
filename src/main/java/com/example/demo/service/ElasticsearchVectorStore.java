package com.example.demo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Elasticsearch KNN store. It is selected only when app.vector-store.backend=elasticsearch. */
@Component("elasticsearchVectorStore")
@ConditionalOnProperty(prefix = "app.vector-store", name = "backend", havingValue = "elasticsearch")
public class ElasticsearchVectorStore implements VectorStore {
    private final String baseUrl;
    private final String index;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();

    public ElasticsearchVectorStore(@Value("${app.index.elasticsearch.base-url}") String baseUrl,
            @Value("${app.index.elasticsearch.index}") String index, ObjectMapper mapper) {
        this.baseUrl = baseUrl.replaceAll("/$", ""); this.index = index; this.mapper = mapper;
    }

    @Override public void add(String id, String text, float[] embedding, int dimension, String modelName,
            String parentId, String parentText) {
        try {
            var body = mapper.createObjectNode();
            body.put("chunkId", id); body.put("documentId", id.contains("#") ? id.substring(0, id.indexOf('#')) : id);
            body.put("documentVersion", 1); body.put("childText", text); body.put("parentId", parentId);
            body.put("parentText", parentText); body.put("embeddingModel", modelName);
            var vector = body.putArray("embedding"); for (float value : embedding) vector.add(value);
            request("PUT", "/" + index + "/_doc/" + encode(id), body.toString());
        } catch (Exception e) { throw new IllegalStateException("Elasticsearch vector write failed", e); }
    }

    @Override public List<Result> search(float[] queryEmbedding, int topK) {
        try {
            var query = mapper.createObjectNode();
            var knn = query.putObject("knn"); knn.put("field", "embedding"); knn.put("k", topK); knn.put("num_candidates", Math.max(topK * 4, 50));
            var vector = knn.putArray("query_vector"); for (float value : queryEmbedding) vector.add(value);
            var root = mapper.readTree(request("POST", "/" + index + "/_search", query.toString()));
            List<Result> results = new ArrayList<>();
            for (JsonNode hit : root.path("hits").path("hits")) {
                JsonNode source = hit.path("_source");
                results.add(new Result(source.path("chunkId").asText(hit.path("_id").asText()), source.path("childText").asText(),
                        hit.path("_score").asDouble(), source.path("parentId").asText(null), source.path("parentText").asText(null)));
            }
            return results;
        } catch (Exception e) { throw new IllegalStateException("Elasticsearch vector search failed", e); }
    }

    @Override public void delete(String id) { try { request("DELETE", "/" + index + "/_doc/" + encode(id), ""); } catch (Exception e) { throw new IllegalStateException("Elasticsearch vector delete failed", e); } }
    @Override public int size() { return 0; }
    private String request(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(10));
        if ("POST".equals(method)) builder.POST(HttpRequest.BodyPublishers.ofString(body));
        else if ("PUT".equals(method)) builder.PUT(HttpRequest.BodyPublishers.ofString(body));
        else builder.DELETE();
        builder.header("Content-Type", "application/json");
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300 && response.statusCode() != 404) throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
        return response.body();
    }
    private String encode(String value) { return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8); }
}
