package se.pbt.curiositas.wikidata;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Gives tests the items saved from Wikidata in {@code src/test/resources/wikidata}, either as the
 * API's answer or already read, so every test works on real data without calling Wikidata.
 */
public final class WikidataFixtures {

    /** Not instantiable; all methods are static. */
    private WikidataFixtures() {
    }

    /**
     * Reads a saved item the way the client reads it from Wikidata.
     *
     * @param id the item's id, for example {@code Q1048}
     * @return the item
     */
    public static WikidataEntity entity(String id) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer wikidata = MockRestServiceServer.bindTo(builder).build();
        wikidata.expect(anything()).andRespond(answer(id));
        return new WikidataClient(builder, URI.create("https://wikidata.test/w/api.php"), "Curiositas/test")
                .fetch(List.of(id)).getFirst();
    }

    /**
     * Answers with saved items, as Wikidata's API would.
     *
     * @param ids the items' ids
     * @return the answer
     */
    public static ResponseCreator answer(String... ids) {
        Map<String, String> items = new LinkedHashMap<>();
        for (String id : ids) {
            items.put(id, item(id));
        }
        return withSuccess(entities(items), MediaType.APPLICATION_JSON);
    }

    /**
     * Wraps items in an answer as Wikidata's API gives it.
     *
     * @param items the JSON of each item, by id
     * @return the answer's JSON
     */
    public static String entities(Map<String, String> items) {
        StringBuilder json = new StringBuilder("{ \"entities\": {");
        String separator = "";
        for (Map.Entry<String, String> item : items.entrySet()) {
            json.append(separator).append('"').append(item.getKey()).append("\": ").append(item.getValue());
            separator = ",";
        }
        return json.append("}, \"success\": 1 }").toString();
    }

    /**
     * Reads the JSON of a saved item.
     *
     * @param id the item's id
     * @return the JSON, as it appears under {@code entities} in the API's answer
     */
    public static String item(String id) {
        try {
            return new ClassPathResource("wikidata/" + id + ".json").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
