package com.mac.scheduler.service.impl;

import com.mac.scheduler.service.HttpTransport;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.stereotype.Component;

@Component
public class JdkHttpTransport implements HttpTransport {

    private final HttpClient httpClient;

    public JdkHttpTransport(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public int send(HttpRequest request) throws IOException, InterruptedException {
        return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
