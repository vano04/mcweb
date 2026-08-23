package com.mojang.authlib;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/** Authlib ABI replacement that parses HTTP URLs without a desktop handler. */
public abstract class HttpAuthenticationService implements AuthenticationService {
    private static final class BrowserUrlHandler extends URLStreamHandler {
        private final int defaultPort;

        private BrowserUrlHandler(int defaultPort) {
            this.defaultPort = defaultPort;
        }

        @Override
        protected URLConnection openConnection(URL url) throws IOException {
            throw new IOException(
                    "Browser Authlib requests must use the fetch compatibility bridge: " + url
            );
        }

        @Override
        protected URLConnection openConnection(URL url, Proxy proxy) throws IOException {
            return openConnection(url);
        }

        @Override
        protected int getDefaultPort() {
            return defaultPort;
        }
    }

    private static final URLStreamHandler HTTP_HANDLER = new BrowserUrlHandler(80);
    private static final URLStreamHandler HTTPS_HANDLER = new BrowserUrlHandler(443);
    private static final URLStreamHandler GENERIC_HANDLER = new BrowserUrlHandler(-1);

    private final Proxy proxy;

    protected HttpAuthenticationService(Proxy proxy) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
    }

    public Proxy getProxy() {
        return proxy;
    }

    public static URL constantURL(String value) {
        try {
            return new URL(null, value, handlerFor(value));
        } catch (MalformedURLException exception) {
            throw new Error("Couldn't create constant for " + value, exception);
        }
    }

    private static URLStreamHandler handlerFor(String value) {
        int separator = value.indexOf(':');
        String protocol = separator < 0 ? "" : value.substring(0, separator);
        if (protocol.equalsIgnoreCase("http")) {
            return HTTP_HANDLER;
        }
        if (protocol.equalsIgnoreCase("https")) {
            return HTTPS_HANDLER;
        }
        return GENERIC_HANDLER;
    }

    public static String buildQuery(Map<String, Object> query) {
        if (query == null) {
            return "";
        }

        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, Object> entry : query.entrySet()) {
            if (!result.isEmpty()) {
                result.append('&');
            }
            result.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            if (entry.getValue() != null) {
                result.append('=');
                result.append(URLEncoder.encode(
                        entry.getValue().toString(),
                        StandardCharsets.UTF_8
                ));
            }
        }
        return result.toString();
    }

    public static URL concatenateURL(URL url, String query) {
        String separator = url.getQuery() != null && !url.getQuery().isEmpty() ? "&" : "?";
        String host = url.getHost();
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
            host = '[' + host + ']';
        }
        StringBuilder value = new StringBuilder()
                .append(url.getProtocol())
                .append("://")
                .append(host);
        if (url.getPort() >= 0) {
            value.append(':').append(url.getPort());
        }
        value.append(url.getFile()).append(separator).append(query);

        try {
            return new URL(null, value.toString(), handlerFor(value.toString()));
        } catch (MalformedURLException exception) {
            throw new IllegalArgumentException(
                    "Could not concatenate given URL with GET arguments!",
                    exception
            );
        }
    }
}
