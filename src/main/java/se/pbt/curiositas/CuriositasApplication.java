package se.pbt.curiositas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of Curiositas, which curates historical facts into a versioned dataset with honest
 * uncertainty and publishes it through a REST API.
 */
@SpringBootApplication
public class CuriositasApplication {

    /**
     * Starts the application.
     *
     * @param args command line arguments passed on to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(CuriositasApplication.class, args);
    }
}
