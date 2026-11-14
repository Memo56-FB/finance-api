package dev.memofb.finance.savings;

import java.util.List;

import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice(assignableTypes = SavingsPotController.class)
class SavingsPotExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(status, "Check the savings pot fields");
        problem.setProperty("errors", exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), error.getDefaultMessage())).toList());
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(status, "The body must be a valid JSON object");
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(exception, invalidId(status), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(status, "Check the request fields");
        problem.setProperty("errors", exception.getParameterValidationResults().stream().flatMap(result -> {
            if (result instanceof ParameterErrors fields) {
                return fields.getFieldErrors().stream().map(error -> new FieldError(error.getField(), error.getDefaultMessage()));
            }
            return result.getResolvableErrors().stream().map(error ->
                    new FieldError(result.getMethodParameter().getParameterName(), error.getDefaultMessage()));
        }).toList());
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    private ProblemDetail invalidId(HttpStatusCode status) {
        var problem = ProblemDetail.forStatusAndDetail(status, "The savings pot id must be a positive integer");
        problem.setProperty("errors", List.of(new FieldError("id", "must be a positive integer")));
        return problem;
    }

    @ExceptionHandler(SavingsPotService.NotFoundException.class)
    ProblemDetail notFound() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Savings pot not found");
    }

    @ExceptionHandler(SavingsPotService.ConflictException.class)
    ProblemDetail conflict(SavingsPotService.ConflictException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    record FieldError(String field, String message) {
    }
}
