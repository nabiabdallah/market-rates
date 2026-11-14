package io.github.bot.marketrates.application.port.in;

/** Refreshes the rates of every asset served by one provider. */
public interface PollRatesUseCase {

    /** Never throws because of a provider failure; the previous rates are kept. */
    void poll(String provider);
}
