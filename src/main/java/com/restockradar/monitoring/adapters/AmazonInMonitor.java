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
public class AmazonInMonitor extends JsonLdMonitor {

    public static final String STORE_CODE = "AMAZON_IN";

    public AmazonInMonitor(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public String storeCode() {
        return STORE_CODE;
    }

    @Override
    public Snapshot fallbackCheck(Document document, Connection.Response response, Target target) {
        // Amazon anti-bot challenge check
        if (isBlockedPage(document)) {
            return Snapshot.blocked("Amazon robot check / CAPTCHA challenge detected");
        }

        BigDecimal price = extractAmazonPrice(document);

        // Check availability container
        Element availabilityEl = document.selectFirst("#availability");
        String availText = availabilityEl != null ? availabilityEl.text().toLowerCase(Locale.ROOT) : "";

        if (availText.contains("currently unavailable") || availText.contains("out of stock")
                || document.selectFirst("#outOfStock") != null) {
            return Snapshot.outOfStock(price, "Amazon: Availability indicates Currently Unavailable / Out of Stock");
        }

        if (availText.contains("in stock") || availText.contains("only") && availText.contains("left in stock")) {
            return Snapshot.inStock(price, "Amazon: Availability element confirmed '" + availabilityEl.text().trim() + "'");
        }

        // Check Add to Cart / Buy Now buttons
        Element addToCart = document.selectFirst("#add-to-cart-button, #buy-now-button, input[name=\"submit.add-to-cart\"]");
        if (addToCart != null && !addToCart.hasAttr("disabled")) {
            return Snapshot.inStock(price, "Amazon: Active Add to Cart / Buy Now button found");
        }

        return Snapshot.unknown("Amazon: Could not conclusively verify stock status from static HTML");
    }

    private BigDecimal extractAmazonPrice(Document document) {
        Elements priceElements = document.select(
                ".apexPriceToPay .a-offscreen, #corePriceDisplay_desktop_feature_div .a-offscreen, " +
                "#priceblock_ourprice, #priceblock_dealprice, span.a-price span.a-offscreen"
        );
        for (Element el : priceElements) {
            BigDecimal price = parsePriceValue(el.text());
            if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                return price;
            }
        }
        return null;
    }
}
