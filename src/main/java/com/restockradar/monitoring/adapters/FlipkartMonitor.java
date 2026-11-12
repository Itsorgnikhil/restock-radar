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
public class FlipkartMonitor extends JsonLdMonitor {

    public static final String STORE_CODE = "FLIPKART";

    public FlipkartMonitor(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public String storeCode() {
        return STORE_CODE;
    }

    @Override
    public Snapshot fallbackCheck(Document document, Connection.Response response, Target target) {
        if (isBlockedPage(document)) {
            return Snapshot.blocked("Flipkart PerimeterX / bot challenge detected");
        }

        BigDecimal price = extractFlipkartPrice(document);

        // Check for Sold Out / Currently Unavailable indicator
        Elements soldOutElements = document.select("div:matchesOwn((?i)sold out|currently unavailable), " +
                "._16FRp0, ._1dqA6a");
        if (!soldOutElements.isEmpty()) {
            return Snapshot.outOfStock(price, "Flipkart: Found sold out indicator ('" + soldOutElements.first().text().trim() + "')");
        }

        // Check for Add to Cart or Buy Now button
        Elements actionButtons = document.select("button:matchesOwn((?i)buy now|add to cart), " +
                "a:matchesOwn((?i)buy now|add to cart), ._2KpZ6l._2U9uOA");

        for (Element btn : actionButtons) {
            boolean disabled = btn.hasAttr("disabled") || btn.classNames().stream().anyMatch(c -> c.equalsIgnoreCase("disabled"));
            if (!disabled) {
                return Snapshot.inStock(price, "Flipkart: Active action button found ('" + btn.text().trim() + "')");
            }
        }

        String bodyText = document.body() != null ? document.body().text().toLowerCase(Locale.ROOT) : "";
        if (bodyText.contains("sold out") || bodyText.contains("currently unavailable")) {
            return Snapshot.outOfStock(price, "Flipkart: Page text contains 'sold out'");
        }

        return Snapshot.unknown("Flipkart: Could not determine stock status from static HTML");
    }

    private BigDecimal extractFlipkartPrice(Document document) {
        Elements priceElements = document.select("div.Nx9bqj, div._30jeq3, div._16Jk6d, div:matchesOwn(₹\\s*\\d+)");
        for (Element el : priceElements) {
            BigDecimal price = parsePriceValue(el.text());
            if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                return price;
            }
        }
        return null;
    }
}
