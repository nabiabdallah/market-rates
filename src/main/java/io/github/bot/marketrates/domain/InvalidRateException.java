package io.github.bot.marketrates.domain;

/** A rate supplied by a provider is not usable. The message never carries the offending value. */
public class InvalidRateException extends DomainException {

    public InvalidRateException(String message) {
        super(message);
    }
}
