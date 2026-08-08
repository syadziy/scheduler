package com.mac.scheduler.utils;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class WorkerIdentity {

    private final String value = resolve();

    public String value() {
        return value;
    }

    private static String resolve() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException exception) {
            host = "unknown-host";
        }
        return host + ":" + ManagementFactory.getRuntimeMXBean().getName() + ":" + UUID.randomUUID();
    }
}
