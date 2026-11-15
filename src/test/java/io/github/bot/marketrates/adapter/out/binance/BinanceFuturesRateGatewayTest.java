package io.github.bot.marketrates.adapter.out.binance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Spec 0002 criteria 4, 7, 8, 9, 10, 11 against a local HTTP server standing in for Binance USDⓈ-M futures. */
class BinanceFuturesRateGatewayTest {

    private static final String BODY_TEXT = "Invalid symbol.";

    private HttpServer server;
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String, List<String>>> requestHeaders = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch releaseSlow = new CountDownLatch(1);
    private volatile Consumer<HttpExchange> handler = ex -> respond(ex, 200, "{}");

    private final Asset xau = asset("xau-usdt", "XAUUSDT", AssetKind.METAL);
    private final Asset xag = asset("xag-usdt", "XAGUSDT", AssetKind.METAL);
    private final Asset nvda = asset("nvda-usdt", "NVDAUSDT", AssetKind.EQUITY);
    private final Asset spy = asset("spy-usdt", "SPYUSDT", AssetKind.INDEX);

    private static Asset asset(String id, String symbol, AssetKind kind) {
        return new Asset(id, id, kind, "binance-futures", symbol, "USDT", 2, RoundingMode.HALF_EVEN);
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
        return BinanceRateGateway.createFutures(baseUrl(), connect, read, observations);
    }

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
            var kv = new HashMap<String, String>();
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

    private static String body(String symbol, String price, long time) {
        return "{\"symbol\":\"" + symbol + "\",\"price\":\"" + price + "\",\"time\":" + time + "}";
    }

    private static String bodyWithRawTime(String symbol, String price, String rawTimeJson) {
        return "{\"symbol\":\"" + symbol + "\",\"price\":\"" + price + "\",\"time\":" + rawTimeJson + "}";
    }

    @Test
    void provider_name_is_binance_futures() {
        assertThat(gateway().provider()).isEqualTo("binance-futures");
    }

    @Test
    void the_spot_factory_still_names_the_provider_binance() {
        var spot = BinanceRateGateway.create(
                baseUrl(), Duration.ofSeconds(1), Duration.ofSeconds(2), ObservationRegistry.NOOP);

        assertThat(spot.provider()).isEqualTo("binance");
    }

    // Criterion 4
    @Test
    void price_and_provider_time_are_parsed_and_the_time_becomes_the_quote_time() {
        handler = ex -> respond(ex, 200, body(symbolOf(ex), "3843.85", 1794737730000L));

        var quotes = gateway().fetch(List.of(xau));

        assertThat(quotes).containsOnlyKeys("XAUUSDT");
        assertThat(quotes.get("XAUUSDT").price()).isEqualTo(new BigDecimal("3843.85"));
        assertThat(quotes.get("XAUUSDT").quoteTime()).isEqualTo(Instant.parse("2026-11-15T10:15:30Z"));
    }

    @Test
    void price_keeps_full_precision_for_the_service_to_round() {
        handler = ex -> respond(ex, 200, body(symbolOf(ex), "161.33000", 1794737730000L));

        var quotes = gateway().fetch(List.of(nvda));

        assertThat(quotes.get("NVDAUSDT").price()).isEqualTo(new BigDecimal("161.33000"));
    }

