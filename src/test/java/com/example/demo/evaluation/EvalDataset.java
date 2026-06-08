package com.example.demo.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * RAG 评测集 —— 一次标注的查询-文档-答案三元组。
 *
 * 从 JSON 文件加载，格式见 src/test/resources/eval-dataset.json。
 *
 * 构建原则：
 * 1. 覆盖多种查询类型（精确查找、概念理解、多文档综合、指代消解）
 * 2. 每条标注 groundTruthDocPrefixes（相关文档的文件名前缀）
 * 3. 可选 referenceAnswer（参考答案，用于 RAGAS 评分）
 */
public class EvalDataset {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String DEFAULT_PATH = "eval-dataset.json";

    /**
     * 单条评测查询。
     *
     * @param groundTruthDocPrefixes 相关文档的文件名前缀列表（如 ["java.txt", "spring.txt"]），
     *                                用于和检索到的 chunk ID 做前缀匹配
     */
    public record EvalQuery(
            String id,
            String query,
            String type,
            List<String> groundTruthDocPrefixes,
            String referenceAnswer
    ) {}

    /** 单条查询的评测结果 */
    public record EvalResult(
            String evalId,
            Map<String, Double> metrics,
            long durationMs
    ) {}

    /** 从 classpath 加载评测集 */
    public static List<EvalQuery> loadFromFile(String path) {
        try (InputStream is = EvalDataset.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException("评测集文件不存在: " + path);
            }
            return mapper.readValue(is, new TypeReference<>() {});
        } catch (Exception e) {
            throw new RuntimeException("加载评测集失败: " + path, e);
        }
    }

    /** 从默认路径加载 */
    public static List<EvalQuery> loadDefault() {
        return loadFromFile(DEFAULT_PATH);
    }

    /** 判断检索到的 chunk ID 是否命中 ground truth（通过文件名前缀匹配） */
    public static boolean matchesGroundTruth(String retrievedId, String groundTruthPrefix) {
        if (retrievedId == null) return false;
        return retrievedId.startsWith(groundTruthPrefix);
    }

    /** 判断检索结果列表中是否命中了某个 ground truth prefix */
    public static boolean anyMatch(List<String> retrievedIds, String groundTruthPrefix) {
        return retrievedIds.stream().anyMatch(id -> matchesGroundTruth(id, groundTruthPrefix));
    }
}
