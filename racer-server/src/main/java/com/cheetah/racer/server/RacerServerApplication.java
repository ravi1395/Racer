package com.cheetah.racer.server;

import com.cheetah.racer.common.annotation.EnableRacer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.cheetah.racer.server", "com.cheetah.racer.common"})
@EnableRacer
public class RacerServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(RacerServerApplication.class, args);
    }
}
