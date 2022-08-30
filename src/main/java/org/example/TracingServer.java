package org.example;

import static org.example.TracingService.SPAN_CONTEXT_KEY;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonFormat.Features;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.logging.LoggingService;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;

class TracingServer {
    TracingServer(String serviceName, String clientName, int myPort, int clientPort) {
        final Supplier<HttpResponse> responseSupplier;
        if (clientPort == -1) {
            responseSupplier = () -> HttpResponse.of("A traced response");
        } else {
            final WebClient client = WebClient.builder("http://127.0.0.1:" + clientPort)
                                              .decorator(LoggingClient.newDecorator())
                                              .decorator(TracingClient.newDecorator(clientName))
                                              .contextCustomizer(ctx -> {
                                                  final Tracer tracer = GlobalTracer.get();
                                                  final Span activeSpan = tracer.activeSpan();
                                                  ctx.setAttr(SPAN_CONTEXT_KEY, activeSpan.context());
                                              })
                                              .build();
            responseSupplier = () -> client.get("/");
        }

        final Server server =
                Server.builder()
                      .http(myPort)
                      .decorator(LoggingService.newDecorator())
                      .decorator(TracingService.newDecorator(serviceName))
                      .service("/", new NonArmeriaThreadService(responseSupplier))
                      .build();
        server.start().join();
        server.closeOnJvmShutdown();
    }

    class NonArmeriaThreadService implements HttpService {
        private final Supplier<HttpResponse> responseSupplier;

        NonArmeriaThreadService(Supplier<HttpResponse> responseSupplier) {
            this.responseSupplier = responseSupplier;
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            final Tracer tracer = GlobalTracer.get();
            final Span currentSpan = tracer.activeSpan();
            final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
            ForkJoinPool.commonPool().execute(() -> {
                // Assume that the active span populated by other frameworks is in the current thread local.
                try (Scope scope = tracer.activateSpan(currentSpan)) {
                    future.complete(responseSupplier.get());
                }
            });
            return HttpResponse.from(future);
        }
    }
}
