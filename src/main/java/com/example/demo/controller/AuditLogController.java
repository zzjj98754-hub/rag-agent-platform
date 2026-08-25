package com.example.demo.controller;

import com.example.demo.governance.AuditLogEntity;
import com.example.demo.governance.AuditLogService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/audit-logs")
public class AuditLogController {
    private final AuditLogService audit;
    public AuditLogController(AuditLogService audit) { this.audit = audit; }
    @GetMapping
    public List<AuditLogEntity> recent(
            @RequestParam(defaultValue = "100") int limit) {
        return audit.recent(limit);
    }
}
