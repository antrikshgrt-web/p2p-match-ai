package com.p2pmatchai;

import com.p2pmatchai.data.MockSapRepository;
import com.p2pmatchai.data.MockSapRepository.SampleInvoice;
import com.p2pmatchai.engine.AuditLog;
import com.p2pmatchai.engine.ExtractionService;
import com.p2pmatchai.engine.MatchingEngine;
import com.p2pmatchai.engine.NarrativeService;
import com.p2pmatchai.model.Models.AuditEntry;
import com.p2pmatchai.model.Models.Finding;
import com.p2pmatchai.model.Models.Invoice;
import com.p2pmatchai.model.Models.MatchResult;
import com.p2pmatchai.model.Models.TestCaseResult;
import com.p2pmatchai.web.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * P2P Match AI — application entry point.
 *
 * Backend: com.sun.net.httpserver (bundled with the JDK), so the whole prototype
 * builds with javac and runs with java. No Maven, Gradle, Spring or internet access
 * is required.
 *
 * Usage:  java -cp out com.p2pmatchai.App [port]
 */
public final class App {

    private static final MockSapRepository REPO = new MockSapRepository();
    private static final NarrativeService NARRATOR = new NarrativeService();
    private static final MatchingEngine ENGINE = new MatchingEngine(REPO, NARRATOR);
    private static final AuditLog AUDIT = new AuditLog(Paths.get("audit-log.csv"));

    /** Invoice numbers already verified in this session (duplicate detection). */
    private static final Set<String> PROCESSED = java.util.Collections
            .synchronizedSet(new LinkedHashSet<>());

