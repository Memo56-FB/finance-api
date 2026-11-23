package dev.memofb.finance.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice(assignableTypes = AuthController.class)
class RegistrationExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(status, "Revisa los campos del registro");
        var errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(
                        error.getField().equals("passwordWithinByteLimit") ? "password" : error.getField(),
                        error.getDefaultMessage()))
                .toList();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(status, "El cuerpo debe contener un objeto JSON de registro válido");
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    @ExceptionHandler(RegistrationService.UsernameAlreadyRegisteredException.class)
    ProblemDetail usernameAlreadyRegistered() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "El nombre de usuario ya está registrado");
    }

    record FieldError(String field, String message) {
    }
}
