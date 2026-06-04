package com.mediquery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MediQueryApplication {
    public static void main(String[] args) {
        SpringApplication.run(MediQueryApplication.class, args);
    }
}