    /** Latest result per invoice, so a decision can be logged against it. */
    private static final Map<String, MatchResult> LAST_RESULTS =
            java.util.Collections.synchronizedMap(new HashMap<>());

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));

        server.createContext("/", App::serveStatic);
        server.createContext("/api/health", ex -> json(ex, 200,
                "{\"status\":\"UP\",\"llmNarratives\":" + NARRATOR.llmEnabled()
                        + ",\"qtyTolerancePct\":" + MatchingEngine.QTY_TOLERANCE_PCT
                        + ",\"priceTolerancePct\":" + MatchingEngine.PRICE_TOLERANCE_PCT
                        + ",\"roundingTolerance\":" + MatchingEngine.AMOUNT_ROUNDING_TOLERANCE + "}"));
        server.createContext("/api/samples", App::samples);
        server.createContext("/api/reference", App::reference);
        server.createContext("/api/grncheck", App::grnCheck);
        server.createContext("/api/verify", App::verify);
        server.createContext("/api/decision", App::decision);
        server.createContext("/api/audit", App::audit);
        server.createContext("/api/evaluate", App::evaluate);
        server.createContext("/api/reset", App::reset);

        server.start();

        System.out.println();
        System.out.println("  P2P Match AI — Invoice Verification & Exception-Triage Agent");
        System.out.println("  ------------------------------------------------------------");
        System.out.println("  Dashboard : http://localhost:" + port + "/");
        System.out.println("  LLM narratives : " + (NARRATOR.llmEnabled()
                ? "ON (ANTHROPIC_API_KEY detected)"
                : "OFF (deterministic offline mode)"));
        System.out.println("  Audit trail : audit-log.csv");
        System.out.println("  Press Ctrl+C to stop.");
        System.out.println();
    }

    /* ------------------------------------------------------------------ */
    /* Handlers                                                            */
    /* ------------------------------------------------------------------ */

    private static void samples(HttpExchange ex) throws IOException {
        json(ex, 200, Json.array(REPO.allSamples(), Json::sample));
    }

    private static void reference(HttpExchange ex) throws IOException {
        String body = "{\"purchaseOrders\":" + Json.array(REPO.allPurchaseOrders(), Json::purchaseOrder)
                + ",\"goodsReceipts\":" + Json.array(REPO.allGoodsReceipts(), Json::goodsReceipt)
                + ",\"vendors\":[" + String.join(",", REPO.allVendors().stream()
                .map(v -> "{\"code\":" + Json.str(v.code())
                        + ",\"name\":" + Json.str(v.name())
                        + ",\"gstin\":" + Json.str(v.gstin())
                        + ",\"state\":" + Json.str(v.state()) + "}")
                .toList()) + "]}";
        json(ex, 200, body);
    }

    private static void grnCheck(HttpExchange ex) throws IOException {
        Map<String, String> q = parse(ex.getRequestURI().getRawQuery());
        String po = q.getOrDefault("po", "");
        List<Finding> findings = ENGINE.checkGrnAgainstPo(po);
        json(ex, 200, "{\"poNumber\":" + Json.str(po)
                + ",\"clean\":" + findings.isEmpty()
                + ",\"findings\":" + Json.array(findings, Json::finding) + "}");
    }

    /** POST /api/verify — body: sampleId=TC01  or  rawText=<document text>. */
    private static void verify(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
            json(ex, 405, Json.error("Use POST"));
            return;
        }
        Map<String, String> form = parse(readBody(ex));

        String rawText = form.get("rawText");
        if (rawText == null || rawText.isBlank()) {
            SampleInvoice sample = REPO.sample(form.getOrDefault("sampleId", ""));
            if (sample == null) {
                json(ex, 400, Json.error("Provide rawText or a valid sampleId"));
                return;
            }
            rawText = sample.rawText();
        }

        Invoice invoice = new ExtractionService().extract(rawText);
        MatchResult result = ENGINE.verify(invoice, PROCESSED);

        LAST_RESULTS.put(result.invoiceNumber().toUpperCase(), result);

        // The recommendation is logged immediately, with the human decision still PENDING,
        // so an AI output can never exist without an audit row.
        AUDIT.record(result.invoiceNumber(), result.poNumber(), result.classification(),
                result.recommendation(), "PENDING", "", "system");

        json(ex, 200, "{\"extracted\":" + Json.invoice(invoice)
                + ",\"result\":" + Json.matchResult(result) + "}");
    }

    /** POST /api/decision — records the human confirm / hold / escalate decision. */
    private static void decision(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
            json(ex, 405, Json.error("Use POST"));
            return;
        }
        Map<String, String> form = parse(readBody(ex));
        String invoiceNumber = form.getOrDefault("invoiceNumber", "").trim();
        String decision = form.getOrDefault("decision", "").trim().toUpperCase();
        String reason = form.getOrDefault("reason", "").trim();
        String user = form.getOrDefault("user", "ap.executive").trim();

        if (invoiceNumber.isEmpty()
                || !List.of("CONFIRM", "HOLD", "ESCALATE", "APPROVE_OVERRIDE").contains(decision)) {
            json(ex, 400, Json.error("invoiceNumber and a valid decision are required"));
            return;
        }

        MatchResult last = LAST_RESULTS.get(invoiceNumber.toUpperCase());
        if (last == null) {
            json(ex, 404, Json.error("No verification found for invoice " + invoiceNumber));
            return;
        }

        // The human decision is recorded as given. The agent never re-classifies an
        // invoice because a reviewer disagreed with it (test case 12).
        AuditEntry entry = AUDIT.record(invoiceNumber, last.poNumber(), last.classification(),
                last.recommendation(), decision, reason, user);

        json(ex, 200, "{\"recorded\":" + Json.auditEntry(entry)
                + ",\"auditCompletenessPct\":" + Json.num(AUDIT.completenessPct()) + "}");
    }

    private static void audit(HttpExchange ex) throws IOException {
        json(ex, 200, "{\"entries\":" + Json.array(AUDIT.all(), Json::auditEntry)
                + ",\"count\":" + AUDIT.size()
                + ",\"completenessPct\":" + Json.num(AUDIT.completenessPct()) + "}");
    }

    /** POST /api/evaluate — runs every sample as a labelled test case (Annexure E). */
    private static void evaluate(HttpExchange ex) throws IOException {
        // A throwaway engine, ledger and offline narrator keep the run reproducible
        // and isolated from whatever the user has been clicking in the dashboard.
        MatchingEngine harnessEngine = new MatchingEngine(REPO, new NarrativeService(false));
        Set<String> ledger = new LinkedHashSet<>();
        ExtractionService extractor = new ExtractionService();

        List<TestCaseResult> results = new ArrayList<>();
        int number = 0;
        int passed = 0;

        for (SampleInvoice sample : REPO.allSamples()) {
            number++;
            Invoice invoice = extractor.extract(sample.rawText());
            MatchResult result = harnessEngine.verify(invoice, ledger);

            boolean findingRaised = sample.expectedFindingCode() == null
                    || result.findings().stream()
                    .anyMatch(f -> f.code().equals(sample.expectedFindingCode()));
            boolean ok = result.classification().equals(sample.expectedClassification())
                    && findingRaised;
            if (ok) {
                passed++;
            }

            results.add(new TestCaseResult(number, sample.category(), sample.label(),
                    sample.expectedClassification(), result.classification(),
                    sample.expectedFindingCode() == null ? "-" : sample.expectedFindingCode(),
                    findingRaised, ok));
        }

        double passRate = results.isEmpty() ? 0.0 : (passed * 100.0) / results.size();
        json(ex, 200, "{\"total\":" + results.size()
                + ",\"passed\":" + passed
                + ",\"passRatePct\":" + Json.num(passRate)
                + ",\"cases\":" + Json.array(results, Json::testCaseResult) + "}");
    }

    private static void reset(HttpExchange ex) throws IOException {
        PROCESSED.clear();
        LAST_RESULTS.clear();
        json(ex, 200, "{\"reset\":true}");
    }

    /* ------------------------------------------------------------------ */
    /* Static file serving                                                 */
    /* ------------------------------------------------------------------ */

    private static void serveStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/") || path.isEmpty()) {
            path = "/index.html";
        }
        if (path.contains("..")) {
            send(ex, 400, "text/plain", "Bad path".getBytes(StandardCharsets.UTF_8));
            return;
        }

        Path file = locateWebFile(path.substring(1));
        if (file == null) {
            send(ex, 404, "text/plain",
                    ("Not found: " + path + "\nExpected the web/ folder next to the working directory.")
                            .getBytes(StandardCharsets.UTF_8));
            return;
        }
        send(ex, 200, contentType(path), Files.readAllBytes(file));
    }

    private static Path locateWebFile(String name) {
        List<Path> candidates = List.of(
                Paths.get("web", name),
                Paths.get("..", "web", name),
                Paths.get(name));
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (path.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (path.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        return "application/octet-stream";
    }

    /* ------------------------------------------------------------------ */
    /* Plumbing                                                            */
    /* ------------------------------------------------------------------ */

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        send(ex, status, "application/json; charset=utf-8",
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, String contentType, byte[] body)
            throws IOException {
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Parses application/x-www-form-urlencoded bodies and query strings. */
    private static Map<String, String> parse(String encoded) {
        Map<String, String> map = new HashMap<>();
        if (encoded == null || encoded.isBlank()) {
            return map;
        }
        for (String pair : encoded.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            map.put(key, value);
        }
        return map;
    }
}
