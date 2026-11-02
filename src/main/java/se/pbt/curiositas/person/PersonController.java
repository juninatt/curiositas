package se.pbt.curiositas.person;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import se.pbt.curiositas.api.PersonsApi;
import se.pbt.curiositas.api.model.PersonDto;
import se.pbt.curiositas.api.model.PersonInputDto;
import se.pbt.curiositas.api.model.PersonPageDto;

import java.net.URI;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serves the person endpoints described in the OpenAPI contract. Paths, parameters and models
 * come from the generated {@link PersonsApi}, so this class cannot drift from the contract.
 *
 * <p>Every successful response is sent as {@code application/json}, even to a client that only
 * accepts {@code application/problem+json}, so a result is never labelled as an error.
 */
@RestController
public class PersonController implements PersonsApi {

    /**
     * The only accepted form of {@code If-Match}: a strong ETag holding a version, such as "3".
     * Weak ETags and "*" are rejected, since they cannot prove which version the client read.
     */
    private static final Pattern VERSION_ETAG = Pattern.compile("^\"(\\d{1,18})\"$");

    private final PersonService service;

    /**
     * Creates the controller.
     *
     * @param service reads and changes persons
     */
    public PersonController(PersonService service) {
        this.service = service;
    }

    /** Returns one page of persons, ordered by name. */
    @Override
    public ResponseEntity<PersonPageDto> listPersons(Integer page, Integer size) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(PersonMapper.toDto(service.list(page, size)));
    }

    /** Returns one person, with its version as ETag so a later replacement can detect conflicts. */
    @Override
    public ResponseEntity<PersonDto> getPerson(UUID id) {
        Person person = service.get(id);
        return ResponseEntity.ok()
                .eTag(eTag(person))
                .contentType(MediaType.APPLICATION_JSON)
                .body(PersonMapper.toDto(person));
    }

    /**
     * Creates a person and answers 201 with its URL in {@code Location} and its version as ETag,
     * so the client can read or replace it without searching for it.
     */
    @Override
    public ResponseEntity<PersonDto> createPerson(PersonInputDto personInputDto) {
        Person person = service.create(PersonMapper.toNewPerson(personInputDto));
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{id}")
                .buildAndExpand(person.getId())
                .toUri();
        return ResponseEntity.created(location)
                .eTag(eTag(person))
                .contentType(MediaType.APPLICATION_JSON)
                .body(PersonMapper.toDto(person));
    }

    /**
     * Replaces a person if {@code If-Match} names its current version, and answers with the new
     * version as ETag. This keeps two clients from overwriting each other's changes.
     */
    @Override
    public ResponseEntity<PersonDto> replacePerson(UUID id, PersonInputDto personInputDto, String ifMatch) {
        long expectedVersion = versionFrom(ifMatch);
        Person person = service.replace(id, expectedVersion, stored -> PersonMapper.copyInput(personInputDto, stored));
        return ResponseEntity.ok()
                .eTag(eTag(person))
                .contentType(MediaType.APPLICATION_JSON)
                .body(PersonMapper.toDto(person));
    }

    /** Deletes a person and answers 204, since there is nothing left to return. */
    @Override
    public ResponseEntity<Void> deletePerson(UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Returns the person's version as ETag value; Spring adds the surrounding quotes. */
    private static String eTag(Person person) {
        return String.valueOf(person.getVersion());
    }

    /**
     * Reads the version from {@code If-Match}. A missing header is answered with 428, since the
     * client must prove which version it read; an unusable one with 412, since it cannot match.
     */
    private static long versionFrom(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "If-Match is required; send the ETag from the latest read of the person");
        }
        Matcher matcher = VERSION_ETAG.matcher(ifMatch.trim());
        if (!matcher.matches()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "If-Match must be the ETag from a read of the person, such as \"3\"");
        }
        return Long.parseLong(matcher.group(1));
    }
}
