package dev.memofb.finance.auth;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record RegistrationRequest(
        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 100, message = "El nombre debe tener como máximo 100 caracteres") String name,
        @NotBlank(message = "El nombre de usuario es obligatorio")
        @Pattern(regexp = USERNAME_PATTERN, message = "Usa letras, números, puntos, guiones o guiones bajos")
        @Size(max = 254, message = "El nombre de usuario debe tener como máximo 254 caracteres") String username,
        @NotBlank(message = "La contraseña es obligatoria")
        @Size(min = 8, message = "La contraseña debe tener al menos 8 caracteres") String password) {

    static final String USERNAME_PATTERN = "[a-z0-9._-]+";

    RegistrationRequest {
        name = name == null ? null : name.strip();
        username = normalizeUsername(username);
    }

    static String normalizeUsername(String username) {
        return username == null ? null : username.strip().toLowerCase(Locale.ROOT);
    }

    @JsonIgnore
    @AssertTrue(message = "La contraseña debe ocupar como máximo 72 bytes UTF-8")
    public boolean isPasswordWithinByteLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    @Override
    public String toString() {
        return "RegistrationRequest[redacted]";
    }
}
