package com.example.demo.config;

import com.example.demo.printer.PrinterCatalogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 开发演示启动时重建虚构打印机资料索引；数据库写入与文档索引均保持幂等。 */
@Component
@ConditionalOnProperty(prefix = "app.printer-demo", name = "auto-initialize", havingValue = "true")
public class PrinterDemoDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PrinterDemoDataInitializer.class);
    private final PrinterCatalogService catalog;

    public PrinterDemoDataInitializer(PrinterCatalogService catalog) { this.catalog = catalog; }

    @Override
    public void run(ApplicationArguments args) {
        PrinterCatalogService.InitializationResult result = catalog.initializeDemoData();
        log.info("打印机演示资料初始化完成 | products={} documents={}", result.products(), result.documents());
    }
}
