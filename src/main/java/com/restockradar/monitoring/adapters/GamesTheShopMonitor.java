package com.restockradar.monitoring.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.restockradar.config.AppConfig;
import com.restockradar.monitoring.Availability;
import com.restockradar.monitoring.JsonLdMonitor;
import com.restockradar.monitoring.Snapshot;
import com.restockradar.target.Target;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class GamesTheShopMonitor extends JsonLdMonitor {

    private static final Logger log = LoggerFactory.getLogger(GamesTheShopMonitor.class);
    public static final String STORE_CODE = "GAMES_THE_SHOP";

    private static final Pattern PRODUCT_ID_PATTERN = Pattern.compile("/product/([a-zA-Z0-9\\-]+)");
    private static final Pattern VARIANT_ID_PATTERN = Pattern.compile("[?&]variant=([a-zA-Z0-9\\-]+)");
    private static final String API_BASE_URL = "https://green-api.gamestheshop.com/storefront/products/";

    public GamesTheShopMonitor(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public String storeCode() {
        return STORE_CODE;
    }

    @Override
    public Snapshot check(Target target) {
        // Source priority: 1. Official API, 2. Structured data (JSON-LD), 3. Jsoup static HTML
        Snapshot apiSnapshot = checkStorefrontApi(target);
        if (apiSnapshot != null) {
            return apiSnapshot;
        }

        return super.check(target);
    }

    /**
     * Queries Games The Shop's official public storefront API for real-time variant stock.
     */
    public Snapshot checkStorefrontApi(Target target) {
        String url = target.getUrl();
        if (url == null || url.isBlank()) {
            return null;
        }

        Matcher productMatcher = PRODUCT_ID_PATTERN.matcher(url);
        if (!productMatcher.find()) {
            return null;
        }

        String productId = productMatcher.group(1);
        String targetVariantId = null;
        Matcher variantMatcher = VARIANT_ID_PATTERN.matcher(url);
        if (variantMatcher.find()) {
            targetVariantId = variantMatcher.group(1);
        }

        String apiUrl = API_BASE_URL + productId + "/";

        try {
            Connection.Response response = Jsoup.connect(apiUrl)
                    .userAgent(AppConfig.USER_AGENT)
                    .timeout((int) AppConfig.TIMEOUT.toMillis())
                    .ignoreHttpErrors(true)
                    .ignoreContentType(true)
                    .header("Accept", "application/json")
                    .execute();

            int statusCode = response.statusCode();
            if (statusCode == 403 || statusCode == 429) {
                return Snapshot.blocked("GamesTheShop API HTTP " + statusCode);
            }
            if (statusCode != 200) {
                log.debug("GamesTheShop API returned HTTP {} for product {}", statusCode, productId);
                return null;
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isNull()) {
                return null;
            }

            // If a variant is specified, look up that exact variant
            JsonNode selectedVariant = null;
            JsonNode variants = data.path("variants");
            if (variants.isArray()) {
                for (JsonNode v : variants) {
                    if (targetVariantId != null && targetVariantId.equalsIgnoreCase(v.path("id").asText())) {
                        selectedVariant = v;
                        break;
                    }
                }
                if (selectedVariant == null && variants.size() > 0) {
                    selectedVariant = variants.get(0);
                }
            }

            String stockStatus;
            int availableStock = 0;
            BigDecimal price = null;

            if (selectedVariant != null) {
                stockStatus = selectedVariant.path("stock_status").asText("");
                availableStock = selectedVariant.path("inventory").path("available_stock").asInt(0);
                String priceStr = selectedVariant.path("sale_price").asText("");
                if (priceStr.isBlank()) {
                    priceStr = selectedVariant.path("regular_price").asText("");
                }
                price = parsePriceValue(priceStr);
            } else {
                stockStatus = data.path("stock_status").asText("");
                availableStock = data.path("total_inventory").asInt(0);
                String priceStr = data.path("sale_price").asText("");
                if (priceStr.isBlank()) {
                    priceStr = data.path("regular_price").asText("");
                }
                price = parsePriceValue(priceStr);
            }

            boolean inStock = availableStock > 0 || stockStatus.equalsIgnoreCase("In Stock");
            String evidence = String.format("Official API: %s (stock: %d)",
                    stockStatus.isBlank() ? (inStock ? "In Stock" : "Out of Stock") : stockStatus,
                    availableStock);

            if (inStock) {
                return Snapshot.inStock(price, evidence);
            } else {
                return Snapshot.outOfStock(price, evidence);
            }

        } catch (Exception e) {
            log.warn("Failed querying GamesTheShop storefront API at {}: {}", apiUrl, e.getMessage());
            return null; // Fallback to HTML scraping
        }
    }

    @Override
    public Snapshot fallbackCheck(Document document, Connection.Response response, Target target) {
        BigDecimal detectedPrice = extractHtmlPrice(document);

        // Check for explicit out-of-stock indicators
        Elements outOfStockElements = document.select("span:matchesOwn((?i)out of stock|sold out|notify me), " +
                "div:matchesOwn((?i)out of stock|sold out|notify me), " +
                "button:matchesOwn((?i)out of stock|sold out|notify me), " +
                ".out-of-stock, .sold-out, .not-available");

        if (!outOfStockElements.isEmpty()) {
            return Snapshot.outOfStock(detectedPrice, "HTML fallback: found out-of-stock element ('" + outOfStockElements.first().text() + "')");
        }

        // Check for enabled Add To Cart or Buy Now buttons
        Elements cartButtons = document.select("button:matchesOwn((?i)add to cart|buy now|pre order), " +
                "a:matchesOwn((?i)add to cart|buy now|pre order), " +
                "input[type=\"submit\"][value~=(?i)add to cart|buy now], " +
                ".btn-add-to-cart, .btn-buy-now, #btnAddToCart");

        for (Element btn : cartButtons) {
            boolean isDisabled = btn.hasAttr("disabled")
                    || btn.classNames().stream().anyMatch(c -> c.equalsIgnoreCase("disabled"))
                    || "true".equalsIgnoreCase(btn.attr("aria-disabled"));

            if (!isDisabled) {
                return Snapshot.inStock(detectedPrice, "HTML fallback: enabled action button ('" + btn.text() + "')");
            }
        }

        // Check whole body text if button was not explicitly structured
        String fullText = document.body() != null ? document.body().text().toLowerCase(Locale.ROOT) : "";
        if (fullText.contains("out of stock") || fullText.contains("sold out")) {
            return Snapshot.outOfStock(detectedPrice, "HTML fallback: page text contains 'out of stock'");
        }

        return Snapshot.unknown("HTML fallback: neither JSON-LD nor cart buttons confirmed availability");
    }

    private BigDecimal extractHtmlPrice(Document document) {
        Elements priceElements = document.select(".our_price, .price, .selling-price, .product-price, " +
                "span[itemprop=\"price\"], .pro-price, span:matchesOwn(₹\\s*\\d+)");

        for (Element el : priceElements) {
            String text = el.text();
            BigDecimal price = parsePriceValue(text);
            if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                return price;
            }
        }
        return null;
    }
}
