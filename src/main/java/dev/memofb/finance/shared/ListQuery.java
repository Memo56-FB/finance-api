package dev.memofb.finance.shared;

public record ListQuery(String search, int page, int size, String sort) {
    public ListQuery {
        if (page < 0 || size < 1 || size > 100) throw FinancialException.badRequest("page must be nonnegative and size between 1 and 100");
        if (!java.util.Set.of("newest", "oldest", "name_asc", "name_desc", "amount_desc", "amount_asc").contains(sort)) {
            throw FinancialException.badRequest("Invalid sort");
        }
        search = search == null ? "" : search;
    }

    public long offset() { return (long) page * size; }
    public String pattern() { return "%" + search.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"; }
    public String orderBy(String dateColumn) {
        return switch (sort) {
            case "newest" -> dateColumn + " DESC, id DESC";
            case "oldest" -> dateColumn + " ASC, id ASC";
            case "name_asc" -> "lower(name) ASC, id ASC";
            case "name_desc" -> "lower(name) DESC, id DESC";
            case "amount_desc" -> "amount DESC, id DESC";
            case "amount_asc" -> "amount ASC, id ASC";
            default -> throw new IllegalStateException();
        };
    }
}
