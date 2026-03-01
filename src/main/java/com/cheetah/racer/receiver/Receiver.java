package com.cheetah.racer.receiver;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Receiver {

    private AtomicInteger count = new AtomicInteger();

    public void receiveMessage(String message) {
        log.info("Received <" + message + ">");
        count.incrementAndGet();
    }

    public int getCount() {
        return count.get();
    }
}
