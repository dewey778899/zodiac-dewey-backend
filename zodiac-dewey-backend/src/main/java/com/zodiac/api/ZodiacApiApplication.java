package com.zodiac.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ZodiacApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(ZodiacApiApplication.class, args);
    }
}
