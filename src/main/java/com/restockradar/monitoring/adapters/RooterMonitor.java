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
public class RooterMonitor extends JsonLdMonitor {

    public static final String STORE_CODE = "ROOTER";

    public RooterMonitor(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public String storeCode() {
        return STORE_CODE;
    }

    @Override
    public Snapshot fallbackCheck(Document document, Connection.Response response, Target target) {
        if (isBlockedPage(document)) {
            return Snapshot.blocked("Rooter challenge page detected");
        }

        BigDecimal price = extractRooterPrice(document);

        // Check for Out of Stock indicators
        Elements outElements = document.select("span:matchesOwn((?i)out of stock|sold out), " +
                "button:matchesOwn((?i)out of stock|sold out), .out-of-stock");
        if (!outElements.isEmpty()) {
            return Snapshot.outOfStock(price, "Rooter: Found out of stock badge ('" + outElements.first().text().trim() + "')");
        }

        // Check for active Buy / Redeem / Claim button
        Elements actionButtons = document.select("button:matchesOwn((?i)buy now|redeem|claim|purchase), " +
                "a:matchesOwn((?i)buy now|redeem|claim|purchase)");
        for (Element btn : actionButtons) {
            boolean disabled = btn.hasAttr("disabled") || btn.classNames().stream().anyMatch(c -> c.equalsIgnoreCase("disabled"));
            if (!disabled) {
                return Snapshot.inStock(price, "Rooter: Active action button found ('" + btn.text().trim() + "')");
            }
        }

        String body = document.body() != null ? document.body().text().toLowerCase(Locale.ROOT) : "";
        if (body.contains("out of stock") || body.contains("sold out")) {
            return Snapshot.outOfStock(price, "Rooter: Page text indicates out of stock");
        }

        return Snapshot.unknown("Rooter: Could not determine stock status from static HTML");
    }

    private BigDecimal extractRooterPrice(Document document) {
        Elements priceElements = document.select(".price, .diamond-price, span:matchesOwn(₹\\s*\\d+)");
        for (Element el : priceElements) {
            BigDecimal price = parsePriceValue(el.text());
            if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                return price;
            }
        }
        return null;
    }
}
