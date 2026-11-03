package se.pbt.curiositas.wikidata;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads items from Wikidata through its public API, with their English labels and statements.
 *
 * <p>Wikidata answers at most 50 items per request for clients like this one, so longer lists are
 * read in several requests. Every request names Curiositas in its {@code User-Agent}, as
 * Wikidata's rules require, so its operators can tell who is asking.
 */
public class WikidataClient {

    /** The most items Wikidata answers in one request. */
    static final int MAX_IDS_PER_REQUEST = 50;

    private static final Pattern ITEM_ID = Pattern.compile("^Q[1-9]\\d*$");

    private static final String ENTITY_URI_PREFIX = "http://www.wikidata.org/entity/";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient rest;

    /**
     * Creates the client.
     *
     * @param builder   the HTTP client to build on, with its timeouts already set
     * @param apiUrl    the address of Wikidata's API
     * @param userAgent the name Curiositas gives itself in every request
     */
    public WikidataClient(RestClient.Builder builder, URI apiUrl, String userAgent) {
        this.rest = builder
                .baseUrl(apiUrl.toString())
                .defaultHeader(HttpHeaders.USER_AGENT, userAgent)
                .build();
    }

    /**
     * Reads the given items.
     *
     * @param ids the items' ids, for example {@code Q1048}
     * @return the items, in the order of the ids
     * @throws IllegalArgumentException if an id is not an item id
     * @throws WikidataException        if Wikidata cannot be reached, answers with an error, or
     *                                  does not know one of the ids
     */
    public List<WikidataEntity> fetch(List<String> ids) {
        for (String id : ids) {
            if (!ITEM_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("Not a Wikidata item id: \"" + id + "\"");
            }
        }
        List<WikidataEntity> entities = new ArrayList<>();
        for (int start = 0; start < ids.size(); start += MAX_IDS_PER_REQUEST) {
            entities.addAll(fetchAtOnce(ids.subList(start, Math.min(start + MAX_IDS_PER_REQUEST, ids.size()))));
        }
        return entities;
    }

    /** Reads up to 50 items in one request. */
    private List<WikidataEntity> fetchAtOnce(List<String> ids) {
        JsonNode answer = request(ids);
        if (answer.has("error")) {
            JsonNode error = answer.get("error");
            throw new WikidataException("Wikidata answered with an error: " + error.path("code").asString()
                    + ", " + error.path("info").asString());
        }
        List<String> missing = new ArrayList<>();
        List<WikidataEntity> entities = new ArrayList<>();
        for (String id : ids) {
            JsonNode entity = answer.path("entities").path(id);
            if (entity.isMissingNode() || entity.has("missing")) {
                missing.add(id);
            } else {
                entities.add(entity(entity));
            }
        }
        if (!missing.isEmpty()) {
            throw new WikidataException("Not found on Wikidata, or merged into another item: "
                    + String.join(", ", missing));
        }
        return entities;
    }

    /** Sends one request for the items and reads the answer as JSON. */
    private JsonNode request(List<String> ids) {
        String body;
        try {
            body = rest.get()
                    .uri(uri -> uri
                            .queryParam("action", "wbgetentities")
                            .queryParam("ids", String.join("|", ids))
                            .queryParam("props", "labels|claims")
                            .queryParam("languages", "en")
                            .queryParam("format", "json")
                            .build())
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            throw new WikidataException("Could not read from Wikidata: " + e.getMessage(), e);
        }
        try {
            return JSON.readTree(body == null ? "" : body);
        } catch (JacksonException e) {
            throw new WikidataException("Wikidata answered with something that is not JSON", e);
        }
    }

    /** Reads one item from its JSON. */
    private static WikidataEntity entity(JsonNode json) {
        JsonNode label = json.path("labels").path("en").path("value");
        Map<String, List<WikidataStatement>> statements = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> property : json.path("claims").properties()) {
            List<WikidataStatement> forProperty = new ArrayList<>();
            for (JsonNode statement : property.getValue()) {
                forProperty.add(statement(property.getKey(), statement));
            }
            statements.put(property.getKey(), List.copyOf(forProperty));
        }
        return new WikidataEntity(json.path("id").asString(), label.isMissingNode() ? null : label.asString(),
                statements);
    }

    /** Reads one statement with its rank and qualifiers. */
    private static WikidataStatement statement(String property, JsonNode json) {
        Map<String, List<WikidataValue>> qualifiers = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> qualifier : json.path("qualifiers").properties()) {
            List<WikidataValue> values = new ArrayList<>();
            for (JsonNode snak : qualifier.getValue()) {
                WikidataValue value = value(snak);
                if (value != null) {
                    values.add(value);
                }
            }
            qualifiers.put(qualifier.getKey(), List.copyOf(values));
        }
        WikidataStatement.Rank rank = WikidataStatement.Rank.valueOf(json.path("rank").asString().toUpperCase());
        return new WikidataStatement(property, rank, value(json.path("mainsnak")), qualifiers);
    }

    /**
     * Reads the value of a snak, Wikidata's name for a property with its value.
     *
     * @return the value, or {@code null} when the snak says the value is unknown or that there is none
     */
    private static WikidataValue value(JsonNode snak) {
        if (!"value".equals(snak.path("snaktype").asString())) {
            return null;
        }
        JsonNode data = snak.path("datavalue");
        JsonNode value = data.path("value");
        return switch (data.path("type").asString()) {
            case "wikibase-entityid" -> new WikidataValue.Item(value.path("id").asString());
            case "time" -> new WikidataValue.Time(value.path("time").asString(), value.path("precision").asInt(),
                    value.path("calendarmodel").asString().replace(ENTITY_URI_PREFIX, ""));
            case "string" -> new WikidataValue.Text(value.asString());
            case "monolingualtext" -> new WikidataValue.Text(value.path("text").asString());
            default -> new WikidataValue.Unsupported(data.path("type").asString());
        };
    }
}
