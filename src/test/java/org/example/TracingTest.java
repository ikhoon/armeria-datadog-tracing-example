package org.example;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;

class TracingTest {
    @BeforeAll
    static void beforeAll() {
        Main.startServers();
    }

    @Test
    void test() {
        final BlockingWebClient client = BlockingWebClient.of("http://127.0.0.1:" + Main.START_PORT);
        final AggregatedHttpResponse response = client.get("/");
        assertThat(response.contentUtf8()).isEqualTo("A traced response");
    }
}
