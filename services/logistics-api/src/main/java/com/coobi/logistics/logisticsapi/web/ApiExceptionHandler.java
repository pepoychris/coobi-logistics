package com.coobi.logistics.logisticsapi.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The error contract of the API: every failure is answered with an RFC 9457 problem
 * detail, never with a stack trace or with the default body of the framework.
 *
 * <p>Two kinds of failure are worth naming:
 *
 * <ul>
 *   <li>a request the client can fix - an unknown filter value, a negative page, a size
 *       above the documented maximum - is answered with {@code 400} and a message that
 *       states the accepted values, so the caller does not have to read this service to
 *       correct the request;
 *   <li>a path that names no resource is answered with {@code 404}.
 * </ul>
 *
 * <p>Anything else is answered with {@code 500} and a generic message: the cause is logged
 * with the request path, and the client is not told about the internals of the service.
 *
 * <p>The advice is ordered first so that these bodies - not the ones the framework would
 * produce - are the error contract clients see.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiNotFoundException.class)
    ProblemDetail handleNotFound(ApiNotFoundException notFound, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Not Found", notFound.getMessage(), request);
    }

    /**
     * Constraint violated on a controller parameter, raised when the controller is
     * validated through its proxy.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handleConstraintViolation(ConstraintViolationException violation, HttpServletRequest request) {
        String detail = violation.getConstraintViolations().stream()
                .map(violationOfField -> violationOfField.getPropertyPath() + " " + violationOfField.getMessage())
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, "Bad Request", detail, request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception unexpected, HttpServletRequest request) {
        log.error("unhandled failure while serving path={}", request.getRequestURI(), unexpected);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal Server Error",
                "the request could not be served",
                request);
    }

    /**
     * A value that does not fit the type it is bound to - the usual case being a filter value
     * outside its enumeration. The message names the parameter and lists what it accepts.
     *
     * <p>Any other type mismatch keeps the answer of the framework, so the contract of this
     * advice only replaces the replies a client can act on.
     */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException mismatch,
            HttpHeaders headers,
            HttpStatusCode status,
            org.springframework.web.context.request.WebRequest request) {
        if (!(mismatch instanceof MethodArgumentTypeMismatchException parameterMismatch)) {
            return super.handleTypeMismatch(mismatch, headers, status, request);
        }
        String detail = "the value '" + parameterMismatch.getValue() + "' is not valid for '"
                + parameterMismatch.getName() + "'" + acceptedValues(parameterMismatch);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Bad Request");
        problem.setInstance(URI.create(request.getDescription(false).replace("uri=", "")));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    /**
     * A body that fails validation; no endpoint of this API takes a body yet, so this
     * handler exists only to keep the error contract complete.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException invalid,
            HttpHeaders headers,
            HttpStatusCode status,
            org.springframework.web.context.request.WebRequest request) {
        String detail = invalid.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Bad Request");
        problem.setInstance(URI.create(request.getDescription(false).replace("uri=", "")));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    /**
     * A parameter that fails its own constraints; the built-in method validation of the
     * framework reports those failures as this exception.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException invalid,
            HttpHeaders headers,
            HttpStatusCode status,
            org.springframework.web.context.request.WebRequest request) {
        String detail = invalid.getAllErrors().stream()
                .map(error -> Objects.toString(error.getDefaultMessage(), "invalid value"))
                .collect(Collectors.joining("; "));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Bad Request");
        problem.setInstance(URI.create(request.getDescription(false).replace("uri=", "")));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    private static String acceptedValues(MethodArgumentTypeMismatchException mismatch) {
        Class<?> requiredType = mismatch.getRequiredType();
        if (requiredType == null || !requiredType.isEnum()) {
            return "";
        }
        String values = Arrays.stream(requiredType.getEnumConstants())
                .map(Object::toString)
                .collect(Collectors.joining(", "));
        return "; accepted values are " + values;
    }

    private static ProblemDetail problem(
            HttpStatus status,
            String title,
            String detail,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }
}
