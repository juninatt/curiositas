package se.pbt.curiositas.wikidata;

import org.springframework.stereotype.Component;
import se.pbt.curiositas.api.model.AlternativeNameDto;
import se.pbt.curiositas.api.model.GenderDto;
import se.pbt.curiositas.api.model.HistoricalDateInputDto;
import se.pbt.curiositas.api.model.NameTypeDto;
import se.pbt.curiositas.api.model.PersonInputDto;
import se.pbt.curiositas.date.HistoricalDate;
import se.pbt.curiositas.person.PersonMapper;
import se.pbt.curiositas.wikidata.WikidataStatement.Rank;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reads persons from Wikidata and turns them into the format of the data files.
 *
 * <p>Only what Wikidata states is used, and nothing is guessed: a name without a type is left out,
 * and a place a source only calls possible is written as "possibly Kinsale". The birth country is
 * the modern country of the place of birth, so it is taken from the place's countries that have no
 * end date and an ISO code; historical states such as the Roman Republic have neither.
 *
 * <p>Persons, their places and the places' countries are read in three rounds, each in as few
 * requests as Wikidata allows.
 */
@Component
public class WikidataPersons {

    static final String DATE_OF_BIRTH = "P569";
    static final String DATE_OF_DEATH = "P570";
    static final String FLORUIT = "P1317";
    static final String PLACE_OF_BIRTH = "P19";
    static final String PLACE_OF_DEATH = "P20";
    static final String SEX_OR_GENDER = "P21";
    static final String BIRTH_NAME = "P1477";
    static final String NICKNAME = "P1449";
    static final String PSEUDONYM = "P742";
    static final String COUNTRY = "P17";
    static final String ISO_COUNTRY_CODE = "P297";
    static final String END_TIME = "P582";

    /** The names Wikidata types, with the type Curiositas gives them, in the order they are written. */
    private static final List<Map.Entry<String, NameTypeDto>> NAME_TYPES = List.of(
            Map.entry(BIRTH_NAME, NameTypeDto.BIRTH_NAME),
            Map.entry(NICKNAME, NameTypeDto.NICKNAME),
            Map.entry(PSEUDONYM, NameTypeDto.PEN_NAME));

    /** Genders by Wikidata item; any other item is {@link GenderDto#OTHER}. */
    private static final Map<String, GenderDto> GENDERS = Map.of(
            "Q6581097", GenderDto.MALE,
            "Q6581072", GenderDto.FEMALE,
            "Q2449503", GenderDto.MALE,
            "Q1052281", GenderDto.FEMALE);

    /** How sure a source is of a place, by Wikidata item, as written before the place. */
    private static final Map<String, String> HEDGES = Map.of(
            "Q56644435", "probably",
            "Q18122778", "presumably",
            "Q30230067", "possibly");

    private static final Pattern COUNTRY_CODE = Pattern.compile("^[A-Z]{2}$");

    private final WikidataClient wikidata;

    /**
     * Creates the reader.
     *
     * @param wikidata reads items from Wikidata
     */
    public WikidataPersons(WikidataClient wikidata) {
        this.wikidata = wikidata;
    }

    /**
     * Reads the given persons with their places and countries.
     *
     * @param ids the persons' Wikidata ids
     * @return the persons, in the order of the ids
     * @throws WikidataException if Wikidata cannot be read, does not know an id, or a person has
     *                           no English name
     */
    public List<FetchedPerson> fetch(List<String> ids) {
        List<WikidataEntity> persons = wikidata.fetch(ids);
        Set<String> placeIds = new LinkedHashSet<>();
        for (WikidataEntity person : persons) {
            for (String property : List.of(PLACE_OF_BIRTH, PLACE_OF_DEATH)) {
                best(person.statements(property)).stream().map(WikidataStatement::value)
                        .filter(WikidataValue.Item.class::isInstance)
                        .forEach(place -> placeIds.add(((WikidataValue.Item) place).id()));
            }
        }
        Map<String, WikidataEntity> places = byId(wikidata.fetch(List.copyOf(placeIds)));
        Set<String> countryIds = new LinkedHashSet<>();
        for (WikidataEntity place : places.values()) {
            countryIds.addAll(currentCountries(place));
        }
        Map<String, WikidataEntity> countries = byId(wikidata.fetch(List.copyOf(countryIds)));
        return persons.stream().map(person -> toPerson(person, places, countries)).toList();
    }

