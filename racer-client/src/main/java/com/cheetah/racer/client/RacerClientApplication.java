package com.cheetah.racer.client;

import com.cheetah.racer.common.annotation.EnableRacer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.cheetah.racer.client", "com.cheetah.racer.common"})
@EnableScheduling
@EnableRacer
public class RacerClientApplication {

    public static void main(String[] args) {
        SpringApplication.run(RacerClientApplication.class, args);
    }
}
