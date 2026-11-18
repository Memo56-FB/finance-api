package dev.memofb.finance.categories;

import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import dev.memofb.finance.shared.FinancialException;

@Repository
public class CategoryRepository {
    private static final RowMapper<CategoryResponse> ROW = (r, i) -> new CategoryResponse(r.getLong("id"), r.getString("name"));
    private final JdbcTemplate jdbc;

    public CategoryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    List<CategoryResponse> list(String owner) {
        return jdbc.query("SELECT id,name FROM categories WHERE username=? AND deleted_at IS NULL ORDER BY id", ROW, owner);
    }

    CategoryResponse get(String owner, long id) {
        return jdbc.query("SELECT id,name FROM categories WHERE username=? AND id=? AND deleted_at IS NULL", ROW, owner, id)
                .stream().findFirst().orElseThrow(FinancialException::notFound);
    }

    CategoryResponse create(String owner, String name) {
        return jdbc.queryForObject("INSERT INTO categories(username,name) VALUES (?,?) RETURNING id,name", ROW, owner, name);
    }

    CategoryResponse update(String owner, long id, String name) {
        return jdbc.queryForObject("UPDATE categories SET name=? WHERE username=? AND id=? RETURNING id,name", ROW, name, owner, id);
    }

    void delete(String owner, long id) {
        boolean used = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM budgets WHERE username=? AND category_id=? AND deleted_at IS NULL)
                    OR EXISTS(SELECT 1 FROM recurring_payments WHERE username=? AND category_id=? AND deleted_at IS NULL)
                """, Boolean.class, owner, id, owner, id);
        if (used) throw FinancialException.conflict("Remove or reassign the active budget or recurring payment before deleting this category");
        jdbc.update("UPDATE categories SET deleted_at=CURRENT_TIMESTAMP WHERE username=? AND id=?", owner, id);
    }

    public void requireAssignment(String owner, Long id, Long previous) {
        if (id == null) throw FinancialException.badRequest("categoryId is required");
        var rows = jdbc.queryForList("SELECT deleted_at IS NULL AS active FROM categories WHERE username=? AND id=?", owner, id);
        if (rows.isEmpty()) throw FinancialException.notFound();
        if (!Boolean.TRUE.equals(rows.getFirst().get("active")) && !Objects.equals(id, previous)) {
            throw FinancialException.badRequest("The selected category is inactive");
        }
    }
}
