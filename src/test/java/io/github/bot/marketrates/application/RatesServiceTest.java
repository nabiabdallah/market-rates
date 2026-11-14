package io.github.bot.marketrates.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bot.marketrates.application.port.in.AssetRate;
import io.github.bot.marketrates.application.port.out.ProviderQuote;
import io.github.bot.marketrates.application.port.out.RateGateway;
import io.github.bot.marketrates.application.port.out.RateProviderException;
import io.github.bot.marketrates.domain.Asset;
import io.github.bot.marketrates.domain.AssetKind;
import io.github.bot.marketrates.domain.AssetNotFoundException;
import io.github.bot.marketrates.domain.RateUnavailableException;
import io.github.bot.marketrates.testsupport.LogCapture;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Unit tests for the rates use cases; no Spring, in-memory fake gateways. */
class RatesServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-14T10:00:00Z");
    private static final Duration STALE_AFTER = Duration.ofSeconds(30);

    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static final class FakeGateway implements RateGateway {
        final String provider;
        final List<List<Asset>> calls = new ArrayList<>();
        volatile Function<List<Asset>, Map<String, ProviderQuote>> behaviour = a -> Map.of();

        FakeGateway(String provider) {
            this.provider = provider;
        }

        @Override
        public String provider() {
            return provider;
        }

        @Override
        public synchronized Map<String, ProviderQuote> fetch(List<Asset> assets) {
            calls.add(List.copyOf(assets));
            return behaviour.apply(assets);
        }

        FakeGateway returns(Map<String, ProviderQuote> quotes) {
            behaviour = a -> quotes;
            return this;
        }

        FakeGateway throwing(RuntimeException e) {
            behaviour = a -> {
                throw e;
            };
            return this;
        }
    }

    private static Asset asset(String id, String symbol, String provider) {
        return new Asset(id, id.toUpperCase(), AssetKind.CRYPTO, provider, symbol, "USDT", 8, RoundingMode.HALF_EVEN);
    }

    private static ProviderQuote quote(String price) {
        return new ProviderQuote(new BigDecimal(price), null);
    }

    private final MutableClock clock = new MutableClock(T0);
    private final Asset btc = asset("btc-usdt", "BTCUSDT", "binance");
    private final Asset eth = asset("eth-usdt", "ETHUSDT", "binance");
    private final Asset qnt = asset("qnt-usdt", "QNTUSDT", "binance");
    private final FakeGateway binance = new FakeGateway("binance");

    private RatesService service(List<Asset> assets, RateGateway... gateways) {
        return new RatesService(assets, List.of(gateways), clock, STALE_AFTER);
    }

    private RatesService binanceService() {
        return service(List.of(btc, eth, qnt), binance);
    }

    // Criterion 3
    @Test
    void list_returns_one_fresh_entry_per_asset_after_a_poll() {
        binance.returns(Map.of("BTCUSDT", quote("96250.12"), "ETHUSDT", quote("3400.5"), "QNTUSDT", quote("101.25")));
        var service = binanceService();

        service.poll("binance");
        var rates = service.list();

        assertThat(rates).hasSize(3);
        assertThat(rates).allSatisfy(r -> {
            assertThat(r.rate()).isNotNull();
            assertThat(r.asOf()).isEqualTo(T0);
            assertThat(r.stale()).isFalse();
            assertThat(r.asset().quoteCurrency()).isEqualTo("USDT");
        });
    }

    @Test
    void list_keeps_the_configured_order() {
        binance.returns(Map.of("BTCUSDT", quote("1"), "ETHUSDT", quote("2"), "QNTUSDT", quote("3")));
        var service = service(List.of(qnt, btc, eth), binance);

        service.poll("binance");

        assertThat(service.list()).extracting(r -> r.asset().id()).containsExactly("qnt-usdt", "btc-usdt", "eth-usdt");
    }

    // Criterion 4
    @Test
    void get_returns_the_entry_of_the_asset() {
        binance.returns(Map.of("BTCUSDT", quote("96250.41")));
        var service = binanceService();
        service.poll("binance");

        AssetRate rate = service.get("btc-usdt");

        assertThat(rate.asset().id()).isEqualTo("btc-usdt");
        assertThat(rate.rate().value()).isEqualByComparingTo("96250.41");
        assertThat(rate.stale()).isFalse();
    }

    // Criterion 5
    @Test
    void get_unknown_asset_fails_with_not_found_that_does_not_echo_the_id() {
        var service = binanceService();

        assertThatThrownBy(() -> service.get("doge-usdt-secret"))
                .isInstanceOf(AssetNotFoundException.class)
                .hasMessage("Asset not found")
                .message()
                .doesNotContain("doge");
    }

    // Criterion 6
    @Test
    void provider_price_is_rounded_to_the_asset_scale_and_mode() {
        binance.returns(Map.of("BTCUSDT", quote("96250.123456789")));
        var service = binanceService();

        service.poll("binance");

        var value = service.get("btc-usdt").rate().value();
        assertThat(value).isEqualByComparingTo("96250.12345679");
        assertThat(value.scale()).isEqualTo(8);
    }

    // asOf
    @Test
    void as_of_is_the_fetch_time_when_the_provider_gives_no_quote_time() {
        binance.returns(Map.of("BTCUSDT", quote("1")));
        var service = binanceService();
        clock.advance(Duration.ofSeconds(5));

        service.poll("binance");

        assertThat(service.get("btc-usdt").asOf()).isEqualTo(T0.plusSeconds(5));
    }

    @Test
    void as_of_is_the_provider_quote_time_when_supplied() {
        var quoteTime = Instant.parse("2026-11-14T09:59:58Z");
        binance.returns(Map.of("BTCUSDT", new ProviderQuote(new BigDecimal("1"), quoteTime)));
        var service = binanceService();

        service.poll("binance");

        assertThat(service.get("btc-usdt").asOf()).isEqualTo(quoteTime);
    }

    // Criterion 7
    @Test
    void non_positive_price_is_discarded_and_the_previous_rate_is_kept() {
        var service = binanceService();
        binance.returns(Map.of("BTCUSDT", quote("100.5")));
        service.poll("binance");
        clock.advance(Duration.ofSeconds(10));

        for (String bad : List.of("0", "-12345.678", "0.000000001")) {
            binance.returns(Map.of("BTCUSDT", quote(bad)));
            service.poll("binance");

            var rate = service.get("btc-usdt");
            assertThat(rate.rate().value()).isEqualByComparingTo("100.5");
            assertThat(rate.asOf()).isEqualTo(T0);
        }
    }

    @Test
    void a_symbol_missing_from_the_response_keeps_the_previous_rate() {
        var service = binanceService();
        binance.returns(Map.of("BTCUSDT", quote("100.5"), "ETHUSDT", quote("7")));
        service.poll("binance");
        clock.advance(Duration.ofSeconds(10));
        binance.returns(Map.of("ETHUSDT", quote("8")));

        service.poll("binance");

        assertThat(service.get("btc-usdt").rate().value()).isEqualByComparingTo("100.5");
        assertThat(service.get("btc-usdt").asOf()).isEqualTo(T0);
        assertThat(service.get("eth-usdt").rate().value()).isEqualByComparingTo("8");
    }

    @Test
    void a_discarded_price_is_logged_with_provider_and_asset_id_but_not_the_value() {
        binance.returns(Map.of("BTCUSDT", quote("-98765.4321")));
        var service = binanceService();

        try (var logs = LogCapture.start()) {
            service.poll("binance");

            assertThat(logs.warnText()).contains("binance").contains("btc-usdt");
            assertThat(logs.text()).doesNotContain("98765").doesNotContain("BTCUSDT");
        }
    }

    // Criterion 8
    @Test
    void rate_older_than_stale_after_is_served_stale_with_its_original_as_of() {
        binance.returns(Map.of("BTCUSDT", quote("100.5")));
        var service = binanceService();
        service.poll("binance");

        clock.advance(STALE_AFTER.plusSeconds(1));
        var rate = service.get("btc-usdt");

        assertThat(rate.stale()).isTrue();
        assertThat(rate.asOf()).isEqualTo(T0);
        assertThat(rate.rate().value()).isEqualByComparingTo("100.5");
    }

    @Test
    void rate_exactly_stale_after_old_is_not_yet_stale() {
        binance.returns(Map.of("BTCUSDT", quote("100.5")));
        var service = binanceService();
        service.poll("binance");

        clock.advance(STALE_AFTER);

        assertThat(service.get("btc-usdt").stale()).isFalse();
    }

    @Test
    void staleness_is_measured_from_the_fetch_time_not_the_provider_quote_time() {
        binance.returns(Map.of("BTCUSDT", new ProviderQuote(new BigDecimal("1"), T0.minusSeconds(3600))));
        var service = binanceService();
        service.poll("binance");

        assertThat(service.get("btc-usdt").stale()).isFalse();
        assertThat(service.list().get(0).stale()).isFalse();
    }

    // Criterion 9
    @Test
    void provider_failure_keeps_last_rates_and_logs_provider_and_exception_class_only() {
        var service = binanceService();
        binance.returns(Map.of("BTCUSDT", quote("100.5")));
        service.poll("binance");
        clock.advance(Duration.ofSeconds(10));
        binance.throwing(new RateProviderException("HTTP 429 secret-detail"));

        try (var logs = LogCapture.start()) {
            assertThatCode(() -> service.poll("binance")).doesNotThrowAnyException();

            assertThat(logs.warnText()).contains("binance").contains("RateProviderException");
            assertThat(logs.text()).doesNotContain("secret-detail");
        }
        assertThat(service.get("btc-usdt").rate().value()).isEqualByComparingTo("100.5");
        assertThat(service.get("btc-usdt").asOf()).isEqualTo(T0);
    }

    @Test
    void unexpected_runtime_exception_from_a_gateway_is_caught_inside_poll() {
        var service = binanceService();
        binance.throwing(new IllegalStateException("secret-detail"));

        try (var logs = LogCapture.start()) {
            assertThatCode(() -> service.poll("binance")).doesNotThrowAnyException();

            assertThat(logs.warnText()).contains("binance").contains("IllegalStateException");
            assertThat(logs.text()).doesNotContain("secret-detail");
        }
    }

    @Test
    void a_failing_provider_does_not_affect_another_providers_assets() {
        var other = new FakeGateway("other");
        var otherAsset = asset("gold-usd", "XAU", "other");
        var service = service(List.of(btc, otherAsset), binance, other);
        binance.throwing(new RateProviderException("down"));
        other.returns(Map.of("XAU", quote("2000.5")));

        service.poll("binance");
        service.poll("other");

        assertThat(service.get("gold-usd").rate().value()).isEqualByComparingTo("2000.5");
        assertThat(service.list().get(0).rate()).isNull();
    }

    @Test
    void polling_one_provider_only_hands_it_its_own_assets() {
        var other = new FakeGateway("other");
        var otherAsset = asset("gold-usd", "XAU", "other");
        var service = service(List.of(btc, otherAsset, eth), binance, other);
        binance.returns(Map.of());

        service.poll("binance");

        assertThat(binance.calls).hasSize(1);
        assertThat(binance.calls.get(0)).containsExactly(btc, eth);
        assertThat(other.calls).isEmpty();
    }

    @Test
    void the_poll_after_a_failure_recovers_the_rates() {
        var service = binanceService();
        binance.throwing(new RateProviderException("down"));
        service.poll("binance");
        clock.advance(Duration.ofSeconds(10));
        binance.returns(Map.of("BTCUSDT", quote("55.5")));

        service.poll("binance");

        assertThat(service.get("btc-usdt").rate().value()).isEqualByComparingTo("55.5");
        assertThat(service.get("btc-usdt").asOf()).isEqualTo(T0.plusSeconds(10));
        assertThat(service.get("btc-usdt").stale()).isFalse();
    }

    @Test
    void polling_an_unknown_provider_is_a_no_op() {
        var service = binanceService();

        assertThatCode(() -> service.poll("nobody")).doesNotThrowAnyException();

        assertThat(binance.calls).isEmpty();
    }

    // Criterion 10
    @Test
    void never_fetched_asset_is_listed_without_rate_and_stale() {
        var service = binanceService();

        var rates = service.list();

        assertThat(rates).hasSize(3);
        assertThat(rates).allSatisfy(r -> {
            assertThat(r.rate()).isNull();
            assertThat(r.asOf()).isNull();
            assertThat(r.stale()).isTrue();
        });
    }

    @Test
    void symbol_the_provider_does_not_list_stays_never_fetched_while_others_are_served() {
        binance.returns(Map.of("BTCUSDT", quote("1"), "QNTUSDT", quote("3")));
        var service = binanceService();

        service.poll("binance");

        assertThat(service.list().get(1).rate()).isNull();
        assertThat(service.list().get(1).stale()).isTrue();
        assertThat(service.list().get(0).rate()).isNotNull();
        assertThat(service.list().get(2).rate()).isNotNull();
    }

    @Test
    void get_of_a_never_fetched_asset_fails_as_unavailable() {
        var service = binanceService();

        assertThatThrownBy(() -> service.get("btc-usdt"))
                .isInstanceOf(RateUnavailableException.class)
                .hasMessage("Rate not available yet");
    }

    // Criterion 11: readers race a writer that alternates two quotes. Every read must be one of the two whole
    // quotes; a rate stored apart from its asOf would show up as a mixed pair.
    @Test
    void concurrent_reads_never_see_a_rate_with_another_fetchs_as_of() throws Exception {
        var timeA = Instant.parse("2026-11-14T09:59:00Z");
        var timeB = Instant.parse("2026-11-14T10:00:07Z");
        var quoteA = Map.of("BTCUSDT", new ProviderQuote(new BigDecimal("100"), timeA));
        var quoteB = Map.of("BTCUSDT", new ProviderQuote(new BigDecimal("200"), timeB));
        var turn = new java.util.concurrent.atomic.AtomicInteger();
        binance.behaviour = assets -> turn.getAndIncrement() % 2 == 0 ? quoteA : quoteB;
        var service = service(List.of(btc), binance);
        service.poll("binance");

        var done = new java.util.concurrent.atomic.AtomicBoolean();
        var mixed = new AtomicReference<String>();
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        Runnable reader = () -> {
            while (!done.get() && mixed.get() == null) {
                for (AssetRate view :
                        List.of(service.get("btc-usdt"), service.list().get(0))) {
                    boolean a = view.rate().value().compareTo(new BigDecimal("100")) == 0
                            && view.asOf().equals(timeA);
                    boolean b = view.rate().value().compareTo(new BigDecimal("200")) == 0
                            && view.asOf().equals(timeB);
                    if (!a && !b) {
                        mixed.set(view.rate().value() + " at " + view.asOf());
                    }
                    reads.incrementAndGet();
                }
            }
        };
        var readers = List.of(new Thread(reader), new Thread(reader));
        readers.forEach(Thread::start);
        try {
            for (int i = 0; i < 20_000 && mixed.get() == null; i++) {
                service.poll("binance");
            }
        } finally {
            done.set(true);
            for (Thread t : readers) {
                t.join(10_000);
            }
        }

        assertThat(mixed.get()).isNull();
        assertThat(reads.get()).isPositive();
    }

    // Criterion 11
    @Test
    void a_read_during_a_poll_sees_the_complete_old_snapshot_then_the_complete_new_one() throws Exception {
        var quoteTimeOld = Instant.parse("2026-11-14T09:59:00Z");
        var quoteTimeNew = Instant.parse("2026-11-14T10:00:07Z");
        binance.returns(Map.of("BTCUSDT", new ProviderQuote(new BigDecimal("100"), quoteTimeOld)));
        var service = service(List.of(btc), binance);
        service.poll("binance");

        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        binance.behaviour = assets -> {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test safety timeout");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Map.of("BTCUSDT", new ProviderQuote(new BigDecimal("200"), quoteTimeNew));
        };
        var failure = new AtomicReference<Throwable>();
        var poller = new Thread(() -> {
            try {
                service.poll("binance");
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        poller.start();
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

            var during = service.get("btc-usdt");
            var duringList = service.list().get(0);

            assertThat(during.rate().value()).isEqualByComparingTo("100");
            assertThat(during.asOf()).isEqualTo(quoteTimeOld);
            assertThat(duringList.rate().value()).isEqualByComparingTo("100");
            assertThat(duringList.asOf()).isEqualTo(quoteTimeOld);
        } finally {
            release.countDown();
            poller.join(10_000);
        }

        assertThat(failure.get()).isNull();
        var after = service.get("btc-usdt");
        assertThat(after.rate().value()).isEqualByComparingTo("200");
        assertThat(after.asOf()).isEqualTo(quoteTimeNew);
    }

    @Test
    void service_does_not_mutate_the_configured_asset_list_it_was_given() {
        var assets = new ArrayList<>(List.of(btc, eth));
        var service = service(assets, binance);
        assets.clear();

        assertThat(service.list()).hasSize(2);
    }
}
