package io.github.bot.marketrates.adapter.out.binance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.bot.marketrates.application.port.out.ProviderQuote;
import io.github.bot.marketrates.application.port.out.RateProviderException;
import io.github.bot.marketrates.domain.Asset;
import io.github.bot.marketrates.domain.AssetKind;
import io.github.bot.marketrates.testsupport.LogCapture;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Criteria 6, 7, 9, 12, 15 against a local HTTP server standing in for Binance. */
class BinanceRateGatewayTest {

    private static final String BODY_TEXT = "Invalid symbol.";

    private HttpServer server;
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String, List<String>>> requestHeaders = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch releaseSlow = new CountDownLatch(1);
    private volatile Consumer<HttpExchange> handler = ex -> respond(ex, 200, "{}");

    private final Asset btc = asset("btc-usdt", "BTCUSDT");
    private final Asset eth = asset("eth-usdt", "ETHUSDT");
    private final Asset qnt = asset("qnt-usdt", "QNTUSDT");

    private static Asset asset(String id, String symbol) {
        return new Asset(id, id, AssetKind.CRYPTO, "binance", symbol, "USDT", 8, RoundingMode.HALF_EVEN);
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            requests.add(
                    ex.getRequestURI().getRawPath() + "?" + ex.getRequestURI().getRawQuery());
            requestHeaders.add(Map.copyOf(ex.getRequestHeaders()));
            handler.accept(ex);
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        releaseSlow.countDown();
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private BinanceRateGateway gateway() {
        return gateway(RestClient.builder(), Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    private BinanceRateGateway gateway(RestClient.Builder builder, Duration connect, Duration read) {
        return BinanceRateGateway.create(baseUrl(), connect, read, builder);
    }

    private static void respond(HttpExchange ex, int status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                ex.getResponseBody().write(bytes);
            }
            ex.close();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String symbolOf(HttpExchange ex) {
        String q = ex.getRequestURI().getRawQuery();
        return q.substring(q.indexOf('=') + 1);
    }

    private static String priceBody(String symbol, String price) {
        return "{\"symbol\":\"" + symbol + "\",\"price\":\"" + price + "\"}";
    }

    @Test
    void provider_name_is_binance() {
        assertThat(gateway().provider()).isEqualTo("binance");
    }

    // Criterion 6
    @Test
    void single_symbol_object_response_is_parsed_with_full_precision_and_no_quote_time() {
        handler = ex -> respond(ex, 200, priceBody(symbolOf(ex), "96250.123456789"));

        Map<String, ProviderQuote> quotes = gateway().fetch(List.of(btc));

        assertThat(quotes).containsOnlyKeys("BTCUSDT");
        assertThat(quotes.get("BTCUSDT").price()).isEqualTo(new BigDecimal("96250.123456789"));
        assertThat(quotes.get("BTCUSDT").quoteTime()).isNull();
    }

    @Test
    void array_response_is_parsed_too() {
        handler = ex -> respond(ex, 200, "[" + priceBody(symbolOf(ex), "3400.5") + "]");

        var quotes = gateway().fetch(List.of(eth));

        assertThat(quotes.get("ETHUSDT").price()).isEqualByComparingTo("3400.5");
    }

    // Criterion 12
    @Test
    void one_sequential_request_per_asset_to_the_ticker_endpoint_without_credentials() {
        handler = ex -> respond(ex, 200, priceBody(symbolOf(ex), "1"));

        var quotes = gateway().fetch(List.of(btc, eth, qnt));

        assertThat(quotes).containsOnlyKeys("BTCUSDT", "ETHUSDT", "QNTUSDT");
        assertThat(requests)
                .containsExactly(
                        "/api/v3/ticker/price?symbol=BTCUSDT",
                        "/api/v3/ticker/price?symbol=ETHUSDT",
                        "/api/v3/ticker/price?symbol=QNTUSDT");
        assertThat(requestHeaders).allSatisfy(h -> {
            var names = h.keySet().stream().map(String::toLowerCase).toList();
            assertThat(names).doesNotContain("authorization", "x-mbx-apikey");
        });
    }

    // Criterion 9 and 7: partial failure
    @Test
    void a_symbol_rejected_with_400_is_omitted_and_the_others_are_returned_without_logging_the_body() {
        handler = ex -> {
            if (symbolOf(ex).equals("ETHUSDT")) {
                respond(ex, 400, "{\"code\":-1121,\"msg\":\"" + BODY_TEXT + "\"}");
            } else {
                respond(ex, 200, priceBody(symbolOf(ex), "7"));
            }
        };

        try (var logs = LogCapture.start()) {
            var quotes = gateway().fetch(List.of(btc, eth, qnt));

            assertThat(quotes).containsOnlyKeys("BTCUSDT", "QNTUSDT");
            assertNoLeak(logs.text());
            assertThat(logs.warnText()).contains("binance").contains("400");
        }
    }

    @Test
    void every_symbol_failing_with_5xx_throws_a_provider_exception_and_logs_status_only() {
        handler = ex -> respond(ex, 503, "{\"msg\":\"" + BODY_TEXT + "\"}");

        try (var logs = LogCapture.start()) {
            assertThatThrownBy(() -> gateway().fetch(List.of(btc, eth)))
                    .isInstanceOf(RateProviderException.class)
                    .message()
                    .doesNotContain("BTCUSDT")
                    .doesNotContain("symbol=")
                    .doesNotContain(BODY_TEXT);

            assertNoLeak(logs.text());
            assertThat(logs.warnText()).contains("binance").contains("503");
        }
    }

    @Test
    void rate_limited_429_for_every_symbol_throws_a_provider_exception() {
        handler = ex -> respond(ex, 429, "{\"msg\":\"" + BODY_TEXT + "\"}");

        try (var logs = LogCapture.start()) {
            assertThatThrownBy(() -> gateway().fetch(List.of(btc, eth, qnt))).isInstanceOf(RateProviderException.class);

            assertNoLeak(logs.text());
            assertThat(logs.warnText()).contains("binance").contains("429");
        }
    }

    @Test
    void a_response_slower_than_the_read_timeout_fails_the_symbol_and_logs_only_the_exception_class() {
        handler = ex -> {
            try {
                releaseSlow.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(ex, 200, priceBody(symbolOf(ex), "1"));
        };
        var gateway = gateway(RestClient.builder(), Duration.ofSeconds(1), Duration.ofMillis(300));

        try (var logs = LogCapture.start()) {
            assertThatThrownBy(() -> gateway.fetch(List.of(btc))).isInstanceOf(RateProviderException.class);

            assertNoLeak(logs.text());
            assertThat(logs.warnText()).contains("binance").contains("Exception");
        }
    }

    @Test
    void an_unreachable_provider_throws_a_provider_exception() {
        server.stop(0);

        assertThatThrownBy(() -> gateway().fetch(List.of(btc))).isInstanceOf(RateProviderException.class);
    }

    // Criterion 7: invalid payloads
    @Test
    void an_unparsable_price_is_omitted_and_not_logged() {
        handler = ex -> {
            if (symbolOf(ex).equals("BTCUSDT")) {
                respond(ex, 200, priceBody("BTCUSDT", "not-a-number-xyz"));
            } else {
                respond(ex, 200, priceBody(symbolOf(ex), "5"));
            }
        };

        try (var logs = LogCapture.start()) {
            var quotes = gateway().fetch(List.of(btc, eth));

            assertThat(quotes).containsOnlyKeys("ETHUSDT");
            assertThat(logs.text()).doesNotContain("not-a-number-xyz");
            assertNoLeak(logs.text());
        }
    }

    @Test
    void a_missing_price_field_is_omitted() {
        handler = ex -> {
            if (symbolOf(ex).equals("BTCUSDT")) {
                respond(ex, 200, "{\"symbol\":\"BTCUSDT\"}");
            } else {
                respond(ex, 200, priceBody(symbolOf(ex), "5"));
            }
        };

        var quotes = gateway().fetch(List.of(btc, eth));

        assertThat(quotes).containsOnlyKeys("ETHUSDT");
    }

    @Test
    void every_price_unparsable_means_all_failed_and_throws() {
        handler = ex -> respond(ex, 200, priceBody(symbolOf(ex), "garbage"));

        assertThatThrownBy(() -> gateway().fetch(List.of(btc, eth))).isInstanceOf(RateProviderException.class);
    }

    // Criterion 15
    @Test
    void observations_carry_the_uri_template_and_no_symbol_in_any_key_value() {
        handler = ex -> respond(ex, 200, priceBody(symbolOf(ex), "1"));
        var captured = new ArrayList<Map<String, String>>();
        var registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                var kv = new java.util.HashMap<String, String>();
                context.getAllKeyValues().forEach(k -> kv.put(k.getKey(), k.getValue()));
                synchronized (captured) {
                    captured.add(kv);
                }
            }
        });

        gateway(RestClient.builder().observationRegistry(registry), Duration.ofSeconds(1), Duration.ofSeconds(2))
                .fetch(List.of(btc, eth));

        assertThat(captured).isNotEmpty();
        assertThat(captured).allSatisfy(kv -> {
            assertThat(kv.values()).noneMatch(v -> v.contains("BTCUSDT") || v.contains("ETHUSDT"));
            assertThat(kv).containsKey("uri");
            assertThat(kv.get("uri")).contains("/api/v3/ticker/price");
        });
    }

    // Criterion 15 / ADR 0002: the error recorded on the observation (exported on spans) is clean too
    @Test
    void errors_recorded_on_observations_carry_no_url_symbol_or_body() {
        var errors = new ArrayList<Throwable>();
        var registry = ObservationRegistry.create();
        registry.observationConfig().observationFilter(new ErrorSanitizingObservationFilter());
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                if (context.getError() != null) {
                    synchronized (errors) {
                        errors.add(context.getError());
                    }
                }
            }
        });
        handler = ex -> respond(ex, 400, "{\"code\":-1121,\"msg\":\"" + BODY_TEXT + "\"}");
        var failing = gateway(
                RestClient.builder().observationRegistry(registry), Duration.ofSeconds(1), Duration.ofSeconds(2));
        assertThatThrownBy(() -> failing.fetch(List.of(btc))).isInstanceOf(RateProviderException.class);
        server.stop(0);
        assertThatThrownBy(() -> failing.fetch(List.of(btc))).isInstanceOf(RateProviderException.class);

        assertThat(errors).hasSize(2).allSatisfy(e -> {
            for (Throwable t = e; t != null; t = t.getCause()) {
                assertThat(String.valueOf(t.getMessage()))
                        .doesNotContain("BTCUSDT")
                        .doesNotContain("symbol=")
                        .doesNotContain("/api/v3")
                        .doesNotContain(BODY_TEXT);
            }
        });
    }

    @Test
    void a_price_in_exponent_form_is_omitted() {
        handler = ex -> {
            if (symbolOf(ex).equals("BTCUSDT")) {
                respond(ex, 200, priceBody("BTCUSDT", "1E+999999999"));
            } else {
                respond(ex, 200, priceBody(symbolOf(ex), "5"));
            }
        };

        assertThat(gateway().fetch(List.of(btc, eth))).containsOnlyKeys("ETHUSDT");
    }

    private static void assertNoLeak(String logs) {
        assertThat(logs)
                .doesNotContain(BODY_TEXT)
                .doesNotContain("-1121")
                .doesNotContain("symbol=")
                .doesNotContain("BTCUSDT")
                .doesNotContain("ETHUSDT")
                .doesNotContain("/api/v3");
    }
}
