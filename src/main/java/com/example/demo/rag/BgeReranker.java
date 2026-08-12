package com.example.demo.rag;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
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
 * BGE Reranker —— 调用硅基流动 Cross-Encoder API 做精细重排序。
 *
 * API: POST https://api.siliconflow.cn/v1/rerank
 * 模型: BAAI/bge-reranker-v2-m3（免费）
 *
 * Cross-Encoder 原理：
 *   把 query 和每个 doc 拼成 [CLS] query [SEP] doc [SEP] 送入 Transformer，
 *   query 的每个 token 都能通过 Self-Attention 注意到 doc 的所有 token（反之亦然），
 *   输出一个精确的相关性分数。
 *
 * 面试要点：
 *   "Bi-Encoder 分开编码 query 和 doc → 快但粗；
 *    Cross-Encoder 联合编码 → 慢但准。
 *    所以全量用 Bi-Encoder 粗排，Top-20 用 Cross-Encoder 精排。"
 */
@Component
public class BgeReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(BgeReranker.class);

    private final RestTemplate restTemplate;
    private final String apiKey;
    private final String apiUrl;
    private final String model;

    public BgeReranker(
            @Value("${app.reranker.api-key}") String apiKey,
            @Value("${app.reranker.url}") String apiUrl,
            @Value("${app.reranker.model}") String model) {
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
        this.model = model;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public List<SearchResult> rerank(String query, List<SearchResult> documents, int topK) {
        if (documents.isEmpty()) {
            return List.of();
        }

        if (apiKey == null || apiKey.isBlank()) {
            log.info("Reranker API Key 未配置，使用 RRF 粗排分数");
            return documents.subList(0, Math.min(topK, documents.size()));
        }

        List<String> docTexts = documents.stream()
                .map(SearchResult::text)
                .toList();

        try {
            return callApi(query, docTexts, documents, topK);
        } catch (Exception e) {
            log.error("Reranker API 调用失败，降级为粗排结果: {}", e.getMessage());
            // 降级：保持粗排顺序，截取 topK
            return documents.subList(0, Math.min(topK, documents.size()));
        }
    }

    @SuppressWarnings("unchecked")
    private List<SearchResult> callApi(
            String query,
            List<String> docTexts,
            List<SearchResult> originals,
            int topK) {

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        Map<String, Object> body = Map.of(
                "model", model,
                "query", query,
                "documents", docTexts,
                "top_n", Math.min(topK, docTexts.size()),
                "return_documents", false
        );

        ResponseEntity<Map> response = restTemplate.postForEntity(
                apiUrl,
                new HttpEntity<>(body, headers),
                Map.class
        );

        Map<String, Object> respBody = response.getBody();
        if (respBody == null || !respBody.containsKey("results")) {
            log.warn("Reranker API 返回异常: {}", respBody);
            return originals.subList(0, Math.min(topK, originals.size()));
        }

        // 解析结果：按 relevance_score 降序排列
        List<Map<String, Object>> results = (List<Map<String, Object>>) respBody.get("results");

        List<SearchResult> reranked = new ArrayList<>();
        for (Map<String, Object> item : results) {
            int index = ((Number) item.get("index")).intValue();
            double score = ((Number) item.get("relevance_score")).doubleValue();
            SearchResult original = originals.get(index);
            reranked.add(original.withRanking(RerankResult.bge(score)));
        }

        log.debug("Reranker 精排完成：{} 条候选 → {} 条结果", docTexts.size(), reranked.size());
        return reranked;
    }
}
