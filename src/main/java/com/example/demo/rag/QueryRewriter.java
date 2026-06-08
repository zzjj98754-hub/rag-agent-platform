package com.example.demo.rag;

/**
 * 查询重写接口 —— 在检索之前，把用户的自然语言 query 改写为更适合检索的形式。
 *
 * 解决的问题：
 * - 多轮对话指代消解（"它怎么解决" → "Redis 缓存穿透怎么解决"）
 * - 口语化精炼（"那个内存淘汰的东西" → "Redis 内存淘汰策略"）
 * - 上下文补充（短 query 拼接历史信息）
 *
 * 实现策略：
 * - LLM Rewrite：调用 LLM 做智能改写（效果最好，有延迟）
 * - 规则 Rewrite：正则替换代词 + 关键词提取（零延迟，覆盖 80% 场景）
 * - 混合策略：先规则快速判断，规则覆盖不了再走 LLM
 */
public interface QueryRewriter {

    /**
     * 重写用户查询。
     *
     * @param rawQuery  用户原始输入（可能含代词、口语化表达）
     * @param history   最近 N 轮对话历史，格式："用户: xxx\n助手: xxx"（可为空）
     * @return 重写后的查询语句
     */
    String rewrite(String rawQuery, String history);
}
