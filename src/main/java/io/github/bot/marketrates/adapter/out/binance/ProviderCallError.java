package io.github.bot.marketrates.adapter.out.binance;

/**
 * What a failed provider call records on its observation, and so on exported spans: the HTTP status or the
 * exception class, nothing else. No cause and no stack trace, because the original exception's message holds
 * the URL with the symbol, and for an error status the response body.
 */
final class ProviderCallError extends RuntimeException {

    ProviderCallError(String what) {
        super(what, null, false, false);
    }
}
