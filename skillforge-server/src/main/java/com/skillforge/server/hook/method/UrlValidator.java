package com.skillforge.server.hook.method;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.regex.Pattern;

public final class UrlValidator {

    private UrlValidator() {}

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "localhost", "127.0.0.1", "0.0.0.0", "[::1]", "::1"
    );

    private static final Pattern PRIVATE_10 = Pattern.compile("^10\\..*");
    private static final Pattern PRIVATE_172 = Pattern.compile("^172\\.(1[6-9]|2[0-9]|3[01])\\..*");
    private static final Pattern PRIVATE_192 = Pattern.compile("^192\\.168\\..*");
    private static final Pattern LINK_LOCAL = Pattern.compile("^169\\.254\\..*");
    private static final Pattern IPV6_UNIQUE_LOCAL = Pattern.compile("^(?i)(fc|fd)[0-9a-f]{2}:.*");

    public static String validate(String url, Set<String> allowedHosts) {
        if (url == null || url.isBlank()) {
            return "url is required";
        }

        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            return "malformed URL";
        }

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
            return "blocked scheme: " + scheme;
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "missing host";
        }
        String hostLower = host.toLowerCase();

        if (allowedHosts != null && allowedHosts.contains(hostLower)) {
            return null;
        }

        if (BLOCKED_HOSTS.contains(hostLower)) {
            return "blocked host: " + host;
        }

        if (PRIVATE_10.matcher(hostLower).matches()
                || PRIVATE_172.matcher(hostLower).matches()
                || PRIVATE_192.matcher(hostLower).matches()
                || LINK_LOCAL.matcher(hostLower).matches()) {
            return "blocked private network: " + host;
        }

        if (IPV6_UNIQUE_LOCAL.matcher(hostLower).matches()) {
            return "blocked IPv6 unique-local: " + host;
        }

        // Resolve hostname to InetAddress to catch bypasses like ::ffff:127.0.0.1
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isLoopbackAddress()) {
                return "blocked loopback (resolved): " + host;
            }
            if (addr.isSiteLocalAddress()) {
                return "blocked site-local (resolved): " + host;
            }
            if (addr.isLinkLocalAddress()) {
                return "blocked link-local (resolved): " + host;
            }
            if (addr.isAnyLocalAddress()) {
                return "blocked any-local (resolved): " + host;
            }
        } catch (UnknownHostException e) {
            return "unresolvable host: " + host;
        }

        return null;
    }

    public static String validate(String url) {
        return validate(url, Set.of());
    }
}
