package io.github.bot.marketrates.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** A strictly positive market rate with an explicit scale. */
public final class Rate {

    private final BigDecimal value;

    private Rate(BigDecimal value) {
        this.value = value;
    }

    /**
     * Rounds {@code raw} to {@code scale} with {@code rounding}.
     *
     * @throws InvalidRateException if it is missing, or not positive once rounded
     */
    public static Rate of(BigDecimal raw, int scale, RoundingMode rounding) {
        if (raw == null) {
            throw new InvalidRateException("Rate is missing");
        }
        BigDecimal rounded;
        try {
            rounded = raw.setScale(scale, rounding);
        } catch (ArithmeticException e) {
            throw new InvalidRateException("Rate cannot be represented at the configured scale");
        }
        if (rounded.signum() <= 0) {
            throw new InvalidRateException("Rate is not a positive number");
        }
        return new Rate(rounded);
    }

    public BigDecimal value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Rate other && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "Rate[" + value.toPlainString() + "]";
    }
}
