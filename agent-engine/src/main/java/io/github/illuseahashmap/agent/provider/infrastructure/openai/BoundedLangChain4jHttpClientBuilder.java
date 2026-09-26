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
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** LangChain4j transport with the platform's hard response-size boundary. */
final class BoundedLangChain4jHttpClientBuilder implements HttpClientBuilder {

    private final int maximumResponseBytes;
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration readTimeout;

    BoundedLangChain4jHttpClientBuilder(int maximumResponseBytes) {
        this.maximumResponseBytes = maximumResponseBytes;
    }

    @Override
    public Duration connectTimeout() {
        return connectTimeout;
    }

    @Override
    public HttpClientBuilder connectTimeout(Duration timeout) {
        this.connectTimeout = timeout;
        return this;
    }

    @Override
    public Duration readTimeout() {
        return readTimeout;
    }

    @Override
    public HttpClientBuilder readTimeout(Duration timeout) {
        this.readTimeout = timeout;
        return this;
    }

    @Override
    public HttpClient build() {
        return new BoundedClient(connectTimeout, readTimeout, maximumResponseBytes);
    }

    private static final class BoundedClient implements HttpClient {
        private final java.net.http.HttpClient delegate;
        private final Duration readTimeout;
        private final int maximumResponseBytes;

        private BoundedClient(Duration connectTimeout, Duration readTimeout, int maximumResponseBytes) {
            this.delegate = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(connectTimeout)
                    .build();
            this.readTimeout = readTimeout;
            this.maximumResponseBytes = maximumResponseBytes;
        }

        @Override
        public SuccessfulHttpResponse execute(HttpRequest request) {
            try {
                java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder()
                        .uri(URI.create(request.url()));
                request.headers().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
                builder.method(request.method().name(), request.body() == null
                        ? BodyPublishers.noBody() : BodyPublishers.ofString(request.body()));
                if (readTimeout != null) {
                    builder.timeout(readTimeout);
                }
                HttpResponse<InputStream> response = delegate.send(
                        builder.build(), HttpResponse.BodyHandlers.ofInputStream());
                byte[] bytes;
                try (InputStream body = response.body()) {
                    bytes = body.readNBytes(maximumResponseBytes + 1);
                }
                if (bytes.length > maximumResponseBytes) {
                    throw new ResponseTooLargeException();
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new HttpException(response.statusCode(), new String(bytes, StandardCharsets.UTF_8));
                }
                return SuccessfulHttpResponse.builder()
                        .statusCode(response.statusCode())
                        .headers(response.headers().map())
                        .body(bytes)
                        .build();
            } catch (java.net.http.HttpTimeoutException exception) {
                throw new TimeoutException(exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(exception);
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        }

        @Override
        public void execute(HttpRequest request, ServerSentEventParser parser,
                            ServerSentEventListener listener) {
            listener.onError(new UnsupportedOperationException(
                    "Streaming is not enabled for workflow Agent model calls"));
        }
    }

    static final class ResponseTooLargeException extends RuntimeException {
        private ResponseTooLargeException() {
            super("Model Provider response exceeded the configured size limit");
        }
    }
}
