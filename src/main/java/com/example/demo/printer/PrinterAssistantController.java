package com.example.demo.printer;

import com.example.demo.security.CurrentUserProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/printer-assistant")
public class PrinterAssistantController {

    private final PrinterCatalogService catalog;
    private final PrinterQaService qa;
    private final PrinterApplicationService applications;
    private final CurrentUserProvider currentUser;

    public PrinterAssistantController(PrinterCatalogService catalog, PrinterQaService qa,
                                      PrinterApplicationService applications, CurrentUserProvider currentUser) {
        this.catalog = catalog;
        this.qa = qa;
        this.applications = applications;
        this.currentUser = currentUser;
    }

    @GetMapping("/products")
    public List<PrinterCatalogService.ProductView> products() { return catalog.listProducts(); }

    @PostMapping("/qa")
    public PrinterQaService.QaResponse ask(@Valid @RequestBody QaBody body) {
        return qa.ask(new PrinterQaService.QaRequest(body.productId(), body.question()));
    }

    @PostMapping("/applications")
    public PrinterApplicationService.ApplicationView create(
            @Valid @RequestBody ApplicationBody body,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return applications.create(currentUser.requireCurrentUser(),
                new PrinterApplicationService.CreateApplication(body.productId(), body.question(),
                        body.troubleshootingSteps(), body.additionalNote()), idempotencyKey);
    }

    @GetMapping("/applications")
    public List<PrinterApplicationService.ApplicationView> mine() {
        return applications.listMine(currentUser.requireCurrentUser());
    }

    @GetMapping("/applications/{id}")
    public PrinterApplicationService.ApplicationView detail(@PathVariable Long id) {
        return applications.requireOwned(id, currentUser.requireCurrentUser());
    }

    @PostMapping("/admin/initialize")
    @PreAuthorize("hasRole('ADMIN')")
    public PrinterCatalogService.InitializationResult initialize() { return catalog.initializeDemoData(); }

    @GetMapping("/admin/applications")
    @PreAuthorize("hasRole('ADMIN')")
    public List<PrinterApplicationService.ApplicationView> all() { return applications.listAll(); }

    @PutMapping("/admin/applications/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public PrinterApplicationService.ApplicationView status(@PathVariable Long id, @Valid @RequestBody StatusBody body) {
        return applications.updateStatus(id, new PrinterApplicationService.UpdateStatus(body.status(), body.processingNote()));
    }

    public record QaBody(Long productId, @NotBlank @Size(max = 2000) String question) {}
    public record ApplicationBody(Long productId, @NotBlank @Size(max = 2000) String question,
                                  @NotBlank @Size(max = 8000) String troubleshootingSteps,
                                  @Size(max = 2000) String additionalNote) {}
    public record StatusBody(@NotBlank String status, @Size(max = 2000) String processingNote) {}
}
