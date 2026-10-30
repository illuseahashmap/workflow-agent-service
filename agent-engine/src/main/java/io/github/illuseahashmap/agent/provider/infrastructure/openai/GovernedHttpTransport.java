package io.github.illuseahashmap.agent.provider.infrastructure.openai;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.util.Timeout;

/** Common wire boundary: validated connection DNS, normal TLS identity, no redirects, bounded I/O. */
public final class GovernedHttpTransport {
    private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();
    private GovernedHttpTransport() {}

    private static ScheduledThreadPoolExecutor deadlines() {
        var pool = new ScheduledThreadPoolExecutor(1, task -> {
            var thread = new Thread(task, "outbound-http-deadline");
            thread.setDaemon(true);
            return thread;
        });
        pool.setRemoveOnCancelPolicy(true);
        return pool;
    }

    public static HttpResponse<InputStream> send(HttpRequest request, byte[] payload, HttpClient configuration,
            ProviderEndpointValidator policy, int maximumBytes) throws IOException, InterruptedException {
        return send(request, payload, configuration.sslContext(),
                configuration.connectTimeout().orElse(Duration.ofSeconds(10)), policy, maximumBytes);
    }

    public static HttpResponse<InputStream> send(HttpRequest request, byte[] payload, javax.net.ssl.SSLContext sslContext,
            Duration connectTimeout, ProviderEndpointValidator policy, int maximumBytes) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Outbound call interrupted");
        }
        policy.validate(request.uri());
        var expired = new AtomicBoolean();
        var manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new org.apache.hc.client5.http.DnsResolver() {
                    public java.net.InetAddress[] resolve(String host) throws java.net.UnknownHostException {
                        return policy.resolve(host);
                    }
                    public String resolveCanonicalHostname(String host) { return host; }
                })
                .setTlsSocketStrategy(ClientTlsStrategyBuilder.create().setSslContext(sslContext).buildClassic())
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofMilliseconds(
                        connectTimeout.toMillis())).build()).build();
        try (var client = HttpClients.custom().setConnectionManager(manager).disableAutomaticRetries()
                .disableRedirectHandling().disableCookieManagement().build()) {
            var outbound = new org.apache.hc.client5.http.classic.methods.HttpUriRequestBase(request.method(), request.uri());
            request.headers().map().forEach((name, values) -> values.forEach(value -> outbound.addHeader(name, value)));
            if (payload != null) {
                outbound.setEntity(new org.apache.hc.core5.http.io.entity.ByteArrayEntity(payload,
                        org.apache.hc.core5.http.ContentType.APPLICATION_JSON));
            }
            long timeout = request.timeout().orElse(Duration.ofSeconds(60)).toMillis();
            outbound.setConfig(RequestConfig.custom().setResponseTimeout(Timeout.ofMilliseconds(timeout)).build());
            var deadline = DEADLINES.schedule(() -> {
                expired.set(true);
                outbound.cancel();
            }, timeout, TimeUnit.MILLISECONDS);
            try {
                return client.execute(outbound, response -> {
                    byte[] bytes;
                    try (var stream = response.getEntity() == null ? InputStream.nullInputStream() : response.getEntity().getContent()) {
                        bytes = stream.readNBytes(Math.addExact(maximumBytes, 1));
                        if (bytes.length > maximumBytes) {
                            outbound.cancel();
                            throw new ResponseTooLargeException();
                        }
                    }
                    var headers = new HashMap<String, List<String>>();
                    for (var header : response.getHeaders()) {
                        headers.computeIfAbsent(header.getName(), ignored -> new ArrayList<>()).add(header.getValue());
                    }
                    return new Response(response.getCode(), HttpHeaders.of(headers, (name, value) -> true),
                            new ByteArrayInputStream(bytes), request);
                });
            } finally {
                deadline.cancel(false);
            }
        } catch (IOException exception) {
            if (expired.get() || exception instanceof java.net.SocketTimeoutException) {
                throw new java.net.http.HttpTimeoutException("Outbound request deadline exceeded");
            }
            throw exception;
        }
    }

    private record Response(int statusCode, HttpHeaders headers, InputStream body, HttpRequest request)
            implements HttpResponse<InputStream> {
        public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
        public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        public java.net.URI uri() { return request.uri(); }
        public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }

    public static final class ResponseTooLargeException extends IOException {
        public ResponseTooLargeException() { super("Outbound response exceeds the configured size limit"); }
    }
}
