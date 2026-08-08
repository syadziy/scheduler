package com.mac.scheduler.service;

import java.io.IOException;
import java.net.http.HttpRequest;

public interface HttpTransport {

    int send(HttpRequest request) throws IOException, InterruptedException;
}
