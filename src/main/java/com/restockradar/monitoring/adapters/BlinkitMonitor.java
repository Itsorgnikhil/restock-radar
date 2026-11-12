package com.restockradar.monitoring.adapters;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.restockradar.monitoring.JsonLdMonitor;
import com.restockradar.monitoring.Snapshot;
import com.restockradar.target.Target;
import org.jsoup.Connection;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;

@Component
public class BlinkitMonitor extends JsonLdMonitor {

    public static final String STORE_CODE = "BLINKIT";

    public BlinkitMonitor(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public String storeCode() {
        return STORE_CODE;
    }

    @Override
    public Snapshot fallbackCheck(Document document, Connection.Response response, Target target) {
        if (isBlockedPage(document)) {
            return Snapshot.blocked("Blinkit challenge page detected");
        }

        BigDecimal price = extractBlinkitPrice(document);

        // Check for Out of Stock
        Elements outOfStock = document.select("div:matchesOwn((?i)out of stock), " +
                "button:matchesOwn((?i)out of stock), span:matchesOwn((?i)out of stock), .out-of-stock-banner");
        if (!outOfStock.isEmpty()) {
            return Snapshot.outOfStock(price, "Blinkit: Out of stock indicator ('" + outOfStock.first().text().trim() + "')");
        }

        // Check for active ADD button
        Elements addButtons = document.select("button:matchesOwn((?i)^add$), div:matchesOwn((?i)^add$), " +
                "button:matchesOwn((?i)add to cart)");
        for (Element btn : addButtons) {
            boolean disabled = btn.hasAttr("disabled") || btn.classNames().stream().anyMatch(c -> c.equalsIgnoreCase("disabled"));
            if (!disabled) {
                return Snapshot.inStock(price, "Blinkit: Active Add button found ('" + btn.text().trim() + "')");
            }
        }

        String body = document.body() != null ? document.body().text().toLowerCase(Locale.ROOT) : "";
        if (body.contains("out of stock")) {
            return Snapshot.outOfStock(price, "Blinkit: Page text indicates out of stock");
        }

        return Snapshot.unknown("Blinkit: Could not determine stock status from static HTML");
    }

    private BigDecimal extractBlinkitPrice(Document document) {
        Elements priceElements = document.select("div:matchesOwn(₹\\s*\\d+), span:matchesOwn(₹\\s*\\d+)");
        for (Element el : priceElements) {
            BigDecimal price = parsePriceValue(el.text());
            if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                return price;
            }
        }
        return null;
    }
}
