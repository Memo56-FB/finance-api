package dev.memofb.finance.categories;

import jakarta.validation.constraints.*;

record CategoryRequest(@NotBlank @Size(max = 100) String name) {
    CategoryRequest { name = name == null ? null : name.strip(); }
}
