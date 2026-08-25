package com.example.demo.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Strict renderer: unknown, missing and unresolved variables are rejected. */
@Component
public class SkillPromptRenderer {
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9_.-]*)}}" );
    private final int maxPromptChars;

    public SkillPromptRenderer(
            @Value("${app.skill.max-prompt-chars}") int maxPromptChars) {
        this.maxPromptChars = Math.max(256, maxPromptChars);
    }

    public String render(
            String template,
            Map<String, Object> variables,
            Map<String, Object> parameterSchema) {
        Map<String, Object> values = variables == null ? Map.of() : Map.copyOf(variables);
        validateRequired(values, parameterSchema);
        Matcher matcher = VARIABLE.matcher(template);
        StringBuffer rendered = new StringBuffer();
        List<String> missing = new ArrayList<>();
        while (matcher.find()) {
            Object value = values.get(matcher.group(1));
            if (value == null) {
                missing.add(matcher.group(1));
                continue;
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(String.valueOf(value)));
        }
        matcher.appendTail(rendered);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing Skill variables: " + missing);
        }
        if (rendered.length() > maxPromptChars) {
            throw new IllegalArgumentException("Rendered Skill prompt is too large");
        }
        return rendered.toString();
    }

    private void validateRequired(
            Map<String, Object> variables, Map<String, Object> schema) {
        Object required = schema == null ? null : schema.get("required");
        if (required instanceof List<?> names) {
            List<String> missing = names.stream()
                    .map(String::valueOf)
                    .filter(name -> !variables.containsKey(name) || variables.get(name) == null)
                    .toList();
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException("Missing required Skill variables: " + missing);
            }
        }
    }
}
