package com.example.demo.rag;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.example.demo.service.LlmClient;

/**
 * LLM + 规则混合 Query Rewrite 实现。
 *
 * 策略：先尝试 LLM 智能改写，LLM 返回无效结果时降级为规则改写。
 *
 * 规则改写覆盖最常见的两类场景：
 * 1. 指代消解："它"/"这个"/"那个" → 从历史中提取的实体
 * 2. 短查询补齐：长度 < 4 字的 query → 拼入历史上下文
 *
 * 面试要点：
 * "为了不增加不必要的延迟，先做按需判断——单轮清晰查询直接跳过 Rewrite。
 *  多轮场景先走规则（零延迟），规则覆盖不了再走 LLM。"
 */
@Component
public class LlmQueryRewriter implements QueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(LlmQueryRewriter.class);

    /** 需要消解的代词 */
    private static final Set<String> PRONOUNS = Set.of("它", "他", "她", "这个", "那个", "这", "那", "其");

    /** 用于提取历史中实体的问句后缀 */
    private static final Pattern QUESTION_SUFFIX = Pattern.compile(
            "(是什么|怎么|如何|什么意思|啥|什么|有哪些|介绍一下|说一下|讲讲|是什么东西|是啥).*$");

    /** LLM 返回结果中表示「无效」的标记（mock LLM 的特征） */
    private static final Set<String> INVALID_MARKERS = Set.of(
            "【模拟 LLM 回答】", "【本地降级回复】", "【系统提示】");

    private final LlmClient llmClient;
    private final String llmModel;

    public LlmQueryRewriter(
            LlmClient llmClient,
            @Value("${app.llm.model}") String llmModel) {
        this.llmClient = llmClient;
        this.llmModel = llmModel;
    }

    @Override
    public String rewrite(String rawQuery, String history) {
        // 单轮 + 无代词 → 不需要改写
        if (history == null || history.isBlank()) {
            if (!containsPronoun(rawQuery)) {
                log.debug("Query Rewrite 跳过：单轮清晰查询");
                return rawQuery;
            }
            // 单轮但有代词 → 无法消解，原样返回
            log.debug("Query Rewrite 跳过：单轮含代词但无历史可参考");
            return rawQuery;
        }

        // 多轮 + 无代词 + query 足够长 → 可能不需要改写
        if (!containsPronoun(rawQuery) && rawQuery.length() > 4) {
            log.debug("Query Rewrite 跳过：多轮但 query 已完整");
            return rawQuery;
        }

        // === 需要改写：先走 LLM，失败降级为规则 ===

        // 1. 尝试 LLM Rewrite
        String llmResult = tryLlmRewrite(rawQuery, history);
        if (llmResult != null) {
            log.debug("Query Rewrite (LLM): \"{}\" → \"{}\"", rawQuery, llmResult);
            return llmResult;
        }

        // 2. LLM 失败，降级为规则 Rewrite
        String ruleResult = ruleRewrite(rawQuery, history);
        if (!ruleResult.equals(rawQuery)) {
            log.debug("Query Rewrite (规则): \"{}\" → \"{}\"", rawQuery, ruleResult);
        }
        return ruleResult;
    }

    // ==================== LLM Rewrite ====================

    private String tryLlmRewrite(String rawQuery, String history) {
        try {
            String prompt = buildRewritePrompt(rawQuery, history);
            String response = llmClient.callLlm(prompt, llmModel);

            // 检查是否 mock LLM 返回的无效结果
            for (String marker : INVALID_MARKERS) {
                if (response.contains(marker)) {
                    log.debug("LLM Rewrite 返回无效结果（检测到 mock 标记），降级为规则");
                    return null;
                }
            }

            // 清理：去掉可能的解释性前缀/后缀，只保留改写后的 query
            String cleaned = cleanLlmOutput(response);
            if (cleaned.isBlank() || cleaned.length() > rawQuery.length() * 3) {
                // 改写结果异常（太短或太长）
                log.debug("LLM Rewrite 结果异常（长度={}），降级为规则", cleaned.length());
                return null;
            }

            return cleaned;
        } catch (Exception e) {
            log.warn("LLM Rewrite 调用异常: {}", e.getMessage());
            return null;
        }
    }

    private String buildRewritePrompt(String rawQuery, String history) {
        return """
                你是一个查询改写助手。根据对话历史，将用户的模糊问题改写为清晰、具体的检索查询。

                要求：
                - 消解指代：将"它""这个""那个"等代词替换为具体实体
                - 补充上下文：结合历史对话补充省略的信息
                - 去除口语化表达
                - 直接输出改写后的查询，不要任何解释

                对话历史：
                %s

                用户当前问题：%s

                改写后的查询：""".formatted(history, rawQuery);
    }

    /**
     * 清理 LLM 输出：去掉常见的多余前缀、引号、换行等。
     */
    private String cleanLlmOutput(String response) {
        String cleaned = response.trim();
        // 去掉常见的包裹字符
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        if (cleaned.startsWith("「") && cleaned.endsWith("」")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        // 去掉可能的"改写后："等前缀
        cleaned = cleaned.replaceFirst("^(改写后的查询[：:]\\s*|改写[：:]\\s*)", "");
        // 只取第一行（避免 LLM 在改写后继续解释）
        int newlineIdx = cleaned.indexOf('\n');
        if (newlineIdx > 0) {
            cleaned = cleaned.substring(0, newlineIdx).trim();
        }
        return cleaned;
    }

    // ==================== 规则 Rewrite ====================

    /**
     * 基于规则的查询重写，覆盖最常见的指代消解和短查询补齐场景。
     */
    private String ruleRewrite(String rawQuery, String history) {
        String lastUserMsg = extractLastUserMessage(history);
        if (lastUserMsg.isEmpty()) {
            return rawQuery;
        }

        String rewritten = rawQuery;

        // 场景一：指代消解 —— 替换代词为上一轮实体
        if (containsPronoun(rewritten)) {
            String entity = extractMainEntity(lastUserMsg);
            if (!entity.isEmpty()) {
                for (String pronoun : PRONOUNS) {
                    rewritten = rewritten.replace(pronoun, entity);
                }
            }
        }

        // 场景二：短查询补齐 —— 在 query 前拼接历史上下文
        if (rewritten.length() <= 4 && !lastUserMsg.isEmpty()) {
            String entity = extractMainEntity(lastUserMsg);
            if (!entity.isEmpty() && !rewritten.contains(entity)) {
                rewritten = entity + " " + rewritten;
            }
        }

        return rewritten;
    }

    // ==================== 工具方法 ====================

    private boolean containsPronoun(String query) {
        for (String pronoun : PRONOUNS) {
            if (query.contains(pronoun)) return true;
        }
        return false;
    }

    /**
     * 从历史文本中提取最后一条用户消息。
     * 历史格式："用户: xxx\n助手: xxx\n用户: yyy\n助手: yyy"
     */
    private String extractLastUserMessage(String history) {
        if (history == null || history.isBlank()) return "";
        String[] lines = history.split("\\n");
        // 倒序查找最后一个 "用户:"
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].strip();
            if (line.startsWith("用户:") || line.startsWith("用户：")) {
                return line.replaceFirst("^用户[：:]\\s*", "").strip();
            }
        }
        return "";
    }

    /**
     * 从用户消息中提取核心实体（去掉问句后缀）。
     * "Redis缓存穿透是什么" → "Redis缓存穿透"
     * "怎么解决缓存雪崩" → "缓存雪崩"
     */
    private String extractMainEntity(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return "";

        // 去掉问句后缀：是什么 / 怎么 / 如何 / 啥...
        Matcher m = QUESTION_SUFFIX.matcher(userMessage);
        String entity = m.replaceFirst("").strip();

        // 去掉常见前缀疑问词："怎么解决" → "解决" 不够好，保留完整信息
        entity = entity.replaceFirst("^(怎么|如何|怎样|怎么解决)", "").strip();

        return entity;
    }
}
