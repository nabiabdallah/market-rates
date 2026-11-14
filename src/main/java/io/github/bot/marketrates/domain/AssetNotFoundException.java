package io.github.bot.marketrates.domain;

/** The requested asset is not configured. The message deliberately omits the requested id. */
public class AssetNotFoundException extends NotFoundException {

    public AssetNotFoundException() {
        super("Asset not found");
    }
}
