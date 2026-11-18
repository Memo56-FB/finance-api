package dev.memofb.finance.transactions;

import java.net.URI;
import java.security.Principal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import dev.memofb.finance.shared.*;

@RestController
@RequestMapping("/api/transactions")
class TransactionController {
    private final TransactionService service;
    TransactionController(TransactionService service) { this.service=service; }
    @GetMapping PageResponse<TransactionResponse> list(Principal user,@RequestParam(required=false) String search,
            @RequestParam(required=false) @Positive Long categoryId,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="newest") String sort) {
        return service.list(user.getName(),new ListQuery(search,page,size,sort),categoryId);
    }
    @GetMapping("/{id}") TransactionResponse get(Principal user,@PathVariable @Positive long id) { return service.get(user.getName(),id); }
    @PostMapping ResponseEntity<TransactionResponse> create(Principal user,@Valid @RequestBody TransactionRequest request) {
        var result=service.create(user.getName(),request); return ResponseEntity.created(URI.create("/api/transactions/"+result.id())).body(result);
    }
    @PutMapping("/{id}") TransactionResponse update(Principal user,@PathVariable @Positive long id,@Valid @RequestBody TransactionRequest request) {
        return service.update(user.getName(),id,request);
    }
    @DeleteMapping("/{id}") ResponseEntity<Void> delete(Principal user,@PathVariable @Positive long id) {
        service.delete(user.getName(),id); return ResponseEntity.noContent().build();
    }
}
