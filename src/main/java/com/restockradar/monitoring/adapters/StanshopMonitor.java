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
public class StanshopMonitor extends JsonLdMonitor {

    public static final String STORE_CODE = "STANSHOP";

    public StanshopMonitor(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public String storeCode() {
        return STORE_CODE;
    }

    @Override
    public Snapshot fallbackCheck(Document document, Connection.Response response, Target target) {
        if (isBlockedPage(document)) {
            return Snapshot.blocked("Stan Shop challenge page detected");
        }

        BigDecimal price = extractStanshopPrice(document);

        // Check for Sold Out
        Elements soldOut = document.select("button:matchesOwn((?i)sold out|out of stock), " +
                "span:matchesOwn((?i)sold out|out of stock), .sold-out, .badge-sold-out");
        if (!soldOut.isEmpty()) {
            return Snapshot.outOfStock(price, "Stan Shop: Sold out marker ('" + soldOut.first().text().trim() + "')");
        }

        // Check for Buy Now / Get It Now
        Elements buyButtons = document.select("button:matchesOwn((?i)buy now|get it now|unlock|add to cart), " +
                "a:matchesOwn((?i)buy now|get it now|unlock|add to cart)");
        for (Element btn : buyButtons) {
            boolean disabled = btn.hasAttr("disabled") || btn.classNames().stream().anyMatch(c -> c.equalsIgnoreCase("disabled"));
            if (!disabled) {
                return Snapshot.inStock(price, "Stan Shop: Active button found ('" + btn.text().trim() + "')");
            }
        }

        String body = document.body() != null ? document.body().text().toLowerCase(Locale.ROOT) : "";
        if (body.contains("sold out") || body.contains("out of stock")) {
            return Snapshot.outOfStock(price, "Stan Shop: Page text indicates sold out");
        }

        return Snapshot.unknown("Stan Shop: Could not determine stock status from static HTML");
    }

    private BigDecimal extractStanshopPrice(Document document) {
        Elements priceElements = document.select(".price, .product-price, .amount, span:matchesOwn(₹\\s*\\d+)");
        for (Element el : priceElements) {
            BigDecimal price = parsePriceValue(el.text());
            if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                return price;
            }
        }
        return null;
    }
}
