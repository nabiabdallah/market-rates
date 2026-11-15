package io.github.bot.marketrates.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.bot.marketrates.application.port.in.PollRatesUseCase;
import io.github.bot.marketrates.application.port.out.ProviderQuote;
import io.github.bot.marketrates.application.port.out.RateGateway;
import io.github.bot.marketrates.domain.Asset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/** Criterion 9: a failing poll must not cancel the fixed-delay schedule. */
class RatePollingConfigTest {

    private static final class FlakyUseCase implements PollRatesUseCase {
        final List<String> polled = new ArrayList<>();
        int failuresLeft;

        FlakyUseCase(int failures) {
            this.failuresLeft = failures;
        }

        @Override
        public void poll(String provider) {
            polled.add(provider);
            if (failuresLeft-- > 0) {
                throw new IllegalStateException("boom");
            }
        }
    }

    @Test
    void task_delegates_to_the_use_case_with_its_provider() {
        var uc = new FlakyUseCase(0);

        RatePollingConfig.task(uc, "binance").run();

        assertThat(uc.polled).containsExactly("binance");
    }

    @Test
    void task_never_throws_even_when_the_use_case_does_and_can_run_again() {
        var uc = new FlakyUseCase(1);
        Runnable task = RatePollingConfig.task(uc, "binance");

        assertThatCode(task::run).doesNotThrowAnyException();
        assertThatCode(task::run).doesNotThrowAnyException();

        assertThat(uc.polled).containsExactly("binance", "binance");
    }

    private static RateGateway gateway(String provider) {
        return new RateGateway() {
            @Override
            public String provider() {
                return provider;
            }

            @Override
            public Map<String, ProviderQuote> fetch(List<Asset> assets) {
                return Map.of();
            }
        };
    }

    // Criterion 9 on the real scheduler: one fixed-delay task per provider at the configured interval, and a
    // poll that throws does not stop the ones after it.
    @Test
    void the_scheduler_polls_each_provider_at_the_interval_and_keeps_going_after_a_failure() throws Exception {
        var interval = Duration.ofMillis(50);
        var props = new org.springframework.boot.context.properties.bind.Binder(
                        new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
                                Map.of("market-rates.poll-interval", "50ms")))
                .bindOrCreate("market-rates", MarketRatesProperties.class);
        var polls = new CopyOnWriteArrayList<String>();
        var enough = new CountDownLatch(6);
        PollRatesUseCase useCase = provider -> {
            polls.add(provider);
            enough.countDown();
            if (polls.size() <= 2) {
                throw new IllegalStateException("boom");
            }
        };
        var config = new RatePollingConfig(useCase, List.of(gateway("binance"), gateway("other")), props);
        var registrar = new ScheduledTaskRegistrar();
        try {
            config.configureTasks(registrar);

            assertThat(registrar.getFixedDelayTaskList())
                    .hasSize(2)
                    .allSatisfy(task -> assertThat(task.getIntervalDuration()).isEqualTo(interval));

            registrar.afterPropertiesSet();
            assertThat(enough.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(polls).contains("binance", "other");
            assertThat(polls.stream().filter("binance"::equals).count()).isGreaterThanOrEqualTo(2);
        } finally {
            registrar.destroy();
            config.destroy();
        }
    }
}
