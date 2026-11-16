package com.karimsaleh.ledger;

import com.karimsaleh.ledger.transaction.IdempotencyConflictException;
import com.karimsaleh.ledger.transaction.InsufficientFundsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ProblemDetail handleInsufficientFunds(InsufficientFundsException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(422), ex.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ProblemDetail handleIdempotencyConflict(IdempotencyConflictException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }
}
