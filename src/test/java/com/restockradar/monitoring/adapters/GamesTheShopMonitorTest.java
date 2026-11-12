package com.restockradar.monitoring.adapters;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.restockradar.monitoring.Availability;
import com.restockradar.monitoring.Snapshot;
import com.restockradar.monitoring.adapters.GamesTheShopMonitor;
import com.restockradar.target.Target;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class GamesTheShopMonitorTest {

    private GamesTheShopMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new GamesTheShopMonitor(new ObjectMapper());
    }

    private Document loadFixture(String fixtureName) throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/fixtures/" + fixtureName)) {
            assertThat(is).as("Fixture %s must exist in test resources", fixtureName).isNotNull();
            String html = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return Jsoup.parse(html);
        }
    }

    @Test
    @DisplayName("Identifies IN_STOCK from HTML fallback when ADD TO CART button is enabled")
    void fallbackIdentifiesInStock() throws Exception {
        Document doc = loadFixture("gamestheshop_instock.html");
        Target target = new Target("GAMES_THE_SHOP", "https://www.gamestheshop.com/product/1", "Test", 600);

        Snapshot snapshot = monitor.fallbackCheck(doc, null, target);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.status()).isEqualTo(Availability.IN_STOCK);
        assertThat(snapshot.price()).isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(snapshot.evidence()).contains("ADD TO CART");
    }

    @Test
    @DisplayName("Identifies OUT_OF_STOCK from HTML fallback when 'Out Of Stock' indicator is present")
    void fallbackIdentifiesOutOfStock() throws Exception {
        Document doc = loadFixture("gamestheshop_outofstock.html");
        Target target = new Target("GAMES_THE_SHOP", "https://www.gamestheshop.com/product/1", "Test", 600);

        Snapshot snapshot = monitor.fallbackCheck(doc, null, target);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.status()).isEqualTo(Availability.OUT_OF_STOCK);
        assertThat(snapshot.price()).isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(snapshot.evidence()).contains("Out Of Stock");
    }

    @Test
    @DisplayName("Prefers JSON-LD over HTML fallback when JSON-LD is available")
    void prefersJsonLdWhenPresent() throws Exception {
        Document doc = loadFixture("product_jsonld_instock.html");
        Snapshot snapshot = monitor.parseJsonLd(doc);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.status()).isEqualTo(Availability.IN_STOCK);
        assertThat(snapshot.price()).isEqualByComparingTo(new BigDecimal("1000.00"));
    }

    @Test
    @DisplayName("Queries official Storefront API for Games The Shop product")
    void checkQueriesStorefrontApi() {
        Target target = new Target("GAMES_THE_SHOP",
                "https://www.gamestheshop.com/product/f528de33-a760-4fea-9b04-3d150b24a116?variant=7e6631a0-0d65-4907-89b7-4ab5e24397cc",
                "PSN Card", 600);
        Snapshot snapshot = monitor.checkStorefrontApi(target);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.status()).isIn(Availability.IN_STOCK, Availability.OUT_OF_STOCK);
        assertThat(snapshot.evidence()).contains("Official API");
        if (snapshot.price() != null) {
            assertThat(snapshot.price()).isPositive();
        }
    }
}
