# 03 · BM25 + Vector + RRF · 掌握卡（入口版）

定位：S 级；算法细节最容易被深挖，面试官会追公式与参数。
关联：02 Hybrid 编排、04 Reranker、08 Embedding 弹性。

## 1. 知识树

```
BM25 + Vector + RRF
├── BM25（词面召回）
│   ├── 倒排索引 term → (doc, tf)
│   ├── 打分：IDF × TF 饱和 × 长度归一化
│   │   └── k1=1.2（TF 饱和）、b=0.75（长度惩罚）、IDF 平滑
│   └── 中文分词：2-gram 子词（"缓存穿透" → 缓存/存穿/穿透）
├── Vector（语义召回）
│   ├── Embedding：BGE-large-zh-v1.5，1024 维
│   ├── 检索：InMemoryVectorStore O(N) cosine
│   └── 弹性：FIFO 缓存 2000 → 熔断 3 次 / 60s → TF-IDF 降级
└── RRF 融合
    ├── score = Σ 1/(k + rank)，k=60 经验值
    ├── 只依赖排名 → 跨源可比，免归一化
    └── Parent 级去重 → 候选 topK×6
```

## 2. 核心流程（骨架）

场景 A：BM25 单路

```
query 分词 → 查倒排 → 逐 doc 打分（IDF × TF 饱和 × 长度归一）→ topN
  └─ 无命中词 → 空结果（交给 RRF / 向量路补）
```

场景 B：向量单路

```
query → embedding（缓存命中？→ 直接取；未命中 → API；熔断 OPEN → TF-IDF 降级）
      → cosine 相似度 → topN
```

场景 C：RRF 融合

```
两路排名列表 → 同一 doc 累加 1/(k+rank) → 排序 → Parent 去重 → topK×6 候选
```

## 3. 两分钟口述（骨架，第 3 步再展开）

- 结论：BM25 管精确词面、向量管语义、RRF 用排名融合消除量纲差异。
- 原理：BM25 三要素；向量 cosine；RRF 排名融合。
- 关键细节：k1/b 含义、k=60 经验值、2-gram、缓存 / 熔断 / 降级。
- 代价：O(N) 向量检索、BM25 重建无锁、2-gram 分词噪声。
- 钩子：为什么不直接用加权和？→ 量纲不可比，RRF 只看排名。

## 4. 三个为什么（入口问题）

1. 为什么 BM25 不用简单词频？ → 方向：TF 饱和、长度归一化、IDF 稀有词加权。
2. 为什么 RRF 不用加权和？ → 方向：分数量纲不同、需逐查询归一化、rank 天然可比。
3. 为什么自实现 BM25 不用 ES？ → 方向：学习价值 vs 工程；<1000 docs 场景够用。

判定：能复述 BM25 公式并解释每个参数的作用 → 掌握。

## 5. 项目应用（证据映射）

| 场景 | 方案 | 收益 | 代价 / 坑 |
|---|---|---|---|
| 关键词精确召回 | Bm25Index（K1=1.2 / B=0.75 + 倒排） | 毫秒级词面召回 | 2-gram 产生切词噪声 |
| 语义召回 | InMemoryVectorStore cosine | 同义改写可召回 | O(N)，<10K 向量够用 |
| Embedding 弹性 | ResilientEmbeddingService 缓存+熔断+TF-IDF | 主 API 挂了链路不断 | 降级语义弱、rebuild 竞态 |
| 融合 | RrfFusion（k=60） | 免归一化、跨源稳定 | 忽略分数幅度信息 |

## 缺口清单

- [ ] 能手写 / 复述 BM25 公式（含 IDF 平滑）
- [ ] 解释 k1 饱和与 b 长度惩罚的直觉
- [ ] 说清 RRF 为什么不看分数只看排名
- [ ] 说出 2-gram 分词的至少两个缺点
