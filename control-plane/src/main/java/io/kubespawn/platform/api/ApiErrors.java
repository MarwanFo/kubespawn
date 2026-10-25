package io.kubespawn.platform.api;

import io.kubespawn.platform.lifecycle.LifecycleException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(LifecycleException.class)
    ProblemDetail lifecycle(LifecycleException error, HttpServletRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(error.status(), error.getMessage());
        problem.setTitle(error.code());
        problem.setProperty("requestId", request.getAttribute("requestId"));
        return problem;
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
        HandlerMethodValidationException.class, MethodArgumentTypeMismatchException.class,
        HttpMessageNotReadableException.class, MissingRequestHeaderException.class})
    ProblemDetail invalid(Exception error, HttpServletRequest request) {
        String detail = "Check the request fields, required headers, and value formats.";
        if (error instanceof MethodArgumentNotValidException invalid) {
            detail = invalid.getBindingResult().getFieldErrors().stream()
                .map(field -> field.getField() + ": " + field.getDefaultMessage())
                .distinct().reduce((first, second) -> first + "; " + second).orElse(detail);
        }
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("INVALID_REQUEST");
        problem.setProperty("requestId", request.getAttribute("requestId"));
        return problem;
    }
}
