package com.example.plsqlvisualizer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@code @SpringBootTest} calls SpringApplication.run, which invokes
 * ApplicationRunner beans — so without the "test" profile this would fire
 * {@link IrRunner} and go looking for a database. {@code @Profile("!test")} on
 * the runner keeps context loading a context check and nothing more.
 */
@SpringBootTest
@ActiveProfiles("test")
class PlsqlVisualizerApplicationTests {

    @Test
    void contextLoads() {
    }

}
