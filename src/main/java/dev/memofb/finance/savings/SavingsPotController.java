package dev.memofb.finance.savings;

import java.net.URI;
import java.security.Principal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/savings-pots")
class SavingsPotController {

    private final SavingsPotService pots;

    SavingsPotController(SavingsPotService pots) {
        this.pots = pots;
    }

    @GetMapping
    SavingsPotListResponse list(Principal principal, @RequestParam(required = false) String month) {
        return pots.list(principal.getName(), month);
    }

    @GetMapping("/{id}")
    SavingsPotResponse get(Principal principal, @PathVariable @Positive long id) {
        return pots.get(principal.getName(), id);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SavingsPotResponse> create(Principal principal, @Valid @RequestBody SavingsPotRequest request) {
        var created = pots.create(principal.getName(), request);
        return ResponseEntity.created(URI.create("/api/savings-pots/" + created.id())).body(created);
    }

    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    SavingsPotResponse update(Principal principal, @PathVariable @Positive long id,
            @Valid @RequestBody SavingsPotRequest request) {
        return pots.update(principal.getName(), id, request);
    }

    @PostMapping(value = "/{id}/deposits", consumes = MediaType.APPLICATION_JSON_VALUE)
    SavingsPotResponse deposit(Principal principal, @PathVariable @Positive long id,
            @Valid @RequestBody MoneyAmountRequest request) {
        return pots.deposit(principal.getName(), id, request.amount());
    }

    @PostMapping(value = "/{id}/withdrawals", consumes = MediaType.APPLICATION_JSON_VALUE)
    SavingsPotResponse withdraw(Principal principal, @PathVariable @Positive long id,
            @Valid @RequestBody MoneyAmountRequest request) {
        return pots.withdraw(principal.getName(), id, request.amount());
    }

    @DeleteMapping("/{id}")
    SavingsPotDeletionResponse delete(Principal principal, @PathVariable @Positive long id) {
        return pots.delete(principal.getName(), id);
    }

    @PostMapping("/{id}/restore")
    SavingsPotResponse restore(Principal principal, @PathVariable @Positive long id) {
        return pots.restore(principal.getName(), id);
    }
}
