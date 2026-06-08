package com.example.demo.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * TF-IDF 向量化 embedding —— 零外部依赖，本地降级实现。
 *
 * 原理：
 * 1. 从所有 chunk 构建词表（每个 term = 向量一个维度）
 * 2. TF  = 词在文本中出现次数
 * 3. IDF = log(总chunk数 / 包含该词的chunk数)
 * 4. 向量 = TF * IDF，L2 归一化后做余弦相似度检索
 *
 * 面试直接说：
 *   "生产环境把这个实现换成 BGE / text2vec-large-chinese，
 *    或者调 OpenAI text-embedding-3-small API，其余代码不变。"
 */
@Component("simpleEmbedding")
public class SimpleEmbeddingService implements EmbeddingService {

    private List<String> vocabulary = List.of();
    private Map<String, Integer> termIndex = Map.of();
    private Map<String, Double> idf = Map.of();

    @Override
    public int dimension() {
        return vocabulary.size();
    }

    /**
     * 基于所有 chunk 构建词表 + IDF。必须在 embed() 之前调用一次。
     */
    public void buildVocabulary(List<String> chunks) {
        // 1. 收集所有 term
        Map<String, Integer> df = new HashMap<>(); // document frequency
        for (String chunk : chunks) {
            for (String term : uniqueTerms(chunk)) {
                df.merge(term, 1, Integer::sum);
            }
        }

        // 2. 构建词表（按 term 排序保证稳定性）
        vocabulary = new ArrayList<>(df.keySet());
        vocabulary.sort(String::compareTo);

        // 3. term → index 映射
        termIndex = new HashMap<>();
        for (int i = 0; i < vocabulary.size(); i++) {
            termIndex.put(vocabulary.get(i), i);
        }

        // 4. 计算 IDF
        int N = chunks.size();
        idf = new HashMap<>();
        for (String term : vocabulary) {
            idf.put(term, Math.log((double) N / (df.get(term) + 1)) + 1);
        }
    }

    @Override
    public float[] embed(String text) {
        if (vocabulary.isEmpty()) return new float[0];

        float[] vec = new float[vocabulary.size()];
        List<String> tokens = tokenize(text);

        // 统计 TF
        Map<String, Integer> tf = new HashMap<>();
        for (String t : tokens) {
            tf.merge(t, 1, Integer::sum);
        }

        // TF * IDF
        for (Map.Entry<String, Integer> e : tf.entrySet()) {
            Integer idx = termIndex.get(e.getKey());
            if (idx != null) {
                double weight = e.getValue() * idf.getOrDefault(e.getKey(), 1.0);
                vec[idx] = (float) weight;
            }
        }

        // L2 归一化
        double norm = 0;
        for (float v : vec) norm += v * v;
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vec.length; i++) vec[i] = (float) (vec[i] / norm);
        }

        return vec;
    }

    /** 分词：按分隔符拆成词 + 中文 2-gram 子词（兼顾短查询） */
    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        for (String word : text.toLowerCase()
                .split("[\\s，。；：、！？\\n,.;:!?()（）\\[\\]【】\"'\"\\-/]+")) {
            if (word.isEmpty()) continue;
            tokens.add(word);
            // 中文词追加 2-gram 子词（提高短查询时的命中率）
            if (word.length() >= 2) {
                for (int i = 0; i <= word.length() - 2; i++) {
                    tokens.add(word.substring(i, i + 2));
                }
            }
        }
        return tokens;
    }

    /** 去重后的 term 集合（用于 DF 统计） */
    private List<String> uniqueTerms(String text) {
        return new ArrayList<>(new java.util.LinkedHashSet<>(tokenize(text)));
    }
}
