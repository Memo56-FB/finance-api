package dev.memofb.finance.budgets;

import java.net.URI;
import java.security.Principal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import dev.memofb.finance.shared.*;

@RestController
@RequestMapping("/api/budgets")
class BudgetController {
    private final BudgetService service;
    private final BusinessCalendar calendar;
    BudgetController(BudgetService service,BusinessCalendar calendar) { this.service=service; this.calendar=calendar; }
    @GetMapping ItemsResponse<BudgetResponse> list(Principal user,@RequestParam(required=false) String month) {
        return new ItemsResponse<>(service.list(user.getName(),calendar.month(month)));
    }
    @GetMapping("/{id}") BudgetResponse get(Principal user,@PathVariable @Positive long id,@RequestParam(required=false) String month) {
        return service.get(user.getName(),id,calendar.month(month));
    }
    @PostMapping ResponseEntity<BudgetResponse> create(Principal user,@Valid @RequestBody BudgetRequest request) {
        var result=service.create(user.getName(),request); return ResponseEntity.created(URI.create("/api/budgets/"+result.id())).body(result);
    }
    @PutMapping("/{id}") BudgetResponse update(Principal user,@PathVariable @Positive long id,@Valid @RequestBody BudgetRequest request) {
        return service.update(user.getName(),id,request);
    }
    @DeleteMapping("/{id}") ResponseEntity<Void> delete(Principal user,@PathVariable @Positive long id) {
        service.delete(user.getName(),id); return ResponseEntity.noContent().build();
    }
}
