package com.example.demo.controller;

import com.example.demo.evaluation.RetrievalEvaluationService;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequestMapping("/admin/evaluations")
public class EvaluationController {
    private final RetrievalEvaluationService evaluation;
    public EvaluationController(RetrievalEvaluationService evaluation) { this.evaluation=evaluation; }
    @PostMapping("/retrieval") public Map<String,Object> retrieval() { return evaluation.run(); }
}
