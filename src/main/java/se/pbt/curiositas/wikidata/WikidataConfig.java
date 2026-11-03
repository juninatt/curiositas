package se.pbt.curiositas.wikidata;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Sets up the client that reads from Wikidata. No request is sent until a fetch asks for one, so
 * the application starts the same whether Wikidata can be reached or not.
 */
@Configuration
@EnableConfigurationProperties(WikidataConfig.WikidataProperties.class)
public class WikidataConfig {

    /** Where Curiositas can be found, so Wikidata's operators can see who is asking. */
    private static final String PROJECT_URL = "https://github.com/juninatt/curiositas";

    /**
     * Creates the client, with timeouts so a fetch fails instead of hanging when Wikidata is slow.
     *
     * @param properties where Wikidata's API is and how long to wait for it
     * @param release    the version of this release, sent as part of the {@code User-Agent}
     * @return the client
     */
    @Bean
    WikidataClient wikidataClient(WikidataProperties properties, @Value("${curiositas.release}") String release) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(properties.timeout());
        return new WikidataClient(RestClient.builder().requestFactory(requests), properties.apiUrl(),
                "Curiositas/" + release + " (" + PROJECT_URL + ")");
    }

    /**
     * Settings for reading from Wikidata.
     *
     * @param apiUrl  the address of Wikidata's API
     * @param timeout how long to wait for Wikidata to connect and to answer
     */
    @ConfigurationProperties("curiositas.wikidata")
    public record WikidataProperties(
            @DefaultValue("https://www.wikidata.org/w/api.php") URI apiUrl,
            @DefaultValue("30s") Duration timeout) {
    }
}
