package se.pbt.curiositas.person;

import org.springframework.data.domain.Page;
import se.pbt.curiositas.api.model.AgeRangeDto;
import se.pbt.curiositas.api.model.AlternativeNameDto;
import se.pbt.curiositas.api.model.ChangeSourceDto;
import se.pbt.curiositas.api.model.ChangeTypeDto;
import se.pbt.curiositas.api.model.CountryDto;
import se.pbt.curiositas.api.model.GenderDto;
import se.pbt.curiositas.api.model.HistoricalDateDto;
import se.pbt.curiositas.api.model.HistoricalDateInputDto;
import se.pbt.curiositas.api.model.NameTypeDto;
import se.pbt.curiositas.api.model.PersonDto;
import se.pbt.curiositas.api.model.PersonInputDto;
import se.pbt.curiositas.api.model.PersonPageDto;
import se.pbt.curiositas.api.model.PersonRevisionDto;
import se.pbt.curiositas.api.model.PersonRevisionListDto;
import se.pbt.curiositas.date.AgeRange;
import se.pbt.curiositas.date.HistoricalDate;
import se.pbt.curiositas.person.InvalidPersonException.Violation;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Converts between persons and the API's models. Incoming data is checked against the rules that
 * the contract's schema cannot express; outgoing data gets its derived values, such as display
 * texts, age at death and country names, which are never stored.
 */
public final class PersonMapper {

    /** The ISO 3166-1 alpha-2 codes of current countries. */
    private static final Set<String> COUNTRY_CODES = Set.of(Locale.getISOCountries());

    /** Not instantiable; all methods are static. */
    private PersonMapper() {
    }

    /**
     * Creates a new person from a request.
     *
     * @param input the person as sent by the client, already checked against the schema
     * @return the new, not yet saved person
     * @throws InvalidPersonException if the data is impossible, listing every problem found
     */
    public static Person toNewPerson(PersonInputDto input) {
        Person person = new Person(input.getName(), Gender.valueOf(input.getGender().name()));
        copyInput(input, person);
        return person;
    }

    /**
     * Copies all fields of a request onto a person. Nothing is changed unless all data is valid,
     * so a rejected request never leaves a half-updated person behind.
     *
     * @param input  the person as sent by the client, already checked against the schema
     * @param person the person to update
     * @throws InvalidPersonException if the data is impossible, listing every problem found
     */
    public static void copyInput(PersonInputDto input, Person person) {
        List<Violation> violations = new ArrayList<>();
        HistoricalDate birth = toDate("birth", input.getBirth(), violations);
        HistoricalDate death = toDate("death", input.getDeath(), violations);
        HistoricalDate floruit = toDate("floruit", input.getFloruit(), violations);
        if (birth != null && death != null) {
            try {
                AgeRange.between(birth, death);
            } catch (IllegalArgumentException impossibleLife) {
                violations.add(new Violation("death", impossibleLife.getMessage()));
            }
        }
        String birthCountry = input.getBirthCountry();
        if (birthCountry != null && !COUNTRY_CODES.contains(birthCountry)) {
            violations.add(new Violation("birthCountry",
                    "must be the ISO 3166-1 alpha-2 code of a current country, but was " + birthCountry));
        }
        if (!violations.isEmpty()) {
            throw new InvalidPersonException(violations);
        }

        person.setName(input.getName());
        person.setGender(Gender.valueOf(input.getGender().name()));
        person.setWikidataId(input.getWikidataId());
        List<AlternativeNameDto> alsoKnownAs = input.getAlsoKnownAs() != null ? input.getAlsoKnownAs() : List.of();
        person.setAlsoKnownAs(alsoKnownAs.stream()
                .map(name -> new AlternativeName(name.getName(), NameType.valueOf(name.getType().name())))
                .toList());
        person.setBirth(birth);
        person.setDeath(death);
        person.setFloruit(floruit);
        person.setBirthPlace(input.getBirthPlace());
        person.setDeathPlace(input.getDeathPlace());
        person.setBirthCountry(birthCountry);
    }

    /**
     * Converts a stored person back to the request format, which is also the format of the data
     * files. Empty lists and an uncertainty of 0 are left out, since they are what the request
     * format assumes when nothing is given, and the files stay shorter without them.
     *
     * @param person the stored person
     * @return the person as a client would send it
     */
    public static PersonInputDto toInput(Person person) {
        List<AlternativeNameDto> alsoKnownAs = person.getAlsoKnownAs().stream()
                .map(PersonMapper::toDto)
                .toList();
        return new PersonInputDto(person.getName(), GenderDto.valueOf(person.getGender().name()))
                .wikidataId(person.getWikidataId())
                .alsoKnownAs(alsoKnownAs.isEmpty() ? null : alsoKnownAs)
                .birth(toInput(person.getBirth()))
                .death(toInput(person.getDeath()))
                .floruit(toInput(person.getFloruit()))
                .birthPlace(person.getBirthPlace())
                .deathPlace(person.getDeathPlace())
                .birthCountry(person.getBirthCountry());
    }

