package dev.memofb.finance.categories;

import java.net.URI;
import java.security.Principal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import dev.memofb.finance.shared.ItemsResponse;

@RestController
@RequestMapping("/api/categories")
class CategoryController {
    private final CategoryService service;
    CategoryController(CategoryService service) { this.service = service; }

    @GetMapping ItemsResponse<CategoryResponse> list(Principal user) { return new ItemsResponse<>(service.list(user.getName())); }
    @GetMapping("/{id}") CategoryResponse get(Principal user, @PathVariable @Positive long id) { return service.get(user.getName(),id); }
    @PostMapping ResponseEntity<CategoryResponse> create(Principal user, @Valid @RequestBody CategoryRequest request) {
        var result=service.create(user.getName(),request);
        return ResponseEntity.created(URI.create("/api/categories/"+result.id())).body(result);
    }
    @PutMapping("/{id}") CategoryResponse update(Principal user, @PathVariable @Positive long id, @Valid @RequestBody CategoryRequest request) {
        return service.update(user.getName(),id,request);
    }
    @DeleteMapping("/{id}") ResponseEntity<Void> delete(Principal user, @PathVariable @Positive long id) {
        service.delete(user.getName(),id);
        return ResponseEntity.noContent().build();
    }
}
