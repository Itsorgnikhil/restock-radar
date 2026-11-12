package com.restockradar.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.restockradar.config.AppConfig;
import com.restockradar.target.Target;
import com.restockradar.util.UrlValidator;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public abstract class JsonLdMonitor implements StoreMonitor {

    private static final Logger log = LoggerFactory.getLogger(JsonLdMonitor.class);
    private static final int MAX_REDIRECTS = 3;

    protected final ObjectMapper objectMapper;

    public JsonLdMonitor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Override
    public Snapshot check(Target target) {
        String currentUrl = target.getUrl();
        try {
            UrlValidator.validate(storeCode(), currentUrl);
        } catch (IllegalArgumentException e) {
            return Snapshot.unknown("Invalid target URL: " + e.getMessage());
        }

        int redirectCount = 0;
        while (redirectCount <= MAX_REDIRECTS) {
            try {
                Connection.Response response = Jsoup.connect(currentUrl)
                        .userAgent(AppConfig.USER_AGENT)
                        .timeout((int) AppConfig.TIMEOUT.toMillis())
                        .ignoreHttpErrors(true)
                        .followRedirects(false)
                        .execute();

                int statusCode = response.statusCode();

                // Check for redirects
                if (statusCode == 301 || statusCode == 302 || statusCode == 303 || statusCode == 307 || statusCode == 308) {
                    String location = response.header("Location");
                    if (location == null || location.isBlank()) {
                        return Snapshot.unknown("HTTP redirect without Location header");
                    }

                    // Resolve relative redirect URL
                    URI baseUri = URI.create(currentUrl);
                    URI resolvedUri = baseUri.resolve(location);
                    String nextUrl = resolvedUri.toString();

                    if (!UrlValidator.isRedirectAllowed(storeCode(), nextUrl)) {
                        return Snapshot.unknown("Redirect to disallowed host: " + nextUrl);
                    }

                    currentUrl = nextUrl;
                    redirectCount++;
                    continue;
                }

                // Check blocked responses
                if (statusCode == 403 || statusCode == 429) {
                    return Snapshot.blocked("HTTP " + statusCode + " response");
                }

                if (statusCode != 200) {
                    return Snapshot.unknown("HTTP " + statusCode + " response");
                }

                Document document = response.parse();

                // Check for CAPTCHA / robot check markers
                if (isBlockedPage(document)) {
                    return Snapshot.blocked("Bot check / CAPTCHA challenge detected on page");
                }

                // Try JSON-LD extraction
                Snapshot jsonLdSnapshot = parseJsonLd(document);
                if (jsonLdSnapshot != null) {
                    return jsonLdSnapshot;
                }

                // Delegate to store-specific fallback
                return fallbackCheck(document, response, target);

            } catch (IOException e) {
                log.warn("I/O error checking store {} at {}: {}", storeCode(), currentUrl, e.getMessage());
                return Snapshot.unknown("Connection or I/O failure: " + e.getMessage());
            } catch (Exception e) {
                log.error("Unexpected error checking store {} at {}: {}", storeCode(), currentUrl, e.getMessage(), e);
                return Snapshot.unknown("Unexpected error: " + e.getMessage());
            }
        }

        return Snapshot.unknown("Too many redirects (exceeded " + MAX_REDIRECTS + ")");
    }

    /**
     * Store-specific fallback when JSON-LD is absent or lacks availability.
     */
    public Snapshot fallbackCheck(Document document, Connection.Response response, Target target) {
        return Snapshot.unknown("JSON-LD not found or missing availability information");
    }

    /**
     * Checks if the page is a robot-check or CAPTCHA challenge page.
     */
    protected boolean isBlockedPage(Document document) {
        String title = document.title().toLowerCase(Locale.ROOT);
        String bodyText = document.body() != null ? document.body().text().toLowerCase(Locale.ROOT) : "";

        if (title.contains("robot check") || title.contains("access denied") || title.contains("captcha")
                || title.contains("shield square") || title.contains("just a moment...")) {
            return true;
        }

        if (bodyText.contains("enter the characters you see below")
                || bodyText.contains("please complete the security check")
                || bodyText.contains("verify you are a human")
                || bodyText.contains("automated access to this page has been blocked")) {
            return true;
        }

        return false;
    }

    /**
     * Parses all <script type="application/ld+json"> blocks in the document.
     */
    public Snapshot parseJsonLd(Document document) {
        Elements scripts = document.select("script[type=\"application/ld+json\"]");
        for (Element script : scripts) {
            String jsonContent = script.data();
            if (jsonContent == null || jsonContent.isBlank()) {
                jsonContent = script.html();
            }
            if (jsonContent == null || jsonContent.isBlank()) {
                continue;
            }

            try {
                JsonNode root = objectMapper.readTree(jsonContent.trim());
                Snapshot snapshot = inspectJsonLdNode(root);
                if (snapshot != null) {
                    return snapshot;
                }
            } catch (Exception e) {
                log.debug("Failed parsing JSON-LD script block: {}", e.getMessage());
            }
        }
        return null;
    }

    private Snapshot inspectJsonLdNode(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return null;
        }

        if (node.isArray()) {
            for (JsonNode item : node) {
                Snapshot s = inspectJsonLdNode(item);
                if (s != null) {
                    return s;
                }
            }
            return null;
        }

        // Check @graph array
        if (node.has("@graph")) {
            return inspectJsonLdNode(node.get("@graph"));
        }

        // Check if node is Product
        JsonNode typeNode = node.get("@type");
        if (typeNode != null) {
            String type = typeNode.asText();
            if (isProductType(type)) {
                return parseProductNode(node);
            }
            if ("Offer".equalsIgnoreCase(type)) {
                return parseOfferNode(node);
            }
        }

        // Check if node has offers anyway
        if (node.has("offers")) {
            return parseOffersField(node.get("offers"));
        }

        return null;
    }

    private boolean isProductType(String type) {
        if (type == null) return false;
        String lower = type.toLowerCase(Locale.ROOT);
        return lower.equals("product") || lower.equals("individualproduct")
                || lower.equals("productmodel") || lower.endsWith("/product");
    }

    private Snapshot parseProductNode(JsonNode productNode) {
        if (productNode.has("offers")) {
            return parseOffersField(productNode.get("offers"));
        }
        return null;
    }

    private Snapshot parseOffersField(JsonNode offersNode) {
        if (offersNode == null || offersNode.isMissingNode() || offersNode.isNull()) {
            return null;
        }

        List<JsonNode> offerList = new ArrayList<>();
        if (offersNode.isArray()) {
            for (JsonNode offer : offersNode) {
                offerList.add(offer);
            }
        } else {
            offerList.add(offersNode);
        }

        boolean hasInStock = false;
        boolean hasOutOfStock = false;
        BigDecimal detectedPrice = null;
        String rawAvailability = null;

        for (JsonNode offer : offerList) {
            BigDecimal price = extractPrice(offer);
            if (price != null && detectedPrice == null) {
                detectedPrice = price;
            }

            JsonNode availNode = offer.get("availability");
            if (availNode != null && !availNode.isNull()) {
                String availText = availNode.asText();
                rawAvailability = availText;
                Availability avail = mapAvailability(availText);
                if (avail == Availability.IN_STOCK) {
                    hasInStock = true;
                } else if (avail == Availability.OUT_OF_STOCK) {
                    hasOutOfStock = true;
                }
            }
        }

        if (hasInStock) {
            return Snapshot.inStock(detectedPrice, "JSON-LD availability: " + rawAvailability);
        }
        if (hasOutOfStock) {
            return Snapshot.outOfStock(detectedPrice, "JSON-LD availability: " + rawAvailability);
        }

        return null;
    }

    private Snapshot parseOfferNode(JsonNode offerNode) {
        JsonNode availNode = offerNode.get("availability");
        if (availNode == null || availNode.isNull()) {
            return null;
        }
        String availText = availNode.asText();
        Availability avail = mapAvailability(availText);
        if (avail == Availability.UNKNOWN) {
            return null;
        }
        BigDecimal price = extractPrice(offerNode);
        return new Snapshot(avail, price, "JSON-LD offer availability: " + availText);
    }

    protected Availability mapAvailability(String availability) {
        if (availability == null) {
            return Availability.UNKNOWN;
        }
        String lower = availability.toLowerCase(Locale.ROOT);
        if (lower.contains("instock") || lower.contains("in_stock") || lower.contains("preorder")) {
            return Availability.IN_STOCK;
        }
        if (lower.contains("outofstock") || lower.contains("out_of_stock")
                || lower.contains("soldout") || lower.contains("sold_out")
                || lower.contains("discontinued")) {
            return Availability.OUT_OF_STOCK;
        }
        return Availability.UNKNOWN;
    }

    protected BigDecimal extractPrice(JsonNode node) {
        if (node == null) return null;

        JsonNode priceNode = node.get("price");
        if (priceNode != null && !priceNode.isNull()) {
            return parsePriceValue(priceNode.asText());
        }

        JsonNode lowPriceNode = node.get("lowPrice");
        if (lowPriceNode != null && !lowPriceNode.isNull()) {
            return parsePriceValue(lowPriceNode.asText());
        }

        JsonNode priceSpec = node.get("priceSpecification");
        if (priceSpec != null && priceSpec.has("price")) {
            return parsePriceValue(priceSpec.get("price").asText());
        }

        return null;
    }

    protected BigDecimal parsePriceValue(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            // Remove currency symbols, commas, non-numeric characters except decimal point
            String clean = raw.replaceAll("[^0-9.]", "").trim();
            if (clean.isBlank()) return null;
            return new BigDecimal(clean);
        } catch (Exception e) {
            return null;
        }
    }
}