    @Test
    void a_quote_time_in_the_future_is_kept_as_is() {
        long future = 4102444800000L; // 2100-01-01T00:00:00Z
        handler = ex -> respond(ex, 200, body(symbolOf(ex), "1", future));

        var quotes = gateway().fetch(List.of(xau));

        assertThat(quotes.get("XAUUSDT").quoteTime()).isEqualTo(Instant.ofEpochMilli(future));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5", "-1794737730000", "1794737730000.5", "\"1794737730000\"", "null", "true"})
    void a_zero_negative_non_integral_or_non_numeric_time_gives_no_quote_time_but_keeps_the_price(String rawTime) {
        handler = ex -> respond(ex, 200, bodyWithRawTime(symbolOf(ex), "12.5", rawTime));

        var quotes = gateway().fetch(List.of(xau));

        assertThat(quotes).containsOnlyKeys("XAUUSDT");
        assertThat(quotes.get("XAUUSDT").price()).isEqualTo(new BigDecimal("12.5"));
        assertThat(quotes.get("XAUUSDT").quoteTime()).isNull();
    }

    @Test
    void a_missing_time_gives_no_quote_time_but_keeps_the_price() {
        handler = ex -> respond(ex, 200, "{\"symbol\":\"" + symbolOf(ex) + "\",\"price\":\"12.5\"}");

        var quotes = gateway().fetch(List.of(xau));

        assertThat(quotes.get("XAUUSDT").price()).isEqualTo(new BigDecimal("12.5"));
        assertThat(quotes.get("XAUUSDT").quoteTime()).isNull();
    }

    @Test
    void one_sequential_request_per_asset_to_the_futures_ticker_endpoint_without_credentials() {
        handler = ex -> respond(ex, 200, body(symbolOf(ex), "1", 1794737730000L));

        var quotes = gateway().fetch(List.of(xau, xag, nvda, spy));

        assertThat(quotes).containsOnlyKeys("XAUUSDT", "XAGUSDT", "NVDAUSDT", "SPYUSDT");
        assertThat(requests)
                .containsExactly(
                        "/fapi/v1/ticker/price?symbol=XAUUSDT",
                        "/fapi/v1/ticker/price?symbol=XAGUSDT",
                        "/fapi/v1/ticker/price?symbol=NVDAUSDT",
                        "/fapi/v1/ticker/price?symbol=SPYUSDT");
        assertThat(requestHeaders).allSatisfy(h -> {
            var names = h.keySet().stream().map(String::toLowerCase).toList();
            assertThat(names).doesNotContain("authorization", "x-mbx-apikey");
        });
    }

    @Test
    void an_array_response_is_not_taken_as_the_symbols_price() {
        handler = ex -> {
            if (symbolOf(ex).equals("XAGUSDT")) {
                respond(ex, 200, "[" + body("XAUUSDT", "3400.5", 1794737730000L) + "]");
            } else {
                respond(ex, 200, body(symbolOf(ex), "5", 1794737730000L));
            }
        };

        assertThat(gateway().fetch(List.of(xau, xag))).containsOnlyKeys("XAUUSDT");
    }

    // Criterion 7
    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-3843.85", "not-a-number-xyz", "1E+999999999", ""})
    void a_non_positive_or_unparsable_price_is_dropped_and_the_others_are_returned(String badPrice) {
        handler = ex -> {
            if (symbolOf(ex).equals("XAUUSDT")) {
                respond(ex, 200, body("XAUUSDT", badPrice, 1794737730000L));
            } else {
                respond(ex, 200, body(symbolOf(ex), "5", 1794737730000L));
            }
        };

        try (var logs = LogCapture.start()) {
            var quotes = gateway().fetch(List.of(xau, xag));

            assertThat(quotes).containsOnlyKeys("XAGUSDT");
            assertThat(logs.text()).doesNotContain("not-a-number-xyz").doesNotContain("3843.85");
            assertNoLeak(logs.text());
        }
    }

    @Test
    void a_missing_price_field_is_dropped() {
        handler = ex -> {
            if (symbolOf(ex).equals("XAUUSDT")) {
                respond(ex, 200, "{\"symbol\":\"XAUUSDT\",\"time\":1794737730000}");
            } else {
                respond(ex, 200, body(symbolOf(ex), "5", 1794737730000L));
            }
        };

        assertThat(gateway().fetch(List.of(xau, xag))).containsOnlyKeys("XAGUSDT");
    }

    @Test
    void every_price_unusable_means_all_failed_and_throws() {
        handler = ex -> respond(ex, 200, body(symbolOf(ex), "-1", 1794737730000L));

        assertThatThrownBy(() -> gateway().fetch(List.of(xau, xag))).isInstanceOf(RateProviderException.class);
    }

    // Criterion 8
    @Test
    void a_429_ends_the_futures_poll_and_keeps_the_rates_already_fetched() {
        handler = ex -> {
            if (symbolOf(ex).equals("XAGUSDT")) {
                respond(ex, 429, "{\"msg\":\"" + BODY_TEXT + "\"}");
            } else {
                respond(ex, 200, body(symbolOf(ex), "7", 1794737730000L));
            }
        };

        var quotes = gateway().fetch(List.of(xau, xag, nvda, spy));

        assertThat(quotes).containsOnlyKeys("XAUUSDT");
        assertThat(requests).hasSize(2);
    }

    @Test
    void a_ban_418_on_the_first_symbol_sends_no_further_request_and_throws() {
        handler = ex -> respond(ex, 418, "{\"msg\":\"" + BODY_TEXT + "\"}");

        try (var logs = LogCapture.start()) {
            assertThatThrownBy(() -> gateway().fetch(List.of(xau, xag, nvda)))
                    .isInstanceOf(RateProviderException.class);

            assertNoLeak(logs.text());
            assertThat(logs.warnText()).contains("binance-futures").contains("418");
        }
        assertThat(requests).hasSize(1);
    }

    @Test
    void the_next_fetch_after_a_429_tries_again() {
        var gateway = gateway();
        handler = ex -> respond(ex, 429, "{}");
        assertThatThrownBy(() -> gateway.fetch(List.of(xau))).isInstanceOf(RateProviderException.class);
        handler = ex -> respond(ex, 200, body(symbolOf(ex), "3843.85", 1794737730000L));

        var quotes = gateway.fetch(List.of(xau));

        assertThat(quotes).containsOnlyKeys("XAUUSDT");
        assertThat(requests).hasSize(2);
    }

    // Criterion 9
    @Test
    void a_symbol_rejected_with_400_is_omitted_the_others_are_returned_and_the_body_is_not_logged() {
        handler = ex -> {
            if (symbolOf(ex).equals("XAGUSDT")) {
                respond(ex, 400, "{\"code\":-1121,\"msg\":\"" + BODY_TEXT + "\"}");
            } else {
                respond(ex, 200, body(symbolOf(ex), "7", 1794737730000L));
            }
        };

        try (var logs = LogCapture.start()) {
            var quotes = gateway().fetch(List.of(xau, xag, nvda));

            assertThat(quotes).containsOnlyKeys("XAUUSDT", "NVDAUSDT");
            assertNoLeak(logs.text());
            assertThat(logs.warnText()).contains("binance-futures").contains("400");
        }
    }

    @Test
    void every_symbol_failing_with_5xx_throws_a_provider_exception_and_logs_status_only() {
        handler = ex -> respond(ex, 503, "{\"msg\":\"" + BODY_TEXT + "\"}");

        try (var logs = LogCapture.start()) {
            assertThatThrownBy(() -> gateway().fetch(List.of(xau, xag)))
                    .isInstanceOf(RateProviderException.class)
                    .message()
                    .doesNotContain("XAUUSDT")
                    .doesNotContain("symbol=")
                    .doesNotContain(BODY_TEXT);

            assertNoLeak(logs.text());
            assertThat(logs.warnText()).contains("binance-futures").contains("503");
        }
    }

    @Test
    void region_blocked_451_is_an_ordinary_provider_failure() {
        handler = ex -> respond(ex, 451, "{\"msg\":\"" + BODY_TEXT + "\"}");

        try (var logs = LogCapture.start()) {
            assertThatThrownBy(() -> gateway().fetch(List.of(xau, xag))).isInstanceOf(RateProviderException.class);

            assertNoLeak(logs.text());
            assertThat(logs.warnText()).contains("binance-futures").contains("451");
        }
    }

    @Test
    void a_response_slower_than_the_read_timeout_fails_and_logs_only_the_exception_class() {
        handler = ex -> {
            try {
                releaseSlow.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(ex, 200, body(symbolOf(ex), "1", 1794737730000L));
        };
        var gateway = gateway(ObservationRegistry.NOOP, Duration.ofSeconds(1), Duration.ofMillis(300));

        try (var logs = LogCapture.start()) {
            assertThatThrownBy(() -> gateway.fetch(List.of(xau))).isInstanceOf(RateProviderException.class);

            assertNoLeak(logs.text());
            assertThat(logs.warnText()).contains("binance-futures").contains("Exception");
        }
    }

    @Test
    void an_unreachable_provider_throws_a_provider_exception() {
        server.stop(0);

        assertThatThrownBy(() -> gateway().fetch(List.of(xau))).isInstanceOf(RateProviderException.class);
    }

    // Criterion 10
    @Test
    void each_call_is_one_observation_tagged_with_futures_provider_template_and_outcome_and_no_symbol() {
        handler = ex -> respond(ex, 200, body(symbolOf(ex), "1", 1794737730000L));
        var recorded = new Recorded();

        gateway(registry(recorded), Duration.ofSeconds(1), Duration.ofSeconds(2))
                .fetch(List.of(xau, xag));

        assertThat(recorded.names).containsExactly("market.rates.provider.request", "market.rates.provider.request");
        assertThat(recorded.errors).isEmpty();
        assertThat(recorded.keyValues).hasSize(2).allSatisfy(kv -> {
            assertThat(kv.values())
                    .noneMatch(v -> v.contains("XAUUSDT")
                            || v.contains("XAGUSDT")
                            || v.contains("xau-usdt")
                            || v.contains("?"));
            assertThat(kv)
                    .containsEntry("provider", "binance-futures")
                    .containsEntry("uri", "/fapi/v1/ticker/price")
                    .containsEntry("outcome", "SUCCESS");
        });
    }

    // Criterion 11
    @Test
    void errors_reach_observation_handlers_without_url_symbol_or_body() {
        var recorded = new Recorded();
        var failing = gateway(registry(recorded), Duration.ofSeconds(1), Duration.ofSeconds(2));
        handler = ex -> respond(ex, 400, "{\"code\":-1121,\"msg\":\"" + BODY_TEXT + "\"}");
        assertThatThrownBy(() -> failing.fetch(List.of(xau))).isInstanceOf(RateProviderException.class);
        server.stop(0);
        assertThatThrownBy(() -> failing.fetch(List.of(xau))).isInstanceOf(RateProviderException.class);

        assertThat(recorded.errors).hasSize(2).allSatisfy(e -> {
            for (Throwable t = e; t != null; t = t.getCause()) {
                assertThat(String.valueOf(t.getMessage()))
                        .doesNotContain("XAUUSDT")
                        .doesNotContain("symbol=")
                        .doesNotContain("/fapi")
                        .doesNotContain("127.0.0.1")
                        .doesNotContain(BODY_TEXT);
            }
        });
        assertThat(recorded.keyValues).extracting(kv -> kv.get("outcome")).containsExactly("HTTP_ERROR", "IO_ERROR");
        assertThat(recorded.keyValues)
                .allSatisfy(kv -> assertThat(kv)
                        .containsEntry("provider", "binance-futures")
                        .doesNotContainKey("http.url"));
    }

    @Test
    void the_http_client_records_no_observation_of_its_own() {
        handler = ex -> respond(ex, 200, body(symbolOf(ex), "1", 1794737730000L));
        var recorded = new Recorded();

        gateway(registry(recorded), Duration.ofSeconds(1), Duration.ofSeconds(2))
                .fetch(List.of(xau));

        assertThat(recorded.names).containsExactly("market.rates.provider.request");
    }

    private static void assertNoLeak(String logs) {
        assertThat(logs)
                .doesNotContain(BODY_TEXT)
                .doesNotContain("-1121")
                .doesNotContain("symbol=")
                .doesNotContain("XAUUSDT")
                .doesNotContain("XAGUSDT")
                .doesNotContain("/fapi")
                .doesNotContain("127.0.0.1");
    }
}
