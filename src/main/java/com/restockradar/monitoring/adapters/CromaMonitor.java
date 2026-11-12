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
public class CromaMonitor extends JsonLdMonitor {

    public static final String STORE_CODE = "CROMA";

    public CromaMonitor(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public String storeCode() {
        return STORE_CODE;
    }

    @Override
    public Snapshot fallbackCheck(Document document, Connection.Response response, Target target) {
        if (isBlockedPage(document)) {
            return Snapshot.blocked("Croma bot protection / challenge page detected");
        }

        BigDecimal price = extractCromaPrice(document);

        // Check for Out of Stock or Notify Me
        Elements outOfStock = document.select("span:matchesOwn((?i)out of stock|currently unavailable|sold out), " +
                "button:matchesOwn((?i)out of stock|notify me), .btn-out-of-stock, .pdp-out-of-stock");
        if (!outOfStock.isEmpty()) {
            return Snapshot.outOfStock(price, "Croma: Found out of stock marker ('" + outOfStock.first().text().trim() + "')");
        }

        // Check for Buy Now / Add to Cart
        Elements cartButtons = document.select("button:matchesOwn((?i)buy now|add to cart), .btn-buy-now, #pdp-add-to-cart");
        for (Element btn : cartButtons) {
            boolean disabled = btn.hasAttr("disabled") || btn.classNames().stream().anyMatch(c -> c.equalsIgnoreCase("disabled"));
            if (!disabled) {
                return Snapshot.inStock(price, "Croma: Active button found ('" + btn.text().trim() + "')");
            }
        }

        String bodyText = document.body() != null ? document.body().text().toLowerCase(Locale.ROOT) : "";
        if (bodyText.contains("out of stock") || bodyText.contains("currently unavailable")) {
            return Snapshot.outOfStock(price, "Croma: Page text contains out of stock");
        }

        return Snapshot.unknown("Croma: Could not determine stock status from static HTML");
    }

    private BigDecimal extractCromaPrice(Document document) {
        Elements priceElements = document.select(".amount, .pdp-price, .new-price, span[data-testid=\"new-price\"], span:matchesOwn(₹\\s*\\d+)");
        for (Element el : priceElements) {
            BigDecimal price = parsePriceValue(el.text());
            if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                return price;
            }
        }
        return null;
    }
}
