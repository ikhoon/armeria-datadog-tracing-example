package org.example;

import static org.example.TracingService.SPAN_CONTEXT_KEY;

import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.SimpleDecoratingHttpClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.internal.client.ClientRequestContextExtension;
import com.linecorp.armeria.internal.common.RequestContextExtension;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.noop.NoopSpan;
import io.opentracing.propagation.Format.Builtin;
import io.opentracing.propagation.TextMapInject;
import io.opentracing.util.GlobalTracer;

final class TracingClient extends SimpleDecoratingHttpClient {

    private static final Logger logger = LoggerFactory.getLogger(TracingClient.class);

    static Function<? super HttpClient, ? extends HttpClient> newDecorator(String serviceName) {
        return delegate -> new TracingClient(delegate, serviceName);
    }

    private final String serviceName;

    /**
     * Creates a new instance that decorates the specified {@link HttpClient}.
     */
    private TracingClient(HttpClient delegate, String serviceName) {
        super(delegate);
        this.serviceName = serviceName;
    }

    @Override
    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        final Tracer tracer = GlobalTracer.get();
        final SpanContext spanContext = ctx.attr(SPAN_CONTEXT_KEY);
        final Span span = tracer.buildSpan(serviceName)
                                .asChildOf(spanContext)
                                .start();
        if (span == NoopSpan.INSTANCE) {
            return unwrap().execute(ctx, req);
        }

        final RequestContextExtension contextExtension = ctx.as(RequestContextExtension.class);
        if (contextExtension != null) {
            contextExtension.hook(() -> tracer.activateSpan(span));
        }

        logger.info("[client:{}] spanContext: traceId: {}, spanId: {}", serviceName, span.context().toTraceId(),
                    span.context().toSpanId());
        final RequestHeadersBuilder headersBuilder = req.headers().toBuilder();
        tracer.inject(span.context(), Builtin.TEXT_MAP_INJECT, new HttpHeadersInjector(headersBuilder));
        final HttpRequest newReq = req.withHeaders(headersBuilder);
        ctx.updateRequest(newReq);

        ctx.log().whenComplete().thenRun(span::finish);
        try (Scope scope = tracer.activateSpan(span)) {
            return unwrap().execute(ctx, newReq);
        }
    }

    private static final class HttpHeadersInjector implements TextMapInject {

        private final RequestHeadersBuilder headersBuilder;

        private HttpHeadersInjector(RequestHeadersBuilder headersBuilder) {
            this.headersBuilder = headersBuilder;
        }

        @Override
        public void put(String key, String value) {
            logger.debug("injected header: {} -> {}", key, value);
            headersBuilder.add(key, value);
        }
    }
}
