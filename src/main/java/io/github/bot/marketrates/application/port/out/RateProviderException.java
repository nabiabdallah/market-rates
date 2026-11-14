package io.github.bot.marketrates.application.port.out;

/**
 * A provider could not be used at all. The message must not carry URLs, query strings or response bodies,
 * and no cause is chained (client exceptions embed both).
 */
public class RateProviderException extends RuntimeException {

    public RateProviderException(String message) {
        super(message);
    }
}
