package org.example;

public class Main {
    static final int START_PORT = 18080;

    public static void main(String[] args) {
        startServers();
    }

    static void startServers() {
        final int maxInstance = 5;
        int nextPort = START_PORT;
        for (int i = 1; i <= maxInstance; i++) {
            if (i < maxInstance) {
                new TracingServer("tracing-service-" + i, "tracing-service-" + (i + 1),
                                  nextPort, nextPort + 1);
            } else {
                new TracingServer("tracing-service-" + i, "tracing-service-" + (i + 1),
                                  nextPort, -1);
            }
            nextPort++;
        }
    }
}
