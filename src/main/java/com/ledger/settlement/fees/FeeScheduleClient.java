package com.ledger.settlement.fees;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Blocking HTTP client for the downstream fee-schedule service. Each line of the response
 * is "merchantId=basisPoints". If the downstream is unreachable the table is empty and the
 * default fee rate applies, so the settlement answer never depends on the downstream being up.
 */
public final class FeeScheduleClient {

    private static final String URL =
            System.getenv().getOrDefault("LEDGER_FEE_SCHEDULE_URL", "http://localhost:9090/fees");

    private FeeScheduleClient() {
    }

    public static Map<String, Long> fetchOverrides() {
        Map<String, Long> table = new HashMap<>();
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(URL))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            for (String line : response.body().split("\\R")) {
                String[] parts = line.trim().split("=");
                if (parts.length == 2) {
                    table.put(parts[0], Long.parseLong(parts[1]));
                }
            }
        } catch (IOException e) {
            System.err.println("fee schedule unavailable, using default fee rate: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Map.copyOf(table);
    }
}