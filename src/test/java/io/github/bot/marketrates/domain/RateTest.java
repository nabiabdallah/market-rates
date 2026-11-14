package io.github.bot.marketrates.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

/** Criteria 6 and 7 (domain part). */
class RateTest {

    @Test
    void rate_is_rounded_to_configured_scale_with_configured_mode() {
        var rate = Rate.of(new BigDecimal("96250.123456789"), 8, RoundingMode.HALF_EVEN);

        assertThat(rate.value()).isEqualByComparingTo("96250.12345679");
        assertThat(rate.value().scale()).isEqualTo(8);
    }

    @Test
    void rate_keeps_trailing_zeros_to_reach_the_scale() {
        var rate = Rate.of(new BigDecimal("96250.41"), 8, RoundingMode.HALF_EVEN);

        assertThat(rate.value().toPlainString()).isEqualTo("96250.41000000");
    }

    @Test
    void rounding_mode_is_honoured_half_even_versus_half_up() {
        assertThat(Rate.of(new BigDecimal("2.5"), 0, RoundingMode.HALF_EVEN).value())
                .isEqualByComparingTo("2");
        assertThat(Rate.of(new BigDecimal("2.5"), 0, RoundingMode.HALF_UP).value())
                .isEqualByComparingTo("3");
    }

    @Test
    void rate_rejects_null() {
        assertThatThrownBy(() -> Rate.of(null, 8, RoundingMode.HALF_EVEN)).isInstanceOf(InvalidRateException.class);
    }

    @Test
    void rate_rejects_zero() {
        assertThatThrownBy(() -> Rate.of(BigDecimal.ZERO, 8, RoundingMode.HALF_EVEN))
                .isInstanceOf(InvalidRateException.class);
    }

    @Test
    void rate_rejects_negative() {
        assertThatThrownBy(() -> Rate.of(new BigDecimal("-1.5"), 8, RoundingMode.HALF_EVEN))
                .isInstanceOf(InvalidRateException.class);
    }

    @Test
    void rate_rejects_a_value_that_rounds_to_zero() {
        assertThatThrownBy(() -> Rate.of(new BigDecimal("0.000000001"), 8, RoundingMode.HALF_EVEN))
                .isInstanceOf(InvalidRateException.class);
    }

    @Test
    void invalid_rate_message_does_not_contain_the_offending_value() {
        assertThatThrownBy(() -> Rate.of(new BigDecimal("-123456.789"), 8, RoundingMode.HALF_EVEN))
                .isInstanceOf(InvalidRateException.class)
                .message()
                .doesNotContain("123456");
    }

    @Test
    void not_found_and_unavailable_have_fixed_messages_and_the_right_kinds() {
        assertThat(new AssetNotFoundException())
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Asset not found");
        assertThat(new RateUnavailableException())
                .isInstanceOf(DomainException.class)
                .hasMessage("Rate not available yet");
        assertThat(new InvalidRateException("fixed"))
                .isInstanceOf(DomainException.class)
                .hasMessage("fixed");
    }
}