    /**
     * Turns one person from Wikidata into the format of the data files.
     *
     * @param person    the person
     * @param places    the places the person was born and died in, by id
     * @param countries the current countries of those places, by id
     * @return the person with notes for the review
     * @throws WikidataException if the person has no English name
     */
    static FetchedPerson toPerson(WikidataEntity person, Map<String, WikidataEntity> places,
                                  Map<String, WikidataEntity> countries) {
        if (person.label() == null) {
            throw new WikidataException(person.id() + " has no English name on Wikidata");
        }
        List<String> notes = new ArrayList<>();
        WikidataStatement birthPlace = single("place of birth", person.statements(PLACE_OF_BIRTH), places, notes);
        WikidataStatement deathPlace = single("place of death", person.statements(PLACE_OF_DEATH), places, notes);
        List<AlternativeNameDto> alsoKnownAs = alsoKnownAs(person);
        PersonInputDto file = new PersonInputDto(person.label(), gender(person, notes))
                .wikidataId(person.id())
                .alsoKnownAs(alsoKnownAs.isEmpty() ? null : alsoKnownAs)
                .birth(date("birth", person.statements(DATE_OF_BIRTH), notes))
                .death(date("death", person.statements(DATE_OF_DEATH), notes))
                .floruit(date("floruit", person.statements(FLORUIT), notes))
                .birthPlace(place("place of birth", birthPlace, places, notes))
                .deathPlace(place("place of death", deathPlace, places, notes))
                .birthCountry(birthCountry(birthPlace, places, countries, notes));
        return new FetchedPerson(file, notes);
    }

    /** Reads a date, with a note when the sources disagree. */
    private static HistoricalDateInputDto date(String what, List<WikidataStatement> statements, List<String> notes) {
        WikidataDate date = WikidataDates.read(statements);
        if (date.sourcesDisagree()) {
            notes.add("Sources disagree on the " + what + " ("
                    + date.sources().stream().map(HistoricalDate::displayText).collect(Collectors.joining(", "))
                    + "), using " + date.date().displayText());
        }
        return PersonMapper.toInput(date.date());
    }

    /** Reads the names Wikidata gives a type, leaving out the person's own name and repeats. */
    private static List<AlternativeNameDto> alsoKnownAs(WikidataEntity person) {
        Set<String> seen = new LinkedHashSet<>(List.of(person.label()));
        List<AlternativeNameDto> names = new ArrayList<>();
        for (Map.Entry<String, NameTypeDto> type : NAME_TYPES) {
            for (WikidataStatement statement : best(person.statements(type.getKey()))) {
                if (statement.value() instanceof WikidataValue.Text name && seen.add(name.text())) {
                    names.add(new AlternativeNameDto(name.text(), type.getValue()));
                }
            }
        }
        return names;
    }

    /** Reads the gender; a person without one is {@link GenderDto#UNKNOWN}. */
    private static GenderDto gender(WikidataEntity person, List<String> notes) {
        List<WikidataStatement> genders = best(person.statements(SEX_OR_GENDER));
        Set<GenderDto> distinct = genders.stream().map(WikidataStatement::value)
                .map(value -> value instanceof WikidataValue.Item item
                        ? GENDERS.getOrDefault(item.id(), GenderDto.OTHER) : GenderDto.OTHER)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (distinct.isEmpty()) {
            return GenderDto.UNKNOWN;
        }
        if (distinct.size() > 1) {
            notes.add("Wikidata gives several genders (" + distinct + "), using " + distinct.iterator().next());
        }
        return distinct.iterator().next();
    }

