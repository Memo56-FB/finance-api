package dev.memofb.finance.auth;

import java.security.Principal;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
class AuthController {

    private final RegistrationService registration;

    AuthController(RegistrationService registration) {
        this.registration = registration;
    }

    @PostMapping(value = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    RegistrationService.RegisteredUser register(@Valid @RequestBody RegistrationRequest request) {
        return registration.register(request);
    }

    @GetMapping("/csrf")
    CsrfToken csrf(CsrfToken token) {
        return token;
    }

    @GetMapping("/me")
    CurrentUser me(Principal principal) {
        return new CurrentUser(principal.getName());
    }

    record CurrentUser(String username) {
    }
}