    /** Converts a date to the request format, or returns {@code null} if the date is unknown. */
    private static HistoricalDateInputDto toInput(HistoricalDate date) {
        if (date == null) {
            return null;
        }
        return new HistoricalDateInputDto(date.year())
                .month(date.month())
                .day(date.day())
                .uncertaintyYears(date.uncertaintyYears() == 0 ? null : date.uncertaintyYears());
    }

    /**
     * Creates a date from a request, or records why it is impossible. Returns {@code null} both
     * for an unknown date and for an invalid one; in the latter case a violation is added.
     */
    private static HistoricalDate toDate(String field, HistoricalDateInputDto input, List<Violation> violations) {
        if (input == null) {
            return null;
        }
        int uncertaintyYears = input.getUncertaintyYears() != null ? input.getUncertaintyYears() : 0;
        try {
            return new HistoricalDate(input.getYear(), input.getMonth(), input.getDay(), uncertaintyYears);
        } catch (IllegalArgumentException impossibleDate) {
            violations.add(new Violation(field, impossibleDate.getMessage()));
            return null;
        }
    }

    /**
     * Converts a person, including all derived values.
     *
     * @param person the stored person
     * @return the person as returned by the API
     */
    public static PersonDto toDto(Person person) {
        return new PersonDto(
                person.getId(),
                person.getName(),
                person.getAlsoKnownAs().stream().map(PersonMapper::toDto).toList(),
                GenderDto.valueOf(person.getGender().name()),
                toOffsetDateTime(person.getCreatedAt()),
                toOffsetDateTime(person.getUpdatedAt()))
                .wikidataId(person.getWikidataId())
                .birth(toDto(person.getBirth()))
                .death(toDto(person.getDeath()))
                .floruit(toDto(person.getFloruit()))
                .ageAtDeath(ageAtDeath(person))
                .birthPlace(person.getBirthPlace())
                .deathPlace(person.getDeathPlace())
                .birthCountry(toCountry(person.getBirthCountry()));
    }

    /**
     * Converts one page of persons, keeping the paging information.
     *
     * @param page the stored persons on one page
     * @return the page as returned by the API
     */
    static PersonPageDto toDto(Page<Person> page) {
        return new PersonPageDto(
                page.getContent().stream().map(PersonMapper::toDto).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    /**
     * Converts the history of a person, reading each snapshot back into the form clients send.
     *
     * @param personId  the id of the person
     * @param revisions every revision of the person, oldest first
     * @return the history as returned by the API
     */
    static PersonRevisionListDto toDto(UUID personId, List<PersonRevision> revisions) {
        return new PersonRevisionListDto(personId, revisions.stream()
                .map(revision -> new PersonRevisionDto(
                        revision.getSequenceNumber(),
                        revision.getRevision(),
                        ChangeTypeDto.valueOf(revision.getChangeType().name()),
                        ChangeSourceDto.valueOf(revision.getSource().name()),
                        toOffsetDateTime(revision.getChangedAt()),
                        PersonSnapshots.read(revision.getSnapshot())))
                .toList());
    }

    /** Converts an alternative name. */
    private static AlternativeNameDto toDto(AlternativeName name) {
        return new AlternativeNameDto(name.name(), NameTypeDto.valueOf(name.type().name()));
    }

    /** Converts a date and adds its display text, or returns {@code null} if the date is unknown. */
    private static HistoricalDateDto toDto(HistoricalDate date) {
        if (date == null) {
            return null;
        }
        return new HistoricalDateDto(date.year(), date.uncertaintyYears(), date.displayText())
                .month(date.month())
                .day(date.day());
    }

    /** Calculates the age at death, or returns {@code null} unless both birth and death are known. */
    private static AgeRangeDto ageAtDeath(Person person) {
        if (person.getBirth() == null || person.getDeath() == null) {
            return null;
        }
        AgeRange age = AgeRange.between(person.getBirth(), person.getDeath());
        return new AgeRangeDto(age.min(), age.max());
    }

    /** Adds the English country name to an ISO code, or returns {@code null} if the country is unknown. */
    private static CountryDto toCountry(String code) {
        if (code == null) {
            return null;
        }
        return new CountryDto(code, Locale.of("", code).getDisplayCountry(Locale.ENGLISH));
    }

    /** Converts a point in time to the API's format, always in UTC. */
    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
