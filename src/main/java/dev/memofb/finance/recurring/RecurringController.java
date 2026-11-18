package dev.memofb.finance.recurring;

import java.net.URI;
import java.security.Principal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import dev.memofb.finance.shared.*;

@RestController
@RequestMapping("/api/recurring-payments")
class RecurringController {
    private final RecurringService service;
    private final BusinessCalendar calendar;
    RecurringController(RecurringService service,BusinessCalendar calendar) { this.service=service;this.calendar=calendar; }
    @GetMapping PageResponse<RecurringPaymentResponse> list(Principal user,@RequestParam(required=false) String month,
            @RequestParam(required=false) String search,@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="newest") String sort) {
        return service.list(user.getName(),calendar.month(month),new ListQuery(search,page,size,sort));
    }
    @GetMapping("/summary") PaymentSummaryResponse summary(Principal user,@RequestParam(required=false) String month) {
        return service.summary(user.getName(),calendar.month(month));
    }
    @GetMapping("/{id}") RecurringPaymentResponse get(Principal user,@PathVariable @Positive long id,@RequestParam(required=false) String month) {
        return service.get(user.getName(),id,calendar.month(month));
    }
    @PostMapping ResponseEntity<RecurringPaymentResponse> create(Principal user,@Valid @RequestBody RecurringPaymentRequest request) {
        var result=service.create(user.getName(),request);return ResponseEntity.created(URI.create("/api/recurring-payments/"+result.id())).body(result);
    }
    @PutMapping("/{id}") RecurringPaymentResponse update(Principal user,@PathVariable @Positive long id,@Valid @RequestBody RecurringPaymentRequest request) {
        return service.update(user.getName(),id,request);
    }
    @DeleteMapping("/{id}") ResponseEntity<Void> delete(Principal user,@PathVariable @Positive long id) {
        service.delete(user.getName(),id);return ResponseEntity.noContent().build();
    }
    @PutMapping("/{id}/payments/{month}") PaymentRecordedResponse pay(Principal user,@PathVariable @Positive long id,@PathVariable String month) {
        return service.pay(user.getName(),id,calendar.month(month));
    }
}
