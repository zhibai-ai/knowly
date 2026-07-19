package com.knowly.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication(scanBasePackages = "com.knowly")
@EnableAsync
public class KnowlyApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(KnowlyApiApplication.class, args);
    }
}
