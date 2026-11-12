package com.restockradar.monitoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class JsonLdMonitorTest {

    private JsonLdMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new JsonLdMonitor(new ObjectMapper()) {
            @Override
            public String storeCode() {
                return "TEST_STORE";
            }
        };
    }

    private Document loadFixture(String fixtureName) throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/fixtures/" + fixtureName)) {
            assertThat(is).as("Fixture %s must exist in test resources", fixtureName).isNotNull();
            String html = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return Jsoup.parse(html);
        }
    }

    @Test
    @DisplayName("Parses standard schema.org Product with InStock Offer and price")
    void parsesInStockJsonLd() throws Exception {
        Document doc = loadFixture("product_jsonld_instock.html");
        Snapshot snapshot = monitor.parseJsonLd(doc);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.status()).isEqualTo(Availability.IN_STOCK);
        assertThat(snapshot.price()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(snapshot.evidence()).contains("InStock");
    }

    @Test
    @DisplayName("Parses schema.org Product with OutOfStock Offer")
    void parsesOutOfStockJsonLd() throws Exception {
        Document doc = loadFixture("product_jsonld_outofstock.html");
        Snapshot snapshot = monitor.parseJsonLd(doc);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.status()).isEqualTo(Availability.OUT_OF_STOCK);
        assertThat(snapshot.price()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(snapshot.evidence()).contains("OutOfStock");
    }

    @Test
    @DisplayName("Parses JSON-LD wrapped in @graph array")
    void parsesGraphJsonLd() throws Exception {
        Document doc = loadFixture("product_jsonld_graph.html");
        Snapshot snapshot = monitor.parseJsonLd(doc);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.status()).isEqualTo(Availability.IN_STOCK);
        assertThat(snapshot.price()).isEqualByComparingTo(new BigDecimal("499.00"));
    }

    @Test
    @DisplayName("Returns null when no JSON-LD script blocks exist")
    void returnsNullWhenNoJsonLdPresent() {
        Document doc = Jsoup.parse("<html><body><div>Just normal HTML</div></body></html>");
        Snapshot snapshot = monitor.parseJsonLd(doc);

        assertThat(snapshot).isNull();
    }

    @Test
    @DisplayName("Price parser extracts clean BigDecimal from various string formats")
    void testPriceParser() {
        assertThat(monitor.parsePriceValue("₹ 1,000.00")).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(monitor.parsePriceValue("INR 4,999")).isEqualByComparingTo(new BigDecimal("4999"));
        assertThat(monitor.parsePriceValue("500")).isEqualByComparingTo(new BigDecimal("500"));
        assertThat(monitor.parsePriceValue("invalid")).isNull();
        assertThat(monitor.parsePriceValue(null)).isNull();
    }
}
