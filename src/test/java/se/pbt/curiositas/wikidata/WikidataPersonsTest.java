package se.pbt.curiositas.wikidata;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import se.pbt.curiositas.api.model.AlternativeNameDto;
import se.pbt.curiositas.api.model.GenderDto;
import se.pbt.curiositas.api.model.HistoricalDateInputDto;
import se.pbt.curiositas.api.model.NameTypeDto;
import se.pbt.curiositas.api.model.PersonInputDto;
import se.pbt.curiositas.wikidata.WikidataStatement.Rank;
import se.pbt.curiositas.wikidata.WikidataValue.Item;
import se.pbt.curiositas.wikidata.WikidataValue.Text;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;

/**
 * Verifies that persons from Wikidata become data files with only what Wikidata states, and with
 * notes wherever the review should look. The persons, places and countries are real items saved
 * from Wikidata.
 */
class WikidataPersonsTest {

    /**
     * Reads Julius Caesar with his places and modern country, and notes that the sources disagree
     * on his birth.
     */
    @Test
    void readsCaesar() {
        FetchedPerson caesar = fetchSaved(List.of("Q1048"), List.of("Q220", "Q944814"), List.of("Q38", "Q237"))
                .getFirst();

        PersonInputDto person = caesar.person();
        assertThat(person.getName()).isEqualTo("Julius Caesar");
        assertThat(person.getWikidataId()).isEqualTo("Q1048");
        assertThat(person.getAlsoKnownAs()).isNull();
        assertThat(person.getBirth()).isEqualTo(new HistoricalDateInputDto(-99).month(7).uncertaintyYears(null));
        assertThat(person.getDeath()).isEqualTo(new HistoricalDateInputDto(-43).month(3).day(15).uncertaintyYears(null));
        assertThat(person.getFloruit()).isNull();
        assertThat(person.getBirthPlace()).isEqualTo("Rome");
        assertThat(person.getDeathPlace()).isEqualTo("Theatre of Pompey");
        assertThat(person.getBirthCountry()).as("Italy, not one of Rome's historical states").isEqualTo("IT");
        assertThat(person.getGender()).isEqualTo(GenderDto.MALE);
        assertThat(caesar.notes()).containsExactly(
                "Sources disagree on the birth (Jul 100 BC, 13 Jul 100 BC, 102 BC, 100 BC), using Jul 100 BC");
    }

    /**
     * Reads Anne Bonny with her approximate birth, the place a source calls possible, and her
     * unknown death left out.
     */
    @Test
    void readsBonny() {
        PersonInputDto bonny = fetchSaved(List.of("Q231549"), List.of("Q840681"), List.of("Q27"))
                .getFirst().person();

        assertThat(bonny.getName()).isEqualTo("Anne Bonny");
        assertThat(bonny.getAlsoKnownAs()).containsExactly(new AlternativeNameDto("Anne", NameTypeDto.NICKNAME));
        assertThat(bonny.getBirth()).isEqualTo(new HistoricalDateInputDto(1697).uncertaintyYears(5));
        assertThat(bonny.getDeath()).isNull();
        assertThat(bonny.getBirthPlace()).isEqualTo("possibly Kinsale");
        assertThat(bonny.getDeathPlace()).isNull();
        assertThat(bonny.getBirthCountry()).isEqualTo("IE");
        assertThat(bonny.getGender()).isEqualTo(GenderDto.FEMALE);
    }

    /** Reads several persons in three requests in all: persons, places and countries. */
    @Test
    void readsPersonsPlacesAndCountriesInThreeRequests() {
        List<FetchedPerson> persons = fetchSaved(List.of("Q231549", "Q1048"),
                List.of("Q840681", "Q220", "Q944814"), List.of("Q27", "Q38", "Q237"));

        assertThat(persons).extracting(fetched -> fetched.person().getName())
                .containsExactly("Anne Bonny", "Julius Caesar");
    }

    /** Takes the modern country from the country statements without an end date, preferred first. */
    @Test
    void findsCurrentCountriesOfPlace() {
        assertThat(WikidataPersons.currentCountries(WikidataFixtures.entity("Q220"))).containsExactly("Q38", "Q237");
    }

    /** Leaves the birth country out, with a note, when no current country has an ISO code. */
    @Test
    void leavesOutBirthCountryWithoutIsoCode() {
        WikidataEntity place = entity("Q2", "Carthage", Map.of(WikidataPersons.COUNTRY,
                List.of(statement(WikidataPersons.COUNTRY, new Item("Q3")))));
        WikidataEntity state = entity("Q3", "Some ancient state", Map.of());

        FetchedPerson person = WikidataPersons.toPerson(person(Map.of(WikidataPersons.PLACE_OF_BIRTH,
                List.of(statement(WikidataPersons.PLACE_OF_BIRTH, new Item("Q2"))))), Map.of("Q2", place),
                Map.of("Q3", state));

        assertThat(person.person().getBirthPlace()).isEqualTo("Carthage");
        assertThat(person.person().getBirthCountry()).isNull();
        assertThat(person.notes()).containsExactly(
                "No modern country with an ISO code for the place of birth Carthage, so the birth country is left out");
    }

