package com.example.demo.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 引用校验器 —— 检测 LLM 回复中的引用编号是否合法。
 *
 * 两类检测：
 * - 越界引用：LLM 引用了不存在的编号（如只给了 3 个 Chunk 却写了 [5]）
 * - 无引用：LLM 完全没有引用任何来源（可能是忘了，也可能是没有用上文档）
 *
 * 面试要点：
 * "LLM 会编造引用——这是真实工程中必须处理的问题。
 *  后处理校验是最低成本的防线：越界引用 100% 可检测。"
 */
@Component
public class CitationValidator {

    private static final Logger log = LoggerFactory.getLogger(CitationValidator.class);

    /** 匹配引用标记：[1]、[2]、[1][2][3] 等 */
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d+)]");

    /** 校验结果 */
    public record ValidationResult(
            List<Integer> validRefs,
            List<Integer> outOfRangeRefs,
            int maxRef,
            int totalCitations
    ) {
        public boolean hasFabricatedRefs() {
            return !outOfRangeRefs.isEmpty();
        }

        public boolean hasNoRefs() {
            return totalCitations == 0;
        }

        public double validRate() {
            return totalCitations > 0
                    ? (double) validRefs.size() / totalCitations
                    : 0.0;
        }
    }

    /**
     * 校验 LLM 回复中的引用。
     *
     * @param response  LLM 完整回复
     * @param maxRef    最大合法引用编号（Chunk 数量）
     */
    public ValidationResult validate(String response, int maxRef) {
        if (response == null || response.isBlank()) {
            return new ValidationResult(List.of(), List.of(), maxRef, 0);
        }

        Matcher matcher = CITATION_PATTERN.matcher(response);
        List<Integer> allRefs = new ArrayList<>();
        while (matcher.find()) {
            allRefs.add(Integer.parseInt(matcher.group(1)));
        }

        List<Integer> valid = new ArrayList<>();
        List<Integer> outOfRange = new ArrayList<>();

        for (int ref : allRefs) {
            if (ref >= 1 && ref <= maxRef) {
                if (!valid.contains(ref)) {
                    valid.add(ref);
                }
            } else {
                if (!outOfRange.contains(ref)) {
                    outOfRange.add(ref);
                }
            }
        }

        ValidationResult result = new ValidationResult(valid, outOfRange, maxRef, allRefs.size());

        if (result.hasFabricatedRefs()) {
            log.warn("检测到编造引用: {} (合法范围 1~{})",
                    result.outOfRangeRefs(), maxRef);
        }

        if (result.hasNoRefs() && maxRef > 0) {
            log.info("LLM 回复未包含任何引用标记（maxRef={}）", maxRef);
        }

        return result;
    }

    /**
     * 快速检查：是否存在编造引用。
     */
    public boolean hasFabricatedRefs(String response, int maxRef) {
        return validate(response, maxRef).hasFabricatedRefs();
    }
}
