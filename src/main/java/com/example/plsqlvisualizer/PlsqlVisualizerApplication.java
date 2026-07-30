package com.example.plsqlvisualizer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point. Configuration lives in {@code application.yaml} and the work is
 * done by {@link IrRunner}, which runs one task and lets the process exit.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PlsqlVisualizerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlsqlVisualizerApplication.class, args);
    }

}
