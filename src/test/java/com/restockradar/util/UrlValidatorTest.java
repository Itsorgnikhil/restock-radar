package com.restockradar.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlValidatorTest {

    @ParameterizedTest
    @CsvSource({
            "GAMES_THE_SHOP, https://www.gamestheshop.com/product/f528de33-a760-4fea-9b04-3d150b24a116",
            "GAMES_THE_SHOP, https://gamestheshop.com/product/1000",
            "AMAZON_IN, https://www.amazon.in/dp/B08L5WHJ7Q",
            "FLIPKART, https://www.flipkart.com/sony-playstation-network-wallet-top-up/p/itm123",
            "CROMA, https://www.croma.com/sony-playstation-network-card/p/247185",
            "ROOTER, https://rooter.gg/shop/diamonds",
            "STANSHOP, https://stanshop.in/creators/item",
            "BLINKIT, https://blinkit.com/prn/gift-card/prid/123"
    })
    @DisplayName("Valid store URLs pass validation")
    void validStoreUrlsPass(String storeCode, String url) {
        assertThatCode(() -> UrlValidator.validate(storeCode, url))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("HTTP (non-HTTPS) URLs are strictly rejected")
    void nonHttpsIsRejected() {
        assertThatThrownBy(() -> UrlValidator.validate("GAMES_THE_SHOP", "http://www.gamestheshop.com/product/1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only HTTPS URLs are allowed");
    }

    @Test
    @DisplayName("Domain mismatch between URL and store code is rejected")
    void domainMismatchRejected() {
        assertThatThrownBy(() -> UrlValidator.validate("GAMES_THE_SHOP", "https://www.amazon.in/product/1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not permitted for store");
    }

    @Test
    @DisplayName("Unsupported store code is rejected")
    void unsupportedStoreCodeRejected() {
        assertThatThrownBy(() -> UrlValidator.validate("EBAY", "https://www.ebay.com/itm/123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported store code");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://localhost/foo",
            "https://sub.localhost/foo",
            "https://internal.service.local/foo",
            "https://myhost.internal/secret"
    })
    @DisplayName("Local and private hostnames are rejected")
    void localHostnamesRejected(String url) {
        assertThatThrownBy(() -> UrlValidator.validate("GAMES_THE_SHOP", url))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://127.0.0.1/admin",
            "https://192.168.1.1/setup",
            "https://10.0.0.1/data",
            "https://169.254.169.254/latest/meta-data",
            "https://[::1]/secret"
    })
    @DisplayName("Direct IP addresses (SSRF targets) are rejected")
    void directIpAddressesRejected(String url) {
        assertThatThrownBy(() -> UrlValidator.validate("GAMES_THE_SHOP", url))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not-a-valid-uri::"})
    @DisplayName("Blank or malformed URLs are rejected")
    void malformedUrlsRejected(String url) {
        assertThatThrownBy(() -> UrlValidator.validate("GAMES_THE_SHOP", url))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
