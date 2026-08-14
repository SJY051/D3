package com.ddd.d3.battle;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableFeignClients
@EnableScheduling
@SpringBootApplication
public class BattleServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BattleServiceApplication.class, args);
    }
}
