package io.github.bot.marketrates.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.bot.marketrates.application.port.in.PollRatesUseCase;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

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
}
