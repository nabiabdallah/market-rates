package io.github.bot.marketrates.adapter.out.binance;

/** The provider answered with an error status. Carries the status only: no URL and no response body. */
final class ProviderHttpException extends RuntimeException {

    private final int status;

    ProviderHttpException(int status) {
        super("Provider answered HTTP " + status);
        this.status = status;
    }

    int status() {
        return status;
    }
}
