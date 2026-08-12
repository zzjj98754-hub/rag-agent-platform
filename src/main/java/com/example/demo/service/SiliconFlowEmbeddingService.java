package com.example.demo.service;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * 硅基流动 Embedding API 实现 —— 调用 BGE 模型生成语义向量。
 *
 * API 兼容 OpenAI 格式：
 * POST https://api.siliconflow.cn/v1/embeddings
 * Body: {"model":"BAAI/bge-large-zh-v1.5", "input":"text"}
 * 返回 1024 维归一化向量。
 */
@Component("siliconFlowEmbedding")
public class SiliconFlowEmbeddingService implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(SiliconFlowEmbeddingService.class);

    private final RestTemplate restTemplate;
    private final String apiKey;
    private final String apiUrl;
    private final String model;
    private volatile boolean available = true;

    @Value("${app.embedding.batch-size}")
    private int batchSize;

    @Value("${app.embedding.retry-max}")
    private int retryMax;

    @Value("${app.embedding.retry-backoff-ms}")
    private long retryBackoffMs;

    public SiliconFlowEmbeddingService(
            @Value("${app.embedding.api-key}") String apiKey,
            @Value("${app.embedding.url}") String apiUrl,
            @Value("${app.embedding.model}") String model) {
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
        this.model = model;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public int dimension() {
        return 1024; // BAAI/bge-large-zh-v1.5
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public EmbeddingSource source() {
        return EmbeddingSource.SILICONFLOW;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    public void markUnavailable() {
        available = false;
        log.warn("SiliconFlow Embedding 标记为不可用");
    }

    public void markAvailable() {
        available = true;
        log.info("SiliconFlow Embedding 恢复可用");
    }

    @Override
    public float[] embed(String text) {
        List<float[]> results = callApiWithRetry(List.of(text));
        return results.isEmpty() ? new float[0] : results.get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts.isEmpty()) return List.of();
        List<float[]> all = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += batchSize) {
            int end = Math.min(i + batchSize, texts.size());
            List<float[]> batch = callApiWithRetry(texts.subList(i, end));
            if (batch.isEmpty()) {
                return List.of(); // 一批失败则整体失败，触发降级
            }
            all.addAll(batch);
        }
        return all;
    }

    private List<float[]> callApiWithRetry(List<String> texts) {
        if (apiKey == null || apiKey.isBlank()) {
            available = false;
            log.info("Embedding API Key 未配置，直接使用本地向量降级");
            return List.of();
        }
        for (int attempt = 0; attempt <= retryMax; attempt++) {
            try {
                List<float[]> result = callApi(texts);
                if (!result.isEmpty()) {
                    return result;
                }
            } catch (Exception e) {
                log.warn("Embedding API 调用失败 (attempt {}/{}): {}", attempt + 1, retryMax + 1, e.getMessage());
            }
            if (attempt < retryMax) {
                try {
                    Thread.sleep(retryBackoffMs * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.error("Embedding API 重试 {} 次后仍失败，返回空结果", retryMax + 1);
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<float[]> callApi(List<String> texts) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        Map<String, Object> body = Map.of(
                "model", model,
                "input", texts,
                "encoding_format", "float"
        );

        ResponseEntity<Map> response = restTemplate.postForEntity(
                apiUrl,
                new HttpEntity<>(body, headers),
                Map.class
        );

        Map<String, Object> respBody = response.getBody();
        if (respBody == null || !respBody.containsKey("data")) {
            log.warn("Embedding API 返回异常: {}", respBody);
            return List.of();
        }

        List<Map<String, Object>> dataList = (List<Map<String, Object>>) respBody.get("data");
        dataList.sort((a, b) -> {
            int idxA = ((Number) a.get("index")).intValue();
            int idxB = ((Number) b.get("index")).intValue();
            return Integer.compare(idxA, idxB);
        });

        List<float[]> embeddings = new ArrayList<>();
        for (Map<String, Object> item : dataList) {
            List<Double> embList = (List<Double>) item.get("embedding");
            float[] vec = new float[embList.size()];
            for (int i = 0; i < embList.size(); i++) {
                vec[i] = embList.get(i).floatValue();
            }
            embeddings.add(vec);
        }
        return embeddings;
    }
}