    /** Chooses the one place to use, with a note when Wikidata gives several. */
    private static WikidataStatement single(String what, List<WikidataStatement> statements,
                                            Map<String, WikidataEntity> places, List<String> notes) {
        List<WikidataStatement> best = best(statements);
        if (best.isEmpty()) {
            return null;
        }
        if (best.size() > 1) {
            notes.add("Wikidata gives several values for the " + what + " ("
                    + best.stream().map(statement -> label(statement.value(), places))
                    .collect(Collectors.joining(", ")) + "), using the first");
        }
        return best.getFirst();
    }

    /** Writes a place as its English name, after how sure the source is of it, if it says. */
    private static String place(String what, WikidataStatement statement, Map<String, WikidataEntity> places,
                                List<String> notes) {
        if (statement == null || !(statement.value() instanceof WikidataValue.Item item)) {
            return null;
        }
        WikidataEntity place = places.get(item.id());
        if (place == null || place.label() == null) {
            notes.add("The " + what + " " + item.id() + " has no English name, so it is left out");
            return null;
        }
        for (WikidataValue circumstance : statement.qualifier(WikidataDates.SOURCING_CIRCUMSTANCES)) {
            if (circumstance instanceof WikidataValue.Item hedge && HEDGES.containsKey(hedge.id())) {
                return HEDGES.get(hedge.id()) + " " + place.label();
            }
        }
        return place.label();
    }

    /** Finds the ISO code of the modern country of the place of birth. */
    private static String birthCountry(WikidataStatement birthPlace, Map<String, WikidataEntity> places,
                                       Map<String, WikidataEntity> countries, List<String> notes) {
        if (birthPlace == null || !(birthPlace.value() instanceof WikidataValue.Item item)
                || !places.containsKey(item.id())) {
            return null;
        }
        WikidataEntity place = places.get(item.id());
        for (String countryId : currentCountries(place)) {
            WikidataEntity country = countries.get(countryId);
            if (country == null) {
                continue;
            }
            for (WikidataStatement code : best(country.statements(ISO_COUNTRY_CODE))) {
                if (code.value() instanceof WikidataValue.Text text && COUNTRY_CODE.matcher(text.text()).matches()) {
                    return text.text();
                }
            }
        }
        notes.add("No modern country with an ISO code for the place of birth " + label(item, places)
                + ", so the birth country is left out");
        return null;
    }

    /** Returns the countries a place belongs to today: those without an end date, preferred first. */
    static List<String> currentCountries(WikidataEntity place) {
        return usable(place.statements(COUNTRY)).stream()
                .filter(statement -> statement.qualifier(END_TIME).isEmpty())
                .map(WikidataStatement::value)
                .filter(WikidataValue.Item.class::isInstance)
                .map(value -> ((WikidataValue.Item) value).id())
                .distinct()
                .toList();
    }

    /** Returns the preferred statements with a value if there are any, and otherwise the normal ones. */
    static List<WikidataStatement> best(List<WikidataStatement> statements) {
        List<WikidataStatement> usable = usable(statements);
        if (!usable.isEmpty() && usable.getFirst().rank() == Rank.PREFERRED) {
            return usable.stream().filter(statement -> statement.rank() == Rank.PREFERRED).toList();
        }
        return usable;
    }

    /** Returns the statements with a value that are not deprecated, the preferred ones first. */
    private static List<WikidataStatement> usable(List<WikidataStatement> statements) {
        List<WikidataStatement> usable = new ArrayList<>();
        for (Rank rank : List.of(Rank.PREFERRED, Rank.NORMAL)) {
            statements.stream()
                    .filter(statement -> statement.rank() == rank && statement.value() != null)
                    .forEach(usable::add);
        }
        return usable;
    }

    /** Returns the English name of a place, or its id if it has none. */
    private static String label(WikidataValue value, Map<String, WikidataEntity> places) {
        if (!(value instanceof WikidataValue.Item item)) {
            return "an unknown place";
        }
        WikidataEntity place = places.get(item.id());
        return place == null || place.label() == null ? item.id() : place.label();
    }

    /** Indexes items by their id. */
    private static Map<String, WikidataEntity> byId(List<WikidataEntity> entities) {
        return entities.stream().collect(Collectors.toMap(WikidataEntity::id, Function.identity(),
                (first, second) -> first, LinkedHashMap::new));
    }
}
