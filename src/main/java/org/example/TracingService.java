package org.example;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.internal.client.ClientRequestContextExtension;
import com.linecorp.armeria.internal.common.RequestContextExtension;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;

import io.netty.util.AttributeKey;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.noop.NoopSpan;
import io.opentracing.propagation.Format.Builtin;
import io.opentracing.propagation.TextMapExtract;
import io.opentracing.util.GlobalTracer;


class TracingService extends SimpleDecoratingHttpService {

    private static final Logger logger = LoggerFactory.getLogger(TracingService.class);
    static final AttributeKey<SpanContext> SPAN_CONTEXT_KEY =
            AttributeKey.valueOf(TracingService.class, "SPAN_CONTEXT");

    static Function<? super HttpService, ? extends HttpService> newDecorator(String serviceName) {
        return service -> new TracingService(service, serviceName);
    }

    private final String serviceName;

    /**
     * Creates a new instance that decorates the specified {@link HttpService}.
     */
    private TracingService(HttpService delegate, String serviceName) {
        super(delegate);
        this.serviceName = serviceName;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final Tracer tracer = GlobalTracer.get();
        final SpanContext spanContext =
                tracer.extract(Builtin.TEXT_MAP_EXTRACT, new HttpHeadersExtractor(req));
        logger.info("[service:{}] spanContext: traceId: {}, spanId: {}", serviceName, spanContext.toTraceId(),
                    spanContext.toSpanId());
        final Span span = tracer.buildSpan(serviceName)
                                .asChildOf(spanContext)
                                .start();
        if (span == NoopSpan.INSTANCE) {
            return unwrap().serve(ctx, req);
        }

        ctx.setAttr(SPAN_CONTEXT_KEY, spanContext);
        final RequestContextExtension contextExtension = ctx.as(RequestContextExtension.class);
        if (contextExtension != null) {
            contextExtension.hook(() -> tracer.activateSpan(span));
        }


        ctx.log().whenComplete().thenRun(span::finish);
        try (Scope scope = tracer.activateSpan(span)) {
            return unwrap().serve(ctx, req);
        }
    }

    private static final class HttpHeadersExtractor implements TextMapExtract {
        private final HttpRequest req;

        HttpHeadersExtractor(HttpRequest req) {
            this.req = req;
        }

        @Override
        public Iterator<Entry<String, String>> iterator() {
            return Streams.stream(req.headers().iterator())
                          .map(entry -> Maps.immutableEntry(entry.getKey().toString(), entry.getValue()))
                          .iterator();
        }
    }
}
