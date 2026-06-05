package com.mediquery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 医问通 - 智慧医药查询系统启动类
 */
@SpringBootApplication
@EnableScheduling
public class MediQueryApplication {
    public static void main(String[] args) {
        SpringApplication.run(MediQueryApplication.class, args);
    }
}
