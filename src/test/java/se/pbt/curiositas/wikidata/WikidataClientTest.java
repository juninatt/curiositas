package se.pbt.curiositas.wikidata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import se.pbt.curiositas.wikidata.WikidataStatement.Rank;
import se.pbt.curiositas.wikidata.WikidataValue.Item;
import se.pbt.curiositas.wikidata.WikidataValue.Text;
import se.pbt.curiositas.wikidata.WikidataValue.Time;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies reading items from Wikidata against saved answers from the real API (see
 * {@link WikidataFixtures}), so the tests never depend on Wikidata being
 * reachable or unchanged.
 */
class WikidataClientTest {

    private static final String USER_AGENT = "Curiositas/test (https://example.test)";

    private MockRestServiceServer wikidata;
    private WikidataClient client;

    /** Puts a fake Wikidata behind a fresh client. */
    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        wikidata = MockRestServiceServer.bindTo(builder).build();
        client = new WikidataClient(builder, URI.create("https://wikidata.test/w/api.php"), USER_AGENT);
    }

    /** Reads the label and every statement, with rank, precision, calendar and qualifiers. */
    @Test
    void readsLabelAndStatements() {
        wikidata.expect(asksFor("Q1048")).andRespond(WikidataFixtures.answer("Q1048"));

        WikidataEntity caesar = client.fetch(List.of("Q1048")).getFirst();

        assertThat(caesar.id()).isEqualTo("Q1048");
        assertThat(caesar.label()).isEqualTo("Julius Caesar");
        List<WikidataStatement> births = caesar.statements("P569");
        assertThat(births).extracting(WikidataStatement::rank)
                .containsExactly(Rank.PREFERRED, Rank.NORMAL, Rank.NORMAL, Rank.NORMAL);
        assertThat(births).extracting(WikidataStatement::value).containsExactly(
                new Time("-0100-07-12T00:00:00Z", 10, "Q1985786"),
                new Time("-0100-07-13T00:00:00Z", 11, "Q1985786"),
                new Time("-0102-00-00T00:00:00Z", 9, "Q1985786"),
                new Time("-0100-00-00T00:00:00Z", 9, "Q1985727"));
        assertThat(births.getFirst().qualifier("P1319")).singleElement().isInstanceOf(Time.class);
        assertThat(births.getFirst().qualifier("P1480")).singleElement().isInstanceOf(Item.class);
        assertThat(caesar.statements("P19")).extracting(WikidataStatement::value).containsExactly(new Item("Q220"));
        assertThat(caesar.statements("P1477")).isEmpty();
    }

    /** Reads an unknown value as null and keeps deprecated statements with their rank. */
    @Test
    void readsUnknownValuesAndDeprecatedStatements() {
        wikidata.expect(asksFor("Q231549")).andRespond(WikidataFixtures.answer("Q231549"));

        WikidataEntity bonny = client.fetch(List.of("Q231549")).getFirst();

        assertThat(bonny.statements("P569")).extracting(WikidataStatement::rank)
                .containsExactly(Rank.DEPRECATED, Rank.PREFERRED, Rank.NORMAL);
        assertThat(bonny.statements("P570")).singleElement()
                .satisfies(death -> assertThat(death.value()).as("unknown date of death").isNull());
        assertThat(bonny.statements("P1449")).extracting(WikidataStatement::value).containsExactly(new Text("Anne"));
    }

    /** Asks for exactly the items, labels in English and statements, and keeps the order asked for. */
    @Test
    void returnsItemsInTheOrderAsked() {
        wikidata.expect(asksFor("Q231549", "Q1048"))
                .andExpect(query("action", "wbgetentities"))
                .andExpect(query("props", "labels|claims"))
                .andExpect(query("languages", "en"))
                .andRespond(WikidataFixtures.answer("Q1048", "Q231549"));

        assertThat(client.fetch(List.of("Q231549", "Q1048"))).extracting(WikidataEntity::label)
                .containsExactly("Anne Bonny", "Julius Caesar");
    }

    /** Names Curiositas in every request, as Wikidata's rules require. */
    @Test
    void sendsUserAgent() {
        wikidata.expect(header("User-Agent", USER_AGENT)).andRespond(WikidataFixtures.answer("Q1048"));

        client.fetch(List.of("Q1048"));

        wikidata.verify();
    }

    /** Splits a long list into requests of at most 50 items, the most Wikidata answers at once. */
    @Test
    void asksForAtMost50ItemsPerRequest() {
        List<String> ids = IntStream.rangeClosed(1, 51).mapToObj(n -> "Q" + n).toList();
        wikidata.expect(asksFor(ids.subList(0, 50).toArray(String[]::new))).andRespond(emptyItems(ids.subList(0, 50)));
        wikidata.expect(asksFor("Q51")).andRespond(emptyItems(List.of("Q51")));

        assertThat(client.fetch(ids)).extracting(WikidataEntity::id).isEqualTo(ids);
        wikidata.verify();
    }

    /** Names every id Wikidata does not know, and only those. */
    @Test
    void reportsUnknownIds() {
        wikidata.expect(asksFor("Q1048", "Q999999999"))
                .andRespond(withSuccess(WikidataFixtures.entities(Map.of("Q1048", WikidataFixtures.item("Q1048"),
                        "Q999999999", "{ \"id\": \"Q999999999\", \"missing\": \"\" }")), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetch(List.of("Q1048", "Q999999999")))
                .isInstanceOf(WikidataException.class)
                .hasMessageContaining("Q999999999")
                .hasMessageNotContaining("Q1048");
    }

    /** Passes on Wikidata's own error, such as when it is too busy to answer. */
    @Test
    void reportsErrorFromWikidata() {
        wikidata.expect(asksFor("Q1048")).andRespond(withSuccess(
                "{ \"error\": { \"code\": \"maxlag\", \"info\": \"Waiting for a database server\" } }",
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetch(List.of("Q1048")))
                .isInstanceOf(WikidataException.class)
                .hasMessageContaining("maxlag")
                .hasMessageContaining("Waiting for a database server");
    }

    /** Reports a failed request as a Wikidata problem, not as an unexplained crash. */
    @Test
    void reportsFailedRequest() {
        wikidata.expect(asksFor("Q1048")).andRespond(withServerError());

        assertThatThrownBy(() -> client.fetch(List.of("Q1048")))
                .isInstanceOf(WikidataException.class)
                .hasMessageStartingWith("Could not read from Wikidata");
    }

    /** Reports an answer that is not JSON, such as an error page. */
    @Test
    void reportsAnswerThatIsNotJson() {
        wikidata.expect(asksFor("Q1048")).andRespond(withSuccess("<html>Down for maintenance</html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> client.fetch(List.of("Q1048")))
                .isInstanceOf(WikidataException.class)
                .hasMessageContaining("not JSON");
    }

    /** Keeps values of kinds it does not read, and leaves unknown qualifier values out. */
    @Test
    void keepsUnsupportedValuesAndSkipsUnknownQualifiers() {
        wikidata.expect(asksFor("Q1")).andRespond(withSuccess(WikidataFixtures.entities(Map.of("Q1", """
                { "id": "Q1", "labels": {}, "claims": { "P2067": [ {
                    "rank": "normal",
                    "mainsnak": { "snaktype": "value", "property": "P2067",
                                  "datavalue": { "type": "quantity", "value": { "amount": "+70" } } },
                    "qualifiers": { "P585": [ { "snaktype": "somevalue", "property": "P585" } ] } } ] } }""")),
                MediaType.APPLICATION_JSON));

        WikidataEntity item = client.fetch(List.of("Q1")).getFirst();

        assertThat(item.label()).isNull();
        assertThat(item.statements("P2067")).singleElement().satisfies(statement -> {
            assertThat(statement.value()).isEqualTo(new WikidataValue.Unsupported("quantity"));
            assertThat(statement.qualifier("P585")).isEmpty();
        });
    }

    /** Rejects anything but item ids before asking Wikidata, such as a property or a lower-case id. */
    @ParameterizedTest
    @ValueSource(strings = {"P31", "q1048", "Q0", "Q01", "Q1048 ", "Q1048|Q1"})
    void rejectsIdsThatAreNotItems(String id) {
        assertThatThrownBy(() -> client.fetch(List.of(id)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(id);
        wikidata.verify();
    }

    /** Matches a GET request for exactly these ids, in this order. */
    private static RequestMatcher asksFor(String... ids) {
        RequestMatcher get = method(GET);
        RequestMatcher idsMatch = query("ids", String.join("|", ids));
        return request -> {
            get.match(request);
            idsMatch.match(request);
        };
    }

    /** Matches a request whose query parameter, decoded, has exactly this value. */
    private static RequestMatcher query(String name, String value) {
        return request -> assertThat(URLDecoder.decode(UriComponentsBuilder.fromUri(request.getURI()).build()
                .getQueryParams().getFirst(name), StandardCharsets.UTF_8)).as(name).isEqualTo(value);
    }

    /** Answers with items that have neither label nor statements. */
    private static ResponseCreator emptyItems(List<String> ids) {
        Map<String, String> items = new LinkedHashMap<>();
        for (String id : ids) {
            items.put(id, "{ \"id\": \"" + id + "\", \"labels\": {}, \"claims\": {} }");
        }
        return withSuccess(WikidataFixtures.entities(items), MediaType.APPLICATION_JSON);
    }
}
