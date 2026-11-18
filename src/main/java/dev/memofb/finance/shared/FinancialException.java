package dev.memofb.finance.shared;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class FinancialException extends ErrorResponseException {
    private FinancialException(HttpStatus status, String detail) {
        super(status, ProblemDetail.forStatusAndDetail(status, detail), null);
    }

    public static FinancialException notFound() { return new FinancialException(HttpStatus.NOT_FOUND, "Resource not found"); }
    public static FinancialException conflict(String detail) { return new FinancialException(HttpStatus.CONFLICT, detail); }
    public static FinancialException badRequest(String detail) { return new FinancialException(HttpStatus.BAD_REQUEST, detail); }
}
