package se.pbt.curiositas.web;

import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import se.pbt.curiositas.api.model.FieldErrorDto;
import se.pbt.curiositas.dataset.VersionAheadException;
import se.pbt.curiositas.person.DuplicatePersonException;
import se.pbt.curiositas.person.InvalidPersonException;
import se.pbt.curiositas.person.PersonChangedException;
import se.pbt.curiositas.person.PersonDeletedException;
import se.pbt.curiositas.person.PersonNotFoundException;
import se.pbt.curiositas.json.JsonFieldPath;
import tools.jackson.databind.DatabindException;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Turns errors into Problem Details (RFC 9457), so every error from the API has the same shape.
 * Spring's own errors are handled by the base class; this class adds the application's own errors
 * and answers 422 for every request that is readable but invalid, so clients only need to handle
 * one kind of validation error. 400 is kept for requests that cannot be read at all.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    /** Orders field errors so the response is the same every time for the same request. */
    private static final Comparator<FieldErrorDto> BY_FIELD =
            Comparator.comparing(FieldErrorDto::getField).thenComparing(FieldErrorDto::getMessage);

    /**
     * Answers 404 Not Found when a requested person does not exist.
     *
     * @param exception the error naming the missing id
     * @return the problem description
     */
    @ExceptionHandler
    ProblemDetail handlePersonNotFound(PersonNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    /**
     * Answers 410 Gone when a requested person has been deleted, so a client knows the id was
     * valid and can remove its own copy of the person.
     *
     * @param exception the error naming the deleted person and where its history is
     * @return the problem description
     */
    @ExceptionHandler
    ProblemDetail handlePersonDeleted(PersonDeletedException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.GONE, exception.getMessage());
    }

    /**
     * Answers 400 Bad Request when a client asks for changes after a version the dataset has not
     * reached, so it knows to load the snapshot again instead of waiting for changes that never come.
     *
     * @param exception the error naming both versions
     * @return the problem description
     */
    @ExceptionHandler
    ProblemDetail handleVersionAhead(VersionAheadException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    /**
     * Answers 409 Conflict when a person is already in the database, naming the existing person
     * so the client can update it instead.
     *
     * @param exception the error naming the existing person
     * @return the problem description
     */
    @ExceptionHandler
    ProblemDetail handleDuplicatePerson(DuplicatePersonException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    /**
     * Answers 409 Conflict when the database rejects a write for clashing with stored data. All
     * other rules are checked before writing, so this only happens when two clients store the
     * same Wikidata id at the same moment and both pass the duplicate check.
     *
     * @param exception the rejection reported by the database layer; not revealed to the client
     * @return the problem description
     */
    @ExceptionHandler
    ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The data conflicts with data stored at the same moment; read it again and retry");
    }

    /**
     * Answers 412 Precondition Failed when a person was changed after the client read it, so the
     * client can read again instead of overwriting the other change.
     *
     * @param exception the error naming the expected and current versions
     * @return the problem description
     */
    @ExceptionHandler
    ProblemDetail handlePersonChanged(PersonChangedException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PRECONDITION_FAILED, exception.getMessage());
    }

    /**
     * Answers 412 Precondition Failed when the database detects that another change was written
     * between reading and writing a person. This is rare, but the result must be the same as when
     * the version check before the write fails.
     *
     * @param exception the conflict reported by the database layer
     * @return the problem description
     */
    @ExceptionHandler
    ProblemDetail handleOptimisticLockingFailure(OptimisticLockingFailureException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PRECONDITION_FAILED,
                "The data was changed by someone else at the same time; read it again and retry");
    }

    /**
     * Answers 422 when a person's data is impossible, for example a date that does not exist.
     *
     * @param exception every problem found
     * @return the problem description with one field error per problem
     */
    @ExceptionHandler
    ProblemDetail handleInvalidPerson(InvalidPersonException exception) {
        return unprocessable(exception.getViolations().stream()
                .map(violation -> new FieldErrorDto(violation.field(), violation.message()))
                .toList());
    }

    /**
     * Answers 401 Unauthorized when a write is made without valid credentials. The
     * {@code WWW-Authenticate} header tells the client which authentication scheme to use.
     *
     * @param exception the failed authentication; its details are not revealed to the client
     * @return the problem description with the authentication challenge
     */
    @ExceptionHandler
    ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"Curiositas\"")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                        "Valid credentials are required to change data"));
    }

    /**
     * Answers 400 Bad Request when a query or path parameter breaks a limit in the contract, such
     * as a negative page number. The generated interfaces validate parameters this way, outside
     * Spring MVC's own validation.
     *
     * @param exception the violated limits
     * @return the problem description, naming each violated limit
     */
    @ExceptionHandler
    ProblemDetail handleConstraintViolation(ConstraintViolationException exception) {
        String detail = exception.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    /**
     * Answers 422 when a request body breaks the contract's schema, for example a missing name or
     * month 13, listing every field that failed.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldErrorDto> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldErrorDto(error.getField(), error.getDefaultMessage()))
                .toList();
        return handleExceptionInternal(exception, unprocessable(errors), headers, HttpStatus.UNPROCESSABLE_CONTENT, request);
    }

    /**
     * Answers 422 when the JSON is valid but a value has the wrong type or is not allowed, such as
     * an unknown gender, naming the field. Malformed JSON still gets 400, since nothing in it can
     * be trusted.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (exception.getCause() instanceof DatabindException invalidValue) {
            FieldErrorDto error = new FieldErrorDto(JsonFieldPath.of(invalidValue),
                    "has a value of the wrong type or outside the allowed values");
            return handleExceptionInternal(exception, unprocessable(List.of(error)), headers,
                    HttpStatus.UNPROCESSABLE_CONTENT, request);
        }
        return super.handleHttpMessageNotReadable(exception, headers, status, request);
    }

    /** Creates a 422 problem with the given field errors as an {@code errors} property. */
    private static ProblemDetail unprocessable(List<FieldErrorDto> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
                "The request contains invalid data");
        problem.setProperty("errors", errors.stream().sorted(BY_FIELD).toList());
        return problem;
    }
}
