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
        return gateway(ObservationRegistry.NOOP, Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    private BinanceRateGateway gateway(ObservationRegistry observations, Duration connect, Duration read) {
        return BinanceRateGateway.create(baseUrl(), connect, read, observations);
    }

    /** What a tracing handler sees: errors at the moment they are recorded, key values when the call ends. */
    private static final class Recorded implements ObservationHandler<Observation.Context> {
        final List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        final List<Map<String, String>> keyValues = Collections.synchronizedList(new ArrayList<>());
        final List<String> names = Collections.synchronizedList(new ArrayList<>());

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

        @Override
        public void onError(Observation.Context context) {
            errors.add(context.getError());
        }

        @Override
        public void onStop(Observation.Context context) {
            var kv = new java.util.HashMap<String, String>();
            context.getAllKeyValues().forEach(k -> kv.put(k.getKey(), k.getValue()));
            keyValues.add(kv);
            names.add(context.getName());
        }
    }

    private static ObservationRegistry registry(Recorded recorded) {
        var registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(recorded);
        return registry;
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

    // Spec 0002: only the futures adapter reads `time`; the spot adapter keeps the fetch time.
    @Test
    void spot_adapter_ignores_a_time_field_in_the_response() {
        handler = ex -> respond(
                ex, 200, "{\"symbol\":\"" + symbolOf(ex) + "\",\"price\":\"1.09890000\",\"time\":1794737730000}");

        var quotes = gateway().fetch(List.of(btc));

        assertThat(quotes.get("BTCUSDT").price()).isEqualTo(new BigDecimal("1.09890000"));
        assertThat(quotes.get("BTCUSDT").quoteTime()).isNull();
    }

    // The single-symbol endpoint answers with one object; an array would be another symbol's price.
    @Test
    void an_array_response_is_not_taken_as_the_symbols_price() {
        handler = ex -> {
            if (symbolOf(ex).equals("ETHUSDT")) {
                respond(ex, 200, "[" + priceBody("BTCUSDT", "3400.5") + "]");
            } else {
                respond(ex, 200, priceBody(symbolOf(ex), "5"));
            }
        };

        assertThat(gateway().fetch(List.of(btc, eth))).containsOnlyKeys("BTCUSDT");
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

    // A provider that rate-limits (429) or bans (418) is left alone for the rest of the poll.
    @Test
    void a_rate_limited_symbol_ends_the_poll_and_keeps_the_rates_already_fetched() {
        handler = ex -> {
            if (symbolOf(ex).equals("ETHUSDT")) {
                respond(ex, 429, "{\"msg\":\"" + BODY_TEXT + "\"}");
            } else {
                respond(ex, 200, priceBody(symbolOf(ex), "7"));
            }
        };

        var quotes = gateway().fetch(List.of(btc, eth, qnt));

        assertThat(quotes).containsOnlyKeys("BTCUSDT");
        assertThat(requests).hasSize(2);
    }

    @Test
    void a_ban_418_on_the_first_symbol_sends_no_further_request() {
        handler = ex -> respond(ex, 418, "{\"msg\":\"" + BODY_TEXT + "\"}");

        assertThatThrownBy(() -> gateway().fetch(List.of(btc, eth, qnt))).isInstanceOf(RateProviderException.class);

        assertThat(requests).hasSize(1);
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
        var gateway = gateway(ObservationRegistry.NOOP, Duration.ofSeconds(1), Duration.ofMillis(300));

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
    void each_call_is_one_observation_tagged_with_provider_template_and_outcome_and_no_symbol() {
        handler = ex -> respond(ex, 200, priceBody(symbolOf(ex), "1"));
        var recorded = new Recorded();

        gateway(registry(recorded), Duration.ofSeconds(1), Duration.ofSeconds(2))
                .fetch(List.of(btc, eth));

        assertThat(recorded.names).containsExactly("market.rates.provider.request", "market.rates.provider.request");
        assertThat(recorded.errors).isEmpty();
        assertThat(recorded.keyValues).hasSize(2).allSatisfy(kv -> {
            assertThat(kv.values()).noneMatch(v -> v.contains("BTCUSDT") || v.contains("ETHUSDT") || v.contains("?"));
            assertThat(kv)
                    .containsEntry("provider", "binance")
                    .containsEntry("uri", "/api/v3/ticker/price")
                    .containsEntry("outcome", "SUCCESS");
        });
    }

    // Criteria 12, 15 / ADR 0002. A tracing handler exports an error the moment it is recorded (onError), with
    // its message, so that is where the URL, the symbol and the body must already be gone. RestClient's own
    // observation fails this: it records its exception, whose message is the full URL, before any filter runs.
    @Test
    void errors_reach_observation_handlers_without_url_symbol_or_body() {
        var recorded = new Recorded();
        var failing = gateway(registry(recorded), Duration.ofSeconds(1), Duration.ofSeconds(2));
        handler = ex -> respond(ex, 400, "{\"code\":-1121,\"msg\":\"" + BODY_TEXT + "\"}");
        assertThatThrownBy(() -> failing.fetch(List.of(btc))).isInstanceOf(RateProviderException.class);
        server.stop(0);
        assertThatThrownBy(() -> failing.fetch(List.of(btc))).isInstanceOf(RateProviderException.class);

        assertThat(recorded.errors).hasSize(2).allSatisfy(e -> {
            for (Throwable t = e; t != null; t = t.getCause()) {
                assertThat(String.valueOf(t.getMessage()))
                        .doesNotContain("BTCUSDT")
                        .doesNotContain("symbol=")
                        .doesNotContain("/api/v3")
                        .doesNotContain("127.0.0.1")
                        .doesNotContain(BODY_TEXT);
            }
        });
        assertThat(recorded.keyValues).extracting(kv -> kv.get("outcome")).containsExactly("HTTP_ERROR", "IO_ERROR");
        assertThat(recorded.keyValues)
                .allSatisfy(kv -> assertThat(kv.values())
                        .noneMatch(v -> v.contains("BTCUSDT") || v.contains("127.0.0.1") || v.contains("?")));
    }

    // The RestClient itself must not observe: its observation carries the full URL in the error and in http.url.
    @Test
    void the_http_client_records_no_observation_of_its_own() {
        handler = ex -> respond(ex, 200, priceBody(symbolOf(ex), "1"));
        var recorded = new Recorded();

        gateway(registry(recorded), Duration.ofSeconds(1), Duration.ofSeconds(2))
                .fetch(List.of(btc));

        assertThat(recorded.names).containsExactly("market.rates.provider.request");
        assertThat(recorded.keyValues).allSatisfy(kv -> assertThat(kv).doesNotContainKey("http.url"));
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
