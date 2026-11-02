package io.github.wesleyosantos91.proposta.bdd;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.trace.v1.Span;

final class OtlpCapture implements AutoCloseable {
    private final HttpServer server;
    private final ConcurrentLinkedQueue<ExportTraceServiceRequest> traces = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ExportMetricsServiceRequest> metrics = new ConcurrentLinkedQueue<>();
    volatile boolean available = true;

    OtlpCapture() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                if (!available) {
                    exchange.sendResponseHeaders(503, -1);
                    return;
                }
                var data = exchange.getRequestBody().readAllBytes();
                switch (exchange.getRequestURI().getPath()) {
                    case "/v1/traces" -> traces.add(ExportTraceServiceRequest.parseFrom(data));
                    case "/v1/metrics" -> metrics.add(ExportMetricsServiceRequest.parseFrom(data));
                    default -> {
                        exchange.sendResponseHeaders(404, -1);
                        return;
                    }
                }
                exchange.getResponseHeaders().add("Content-Type", "application/x-protobuf");
                exchange.sendResponseHeaders(200, -1);
            }
        });
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    Stream<ObservedSpan> spans() {
        return traces.stream().flatMap(request -> request.getResourceSpansList().stream())
                .flatMap(resource -> resource.getScopeSpansList().stream()
                        .flatMap(scope -> scope.getSpansList().stream())
                        .map(span -> new ObservedSpan(attribute(resource.getResource().getAttributesList(), "service.name"), span)));
    }

    Stream<ObservedMetric> metrics() {
        return metrics.stream().flatMap(request -> request.getResourceMetricsList().stream())
                .flatMap(resource -> resource.getScopeMetricsList().stream()
                        .flatMap(scope -> scope.getMetricsList().stream())
                        .map(metric -> new ObservedMetric(attribute(resource.getResource().getAttributesList(), "service.name"), metric)));
    }

    static String attribute(List<KeyValue> attributes, String name) {
        return attributes.stream().filter(value -> name.equals(value.getKey()))
                .map(value -> value.getValue().getStringValue()).findFirst().orElse("");
    }

    @Override
    public void close() throws IOException {
        server.stop(0);
        var directory = Path.of(System.getProperty("insurance.repository.root"),
                "tests", "integration", "target", "observability");
        Files.createDirectories(directory);
        var traceRequest = ExportTraceServiceRequest.newBuilder();
        traces.forEach(request -> traceRequest.addAllResourceSpans(request.getResourceSpansList()));
        Files.write(directory.resolve("traces.pb"), traceRequest.build().toByteArray());
        var metricRequest = ExportMetricsServiceRequest.newBuilder();
        metrics.forEach(request -> metricRequest.addAllResourceMetrics(request.getResourceMetricsList()));
        Files.write(directory.resolve("metrics.pb"), metricRequest.build().toByteArray());
        var summary = Map.of("traceBatches", traces.size(), "metricBatches", metrics.size(),
                "spans", spans().count(), "metricRecords", metrics().count(),
                "services", spans().map(ObservedSpan::service).distinct().sorted().toList(),
                "customMetrics", metrics().map(point -> point.metric().getName())
                        .filter(name -> name.startsWith("insurance.")).distinct().sorted().toList());
        Files.writeString(directory.resolve("summary.json"),
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(summary));
    }

    record ObservedSpan(String service, Span span) {
    }

    record ObservedMetric(String service, Metric metric) {
    }
}
