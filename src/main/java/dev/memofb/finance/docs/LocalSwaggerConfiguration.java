package dev.memofb.finance.docs;

import java.util.List;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.core.providers.ObjectMapperProvider;
import org.springdoc.webmvc.ui.SwaggerIndexPageTransformer;
import org.springdoc.webmvc.ui.SwaggerIndexTransformer;
import org.springdoc.webmvc.ui.SwaggerWelcomeCommon;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED_VALUE;

@Configuration(proxyBeanMethods = false)
@Profile("local")
class LocalSwaggerConfiguration {

    @Bean
    @Order(0)
    SecurityFilterChain swaggerSecurityFilterChain(HttpSecurity http) throws Exception {
        return http.securityMatcher("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**", "/v3/api-docs.yaml")
                .authorizeHttpRequests(authorize -> authorize.requestMatchers(HttpMethod.GET, "/**").permitAll()
                        .anyRequest().denyAll())
                .build();
    }

    @Bean
    OpenAPI localOpenApi() {
        // Login and logout are Spring Security filters, so controller scanning cannot describe their contract.
        var login = new Operation().operationId("login").tags(List.of("auth-controller"))
                .summary("Log in with email and password")
                .requestBody(new RequestBody().required(true).content(new Content().addMediaType(
                        APPLICATION_FORM_URLENCODED_VALUE, new MediaType().schema(new ObjectSchema()
                                .addProperty("email", new StringSchema().format("email"))
                                .addProperty("password", new StringSchema().format("password").writeOnly(true))
                                .required(List.of("email", "password"))))))
                .responses(new ApiResponses().addApiResponse("204", new ApiResponse().description("Authenticated"))
                        .addApiResponse("401", new ApiResponse().description("Invalid credentials"))
                        .addApiResponse("403", new ApiResponse().description("Invalid CSRF token")));
        var logout = new Operation().operationId("logout").tags(List.of("auth-controller"))
                .summary("Log out of the current session")
                .responses(new ApiResponses().addApiResponse("204", new ApiResponse().description("Logged out"))
                        .addApiResponse("403", new ApiResponse().description("Invalid CSRF token")));
        return new OpenAPI().info(new Info().title("financeApi").version("0.0.1-SNAPSHOT")
                        .description("Log in with POST /api/auth/login before testing private endpoints. "
                                + "The browser keeps the session cookie and Swagger automatically obtains fresh CSRF tokens."))
                .path("/api/auth/login", new PathItem().post(login))
                .path("/api/auth/logout", new PathItem().post(logout));
    }

    @Bean
    SwaggerIndexTransformer swaggerIndexTransformer(SwaggerUiConfigProperties config, SwaggerUiOAuthProperties oauth,
            SwaggerWelcomeCommon welcome, ObjectMapperProvider mapper) {
        return new SwaggerIndexPageTransformer(config, oauth, welcome, mapper) {
            @Override
            protected String addCSRF(String initializer) {
                return initializer.replace("presets: [", """
                        requestInterceptor: async (request) => {
                            request.credentials = "same-origin";
                            const url = new URL(request.url, window.location.href);
                            const method = (request.method || "GET").toUpperCase();
                            if (url.origin === window.location.origin && !["GET", "HEAD", "OPTIONS"].includes(method)) {
                                const response = await fetch(new URL("../api/auth/csrf", window.location.href), {
                                    credentials: "same-origin", cache: "no-store"
                                });
                                if (!response.ok) throw new Error("Unable to obtain a CSRF token");
                                const csrf = await response.json();
                                request.headers[csrf.headerName] = csrf.token;
                            }
                            return request;
                        },
                        presets: [""");
            }
        };
    }
}
