package com.example.demo.agent.tool;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class CalculatorTool implements ToolDefinition {

    @Override
    public String name() {
        return "calculator";
    }

    @Override
    public String description() {
        return "Execute math expression. Use for numeric calculation, supports +-*/ and parentheses.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "expression", Map.of("type", "string", "description", "Math expression like '(3+5)*2'")
                ),
                "required", List.of("expression")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        long start = System.currentTimeMillis();
        String expression = (String) params.get("expression");
        if (expression == null || expression.isBlank()) {
            return ToolResult.fail(name(), "expression is required", System.currentTimeMillis() - start);
        }

        if (!expression.matches("[0-9+\\-*/().%\\s]+")) {
            return ToolResult.fail(name(), "Expression contains disallowed characters",
                    System.currentTimeMillis() - start);
        }

        try {
            double result = evaluateSimple(expression);
            return ToolResult.ok(name(), expression + " = " + result, System.currentTimeMillis() - start);
        } catch (Exception e) {
            return ToolResult.fail(name(), "Calculation failed: " + e.getMessage(),
                    System.currentTimeMillis() - start);
        }
    }

    @Override
    public Set<String> requiredPermissions() {
        return Set.of("CALCULATOR");
    }

    /**
     * Simple expression evaluator using recursive descent.
     * Supports: + - * / ( ) and numbers.
     */
    private double evaluateSimple(String expr) {
        return new Parser(expr.replaceAll("\\s+", "")).parseExpression();
    }

    private static class Parser {
        private final String input;
        private int pos;

        Parser(String input) {
            this.input = input;
            this.pos = 0;
        }

        double parseExpression() {
            double left = parseTerm();
            while (pos < input.length()) {
                char op = input.charAt(pos);
                if (op == '+') { pos++; left += parseTerm(); }
                else if (op == '-') { pos++; left -= parseTerm(); }
                else break;
            }
            return left;
        }

        double parseTerm() {
            double left = parseFactor();
            while (pos < input.length()) {
                char op = input.charAt(pos);
                if (op == '*') { pos++; left *= parseFactor(); }
                else if (op == '/') { pos++; left /= parseFactor(); }
                else if (op == '%') { pos++; left %= parseFactor(); }
                else break;
            }
            return left;
        }

        double parseFactor() {
            if (pos >= input.length()) throw new IllegalArgumentException("Unexpected end");
            char c = input.charAt(pos);
            if (c == '(') {
                pos++;
                double value = parseExpression();
                if (pos >= input.length() || input.charAt(pos) != ')') {
                    throw new IllegalArgumentException("Missing closing parenthesis");
                }
                pos++;
                return value;
            }
            if (c == '-') {
                pos++;
                return -parseFactor();
            }
            return parseNumber();
        }

        double parseNumber() {
            int start = pos;
            while (pos < input.length() &&
                    (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos) throw new IllegalArgumentException("Expected number at position " + pos);
            return Double.parseDouble(input.substring(start, pos));
        }
    }

}
