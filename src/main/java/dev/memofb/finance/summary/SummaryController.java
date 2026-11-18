package dev.memofb.finance.summary;

import java.security.Principal;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/financial-summary")
class SummaryController {
    private final SummaryService service;
    SummaryController(SummaryService service) { this.service=service; }
    @GetMapping FinancialSummaryResponse summary(Principal user) { return service.summary(user.getName()); }
    @GetMapping("/settings") SummarySettings settings(Principal user) { return service.settings(user.getName()); }
    @PutMapping("/settings") SummarySettings save(Principal user, @Valid @RequestBody SummarySettings request) {
        return service.save(user.getName(),request);
    }
}
