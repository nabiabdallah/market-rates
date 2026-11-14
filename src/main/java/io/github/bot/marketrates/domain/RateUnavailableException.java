package io.github.bot.marketrates.domain;

/** The asset is configured but no rate was fetched yet. The web adapter maps it to 503. */
public class RateUnavailableException extends DomainException {

    public RateUnavailableException() {
        super("Rate not available yet");
    }
}
