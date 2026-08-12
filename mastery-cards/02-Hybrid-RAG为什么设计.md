# 02 · Hybrid RAG 为什么设计 · 掌握卡（入口版）

定位：S 级核心亮点；高频追问「为什么两路 / 为什么并行 / 为什么不加权」。
关联：03 BM25+Vector+RRF、04 Reranker、05 评测。

## 1. 知识树

```
Hybrid RAG
├── 单路检索的盲区
│   ├── 纯 BM25：词面匹配，同义改写漏召回
│   └── 纯向量：语义近但专有名词 / 编号易漂移
├── 两阶段设计
│   ├── 阶段一 粗排：BM25 + Vector 并行 → RRF → 候选 topK×6
│   ├── 阶段二 精排：Cross-Encoder Reranker → topK
│   └── 门控：RelevanceGate
├── 并行实现
│   ├── CompletableFuture + ragRetrievalExecutor
│   ├── 延迟 = max(两路) 而非 sum
│   └── 异常兜底：并行失败 → BM25 单路
└── 关联
    ├── 03 RRF k=60、Parent 去重
    ├── 05 评测对比 Recall@3 0.96 → 0.99
    └── RagObservability 记录 RETRIEVAL / RERANK 阶段耗时
```

## 2. 核心流程（骨架）

场景 A：混合检索一次调用

```
query → 双路并行（BM25 / Embedding+cosine）
  ├─ 两路成功 → RRF 融合 → Parent 去重 → topK×6 候选
  ├─ 并行异常 → 降级 BM25-only（vector 空）
  └─ 候选 → Reranker 精排
      ├─ 成功 → topK → RelevanceGate
      └─ Reranker 失败 → 直接用粗排结果（不阻塞）
```

场景 B：延迟构成

```
BM25(ms 级) ∥ Embedding(API, 百 ms~s) → RRF(ms) → Reranker(API, 最贵) → 返回
```

## 3. 两分钟口述（骨架，第 3 步再展开）

- 结论：两路互补召回 + 粗排精排两阶段控成本。
- 原理：BM25 管词面精确，向量管语义；RRF 用排名融合。
- 关键细节：topK×6=18 候选给精排留余量；并行让延迟=max；Reranker 失败降级。
- 数据：Recall@3 0.96→0.99，MRR 0.91→0.95，代价 12ms→14s。
- 钩子：为什么不直接全量 Cross-Encoder？→ 成本 / 精度平衡。

## 4. 三个为什么（入口问题）

1. 为什么需要双路？ → 方向：词面盲区 vs 语义盲区互补。
2. 为什么两阶段而不是一路？ → 方向：全量精排太慢太贵，粗排先缩候选。
3. 为什么并行？ → 方向：两路独立、延迟 max、线程池隔离、异常可兜底。

判定：能各举一个 BM25 和向量的失败例子 → 掌握。

## 5. 项目应用（证据映射）

| 场景 | 方案 | 收益 | 代价 |
|---|---|---|---|
| 召回互补 | HybridRetriever 双路 | Recall@3 0.99 | Reranker 外部 API 拉高延迟 |
| 延迟控制 | CompletableFuture + 独立 executor | max 非 sum | 线程池资源占用 |
| 降级 | 并行异常→BM25-only；Reranker 失败→粗排 | 链路不中断 | 召回 / 排序质量临时下降 |

## 缺口清单

- [ ] 举具体例子说明 BM25、向量各自的失败场景
- [ ] 解释 CANDIDATE_MULTIPLIER=6 为什么够
- [ ] 说清观测到了哪些阶段耗时（RagObservability / RagStage）
