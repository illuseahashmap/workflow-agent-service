package io.github.illuseahashmap.agent.provider.infrastructure.openai;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** LangChain4j adapter over the shared governed outbound transport. */
public final class BoundedLangChain4jHttpClientBuilder implements HttpClientBuilder {
    private final int maximumResponseBytes;
    private final ProviderEndpointValidator endpointValidator;
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration readTimeout;

    public BoundedLangChain4jHttpClientBuilder(int maximumResponseBytes, ProviderEndpointValidator endpointValidator) {
        this.maximumResponseBytes = maximumResponseBytes;
        this.endpointValidator = java.util.Objects.requireNonNull(endpointValidator, "endpointValidator");
    }

    @Override
    public Duration connectTimeout() { return connectTimeout; }
    @Override
    public HttpClientBuilder connectTimeout(Duration timeout) {
        connectTimeout = timeout;
        return this;
    }
    @Override
    public Duration readTimeout() { return readTimeout; }
    @Override
    public HttpClientBuilder readTimeout(Duration timeout) {
        readTimeout = timeout;
        return this;
    }

    @Override
    public HttpClient build() {
        return new HttpClient() {
            @Override
            public SuccessfulHttpResponse execute(HttpRequest request) {
                var builder = java.net.http.HttpRequest.newBuilder(URI.create(request.url()))
                        .timeout(readTimeout == null ? Duration.ofSeconds(60) : readTimeout);
                request.headers().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
                byte[] payload = request.body() == null ? null : request.body().getBytes(StandardCharsets.UTF_8);
                builder.method(request.method().name(), java.net.http.HttpRequest.BodyPublishers.noBody());
                try {
                    var response = GovernedHttpTransport.send(builder.build(), payload,
                            javax.net.ssl.SSLContext.getDefault(), connectTimeout, endpointValidator, maximumResponseBytes);
                    byte[] bytes;
                    try (var stream = response.body()) { bytes = stream.readAllBytes(); }
                    if (bytes.length > maximumResponseBytes) { throw new ResponseTooLargeException(); }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new HttpException(response.statusCode(), new String(bytes, StandardCharsets.UTF_8));
                    }
                    return SuccessfulHttpResponse.builder().statusCode(response.statusCode())
                            .headers(response.headers().map()).body(bytes).build();
                } catch (java.net.http.HttpTimeoutException exception) {
                    throw new TimeoutException(exception);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(exception);
                } catch (GovernedHttpTransport.ResponseTooLargeException exception) {
                    throw new ResponseTooLargeException();
                } catch (IOException exception) {
                    throw new dev.langchain4j.exception.RetriableException("Model Provider transport is unavailable", exception);
                } catch (java.security.NoSuchAlgorithmException exception) {
                    throw new IllegalStateException("TLS is unavailable", exception);
                }
            }

            @Override
            public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
                listener.onError(new UnsupportedOperationException("Streaming is not enabled for workflow Agent model calls"));
            }
        };
    }

    static final class ResponseTooLargeException extends RuntimeException {
        private ResponseTooLargeException() { super("Model Provider response exceeded the configured size limit"); }
    }
}
