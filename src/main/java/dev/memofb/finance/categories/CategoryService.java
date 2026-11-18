package dev.memofb.finance.categories;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import dev.memofb.finance.shared.FinancialLock;

@Service
class CategoryService {
    private final CategoryRepository categories;
    private final FinancialLock lock;

    CategoryService(CategoryRepository categories, FinancialLock lock) { this.categories = categories; this.lock = lock; }

    public List<CategoryResponse> list(String owner) { return categories.list(owner); }
    public CategoryResponse get(String owner, long id) { return categories.get(owner, id); }

    @Transactional
    public CategoryResponse create(String owner, CategoryRequest request) {
        lock.acquire(owner);
        return categories.create(owner, request.name());
    }

    @Transactional
    public CategoryResponse update(String owner, long id, CategoryRequest request) {
        lock.acquire(owner);
        categories.get(owner,id);
        return categories.update(owner,id,request.name());
    }

    @Transactional
    public void delete(String owner, long id) {
        lock.acquire(owner);
        categories.get(owner,id);
        categories.delete(owner,id);
    }
}
