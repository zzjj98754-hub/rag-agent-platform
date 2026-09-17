package com.example.demo.evaluation;

import com.example.demo.rag.Bm25Index;
import com.example.demo.rag.HybridRetriever;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Built-in 30-question smoke evaluation; replace the expected prefixes with domain labels for real corpora. */
@Service
public class RetrievalEvaluationService {
    private record Case(String query, String expectedPrefix) { }
    private static final List<Case> QUERIES = List.of(
            new Case("面向对象有哪三大特性", "java.txt"),new Case("Redis 是什么", "redis.txt"),new Case("Spring IoC 是什么意思", "spring.txt"),new Case("Redis 支持哪些数据结构", "redis.txt"),new Case("Spring AOP 是怎么实现的", "spring.txt"),
            new Case("多态是什么", "java.txt"),new Case("缓存穿透怎么解决", "redis.txt"),new Case("Java 和 Spring 有什么关系", "java.txt"),new Case("继承和封装的区别", "java.txt"),new Case("Spring 事务的传播行为有哪些", "spring-ioc-aop.txt"),
            new Case("它的淘汰策略有哪些", "redis-in-depth.txt"),new Case("依赖注入有哪些方式", "spring.txt"),new Case("Redis 持久化机制 RDB 和 AOF 的区别", "redis-in-depth.txt"),new Case("Java 集合框架中 HashMap 的实现原理", "java-collections.txt"),new Case("Spring Boot 自动配置是怎么工作的", "spring-boot-autoconfig.txt"),
            new Case("Redis 和传统数据库有什么区别", "redis.txt"),new Case("接口和抽象类有什么区别", "java.txt"),new Case("Spring Bean 的生命周期有哪些阶段", "spring-ioc-aop.txt"),new Case("面向对象和面向过程有什么区别", "java.txt"),new Case("缓存雪崩和缓存穿透有什么区别", "redis.txt"),
            new Case("ConcurrentHashMap 在 JDK7 和 JDK8 中有什么不同", "java-collections.txt"),new Case("synchronized 的锁升级机制是怎样的", "java-concurrency.txt"),new Case("JVM 的垃圾回收算法有哪些", "java-jvm.txt"),new Case("RAG 检索增强生成的基本原理是什么", "rag-principles.txt"),new Case("BM25 和向量检索有什么区别", "vector-search.txt"),
            new Case("MySQL InnoDB 的 B+Tree 索引为什么用 B+树不用哈希表", "mysql-index-transaction.txt"),new Case("设计模式中策略模式和模板方法模式有什么区别", "design-patterns.txt"),new Case("HTTPS 的 TLS 握手过程是怎样的", "http-https.txt"),new Case("Spring MVC 的 DispatcherServlet 请求处理流程", "spring-mvc-rest.txt"),new Case("CompletableFuture 和 Future 的区别以及线程池的核心参数", "java-concurrency.txt"));
    private final HybridRetriever hybrid; private final Bm25Index bm25;
    public RetrievalEvaluationService(HybridRetriever hybrid, Bm25Index bm25) { this.hybrid=hybrid; this.bm25=bm25; }
    public Map<String,Object> run() {
        return Map.of("datasetSize", QUERIES.size(), "topK", 3,
                "bm25", score(q -> bm25.search(q,3).stream().map(Bm25Index.ScoredDoc::id).toList()),
                "hybridRrfBge", score(q -> hybrid.retrieve(q,3).stream().map(it -> it.id()).toList()));
    }
    private Map<String,Object> score(java.util.function.Function<String,List<String>> retrieve) {
        double recall=0,mrr=0,ndcg=0;
        for (Case q:QUERIES) { List<String> ids=retrieve.apply(q.query()); int rank=-1; for(int i=0;i<ids.size();i++) if(ids.get(i).startsWith(q.expectedPrefix())) {rank=i;break;}
            if(rank>=0) { recall++; mrr+=1d/(rank+1); ndcg+=1d/(Math.log(rank+2)/Math.log(2)); } }
        int n=QUERIES.size(); Map<String,Object> result=new LinkedHashMap<>();
        result.put("recallAt3", recall/n); result.put("mrrAt3",mrr/n); result.put("ndcgAt3",ndcg/n);
        result.put("note","每条数据标注了期望文档文件名前缀；未导入对应演示语料时分数为 0 是正常结果。"); return result;
    }
}
