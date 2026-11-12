package com.restockradar.util;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class UrlValidator {

    private static final Pattern IPV4_PATTERN =
            Pattern.compile("^(\\d{1,3}\\.){3}\\d{1,3}$");

    public static final Map<String, Set<String>> ALLOWED_DOMAINS = Map.of(
            "GAMES_THE_SHOP", Set.of("gamestheshop.com", "www.gamestheshop.com", "green-api.gamestheshop.com"),
            "AMAZON_IN", Set.of("amazon.in", "www.amazon.in"),
            "FLIPKART", Set.of("flipkart.com", "www.flipkart.com"),
            "CROMA", Set.of("croma.com", "www.croma.com"),
            "ROOTER", Set.of("rooter.gg", "www.rooter.gg"),
            "STANSHOP", Set.of("stanshop.in", "www.stanshop.in"),
            "BLINKIT", Set.of("blinkit.com", "www.blinkit.com")
    );

    private UrlValidator() {
    }

    /**
     * Validates that the URL uses HTTPS, does not point to an IP address or localhost/private network,
     * and belongs to the allowed domains for the given store.
     */
    public static void validate(String storeCode, String urlString) {
        if (urlString == null || urlString.isBlank()) {
            throw new IllegalArgumentException("URL cannot be empty");
        }

        URI uri;
        try {
            uri = new URI(urlString.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URL syntax: " + e.getMessage());
        }

        String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("https")) {
            throw new IllegalArgumentException("Only HTTPS URLs are allowed");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL must include a valid host");
        }

        String lowerHost = host.toLowerCase(Locale.ROOT);

        // Reject localhost and local domain names
        if (lowerHost.equals("localhost") || lowerHost.endsWith(".localhost")
                || lowerHost.endsWith(".local") || lowerHost.endsWith(".internal")) {
            throw new IllegalArgumentException("Local and private hostnames are not allowed");
        }

        // Reject IP addresses (IPv4 or IPv6)
        if (IPV4_PATTERN.matcher(lowerHost).matches() || lowerHost.contains(":")) {
            throw new IllegalArgumentException("Direct IP addresses are not allowed: " + host);
        }

        // Check if the store is supported
        Set<String> allowed = ALLOWED_DOMAINS.get(storeCode);
        if (allowed == null) {
            throw new IllegalArgumentException("Unsupported store code: " + storeCode);
        }

        // Check against allowed domains for this store
        boolean matchesAllowed = allowed.stream().anyMatch(domain ->
                lowerHost.equals(domain) || lowerHost.endsWith("." + domain)
        );

        if (!matchesAllowed) {
            throw new IllegalArgumentException("Host '" + host + "' is not permitted for store " + storeCode
                    + ". Allowed: " + allowed);
        }

        // Additional safeguard: verify resolved address is not loopback/private
        try {
            InetAddress address = InetAddress.getByName(lowerHost);
            if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress() || address.isAnyLocalAddress()) {
                throw new IllegalArgumentException("Host resolves to a private or loopback address: " + host);
            }
        } catch (UnknownHostException ignored) {
            // Unresolved hosts at validation time will fail on actual HTTP fetch, which is acceptable
        }
    }

    /**
     * Checks if a redirect target is permitted for the given store.
     */
    public static boolean isRedirectAllowed(String storeCode, String targetUrl) {
        try {
            validate(storeCode, targetUrl);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
