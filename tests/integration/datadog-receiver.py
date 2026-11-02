"""Local test double: receives DogStatsD and trace HTTP traffic; never exports to Datadog."""
import json
import gzip
import socketserver
import threading
from collections import defaultdict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

lock = threading.Lock()
metrics = defaultdict(lambda: defaultdict(int))
tags_seen = defaultdict(set)
http_requests = defaultdict(int)
log_messages = []


class DogStatsDHandler(socketserver.BaseRequestHandler):
    def handle(self):
        for line in self.request[0].decode("utf-8", errors="replace").splitlines():
            parts = line.split("|")
            name = parts[0].split(":", 1)[0]
            tags = {tag for part in parts[1:] if part.startswith("#") for tag in part[1:].split(",")}
            service = next((tag[8:] for tag in tags if tag.startswith("service:")), None)
            if service:
                with lock:
                    metrics[service][name] += 1
                    tags_seen[service].update(tags)


class AgentHandler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def respond(self, body):
        data = json.dumps(body).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/results":
            with lock:
                self.respond({
                    "services": {service: {"metrics": dict(names), "tags": sorted(tags_seen[service])}
                                 for service, names in metrics.items()},
                    "http_requests": dict(http_requests),
                    "logMessages": list(log_messages),
                })
        else:
            self.respond({"version": "7.85.0", "endpoints": ["/v0.4/traces", "/v0.6/stats"],
                          "client_drop_p0s": False})

    def do_POST(self):
        data = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        if self.headers.get("Content-Encoding") == "gzip":
            data = gzip.decompress(data)
        if self.path.startswith("/api/v2/logs"):
            for record in json.loads(data):
                message = record.get("message", "")
                try:
                    parsed = record if message == "insurance-observability-local-log-validation" else (json.loads(message) if isinstance(message, str) else message)
                except (ValueError, TypeError):
                    continue
                if isinstance(parsed, dict) and parsed.get("message") == "insurance-observability-local-log-validation":
                    with lock:
                        if len(log_messages) < 20:
                            log_messages.append(parsed)
        with lock:
            http_requests[self.path] += 1
        self.respond({"rate_by_service": {}})

    do_PUT = do_POST


if __name__ == "__main__":
    udp = socketserver.ThreadingUDPServer(("0.0.0.0", 8125), DogStatsDHandler)
    threading.Thread(target=udp.serve_forever, daemon=True).start()
    ThreadingHTTPServer(("0.0.0.0", 8126), AgentHandler).serve_forever()
