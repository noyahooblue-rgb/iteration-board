package com.iterationboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class IterationBoardApplication {
    public static void main(String[] args) {
        SpringApplication.run(IterationBoardApplication.class, args);
    }
}