    /** Uses the first of several places of birth and notes all of them. */
    @Test
    void notesSeveralPlaces() {
        FetchedPerson person = WikidataPersons.toPerson(person(Map.of(WikidataPersons.PLACE_OF_DEATH, List.of(
                        statement(WikidataPersons.PLACE_OF_DEATH, new Item("Q2")),
                        statement(WikidataPersons.PLACE_OF_DEATH, new Item("Q3"))))),
                Map.of("Q2", entity("Q2", "Rome", Map.of()), "Q3", entity("Q3", "Naples", Map.of())), Map.of());

        assertThat(person.person().getDeathPlace()).isEqualTo("Rome");
        assertThat(person.notes()).containsExactly(
                "Wikidata gives several values for the place of death (Rome, Naples), using the first");
    }

    /** Leaves out a place without an English name instead of writing its id, with a note. */
    @Test
    void leavesOutPlaceWithoutName() {
        FetchedPerson person = WikidataPersons.toPerson(person(Map.of(WikidataPersons.PLACE_OF_DEATH,
                        List.of(statement(WikidataPersons.PLACE_OF_DEATH, new Item("Q2"))))),
                Map.of("Q2", entity("Q2", null, Map.of())), Map.of());

        assertThat(person.person().getDeathPlace()).isNull();
        assertThat(person.notes()).containsExactly("The place of death Q2 has no English name, so it is left out");
    }

    /**
     * Keeps only names Wikidata gives a type, birth names first, without the person's own name or
     * repeats.
     */
    @Test
    void keepsTypedNames() {
        Map<String, List<WikidataStatement>> names = new LinkedHashMap<>();
        names.put(WikidataPersons.PSEUDONYM, List.of(statement(WikidataPersons.PSEUDONYM, new Text("Lysander"))));
        names.put(WikidataPersons.NICKNAME, List.of(
                statement(WikidataPersons.NICKNAME, new Text("Edward Teach")),
                statement(WikidataPersons.NICKNAME, new Text("Blackbeard"))));
        names.put(WikidataPersons.BIRTH_NAME, List.of(
                statement(WikidataPersons.BIRTH_NAME, new Text("Edward Thatch")),
                new WikidataStatement(WikidataPersons.BIRTH_NAME, Rank.DEPRECATED, new Text("Edward Drummond"), Map.of())));

        assertThat(WikidataPersons.toPerson(person(names), Map.of(), Map.of()).person().getAlsoKnownAs())
                .containsExactly(
                        new AlternativeNameDto("Edward Thatch", NameTypeDto.BIRTH_NAME),
                        new AlternativeNameDto("Blackbeard", NameTypeDto.NICKNAME),
                        new AlternativeNameDto("Lysander", NameTypeDto.PEN_NAME));
    }

    /** Maps Wikidata's genders, with any other gender as OTHER and none as UNKNOWN. */
    @Test
    void mapsGenders() {
        assertThat(genderOf("Q6581097")).isEqualTo(GenderDto.MALE);
        assertThat(genderOf("Q6581072")).isEqualTo(GenderDto.FEMALE);
        assertThat(genderOf("Q2449503")).as("trans man").isEqualTo(GenderDto.MALE);
        assertThat(genderOf("Q1052281")).as("trans woman").isEqualTo(GenderDto.FEMALE);
        assertThat(genderOf("Q48270")).as("non-binary").isEqualTo(GenderDto.OTHER);
        assertThat(WikidataPersons.toPerson(person(Map.of()), Map.of(), Map.of()).person().getGender())
                .isEqualTo(GenderDto.UNKNOWN);
    }

    /** Refuses a person without an English name, since every file needs one. */
    @Test
    void refusesPersonWithoutName() {
        assertThatThrownBy(() -> WikidataPersons.toPerson(entity("Q1", null, Map.of()), Map.of(), Map.of()))
                .isInstanceOf(WikidataException.class)
                .hasMessage("Q1 has no English name on Wikidata");
    }

    /** Fetches saved persons, expecting one request for each of persons, places and countries. */
    private static List<FetchedPerson> fetchSaved(List<String> persons, List<String> places, List<String> countries) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer wikidata = MockRestServiceServer.bindTo(builder).build();
        for (List<String> round : List.of(persons, places, countries)) {
            wikidata.expect(anything()).andRespond(WikidataFixtures.answer(round.toArray(String[]::new)));
        }
        List<FetchedPerson> fetched = new WikidataPersons(new WikidataClient(builder,
                URI.create("https://wikidata.test/w/api.php"), "Curiositas/test")).fetch(persons);
        wikidata.verify();
        return fetched;
    }

    /** Returns the gender a person with one gender statement gets. */
    private static GenderDto genderOf(String item) {
        return WikidataPersons.toPerson(person(Map.of(WikidataPersons.SEX_OR_GENDER,
                List.of(statement(WikidataPersons.SEX_OR_GENDER, new Item(item))))), Map.of(), Map.of())
                .person().getGender();
    }

    /** Creates a person named Edward Teach with the given statements. */
    private static WikidataEntity person(Map<String, List<WikidataStatement>> statements) {
        return entity("Q1", "Edward Teach", statements);
    }

    /** Creates an item. */
    private static WikidataEntity entity(String id, String label, Map<String, List<WikidataStatement>> statements) {
        return new WikidataEntity(id, label, statements);
    }

    /** Creates a normal statement without qualifiers. */
    private static WikidataStatement statement(String property, WikidataValue value) {
        return new WikidataStatement(property, Rank.NORMAL, value, Map.of());
    }
}
