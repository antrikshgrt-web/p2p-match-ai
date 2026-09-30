package com.p2pmatchai.engine;

import com.p2pmatchai.model.Models.AuditEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Audit and feedback log (Annexure B, step 11).
 *
 * Append-only: entries are never edited or deleted, so every AI recommendation is
 * permanently paired with the human decision that followed it. Also mirrored to
 * audit-log.csv so the log survives a restart and can be attached to the report.
 */
public final class AuditLog {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final List<AuditEntry> entries = Collections.synchronizedList(new ArrayList<>());
    private final Path csvFile;

    public AuditLog(Path csvFile) {
        this.csvFile = csvFile;
        writeHeaderIfNeeded();
    }

    public AuditEntry record(String invoiceNumber, String poNumber, String classification,
                             String aiRecommendation, String humanDecision,
                             String reason, String user) {
        AuditEntry entry = new AuditEntry(
                LocalDateTime.now().format(TS),
                invoiceNumber,
                poNumber == null ? "" : poNumber,
                classification,
                aiRecommendation,
                humanDecision == null ? "PENDING" : humanDecision,
                reason == null ? "" : reason,
                user == null ? "ap.executive" : user);
        entries.add(entry);
        appendCsv(entry);
        return entry;
    }

    public List<AuditEntry> all() {
        synchronized (entries) {
            return new ArrayList<>(entries);
        }
    }

    public int size() {
        return entries.size();
    }

    /** Completeness metric: share of AI recommendations that carry a human decision. */
    public double completenessPct() {
        List<AuditEntry> all = all();
        if (all.isEmpty()) {
            return 100.0;
        }
        long decided = all.stream().filter(e -> !e.humanDecision().equals("PENDING")).count();
        return (decided * 100.0) / all.size();
    }

    private void writeHeaderIfNeeded() {
        try {
            if (csvFile != null && !Files.exists(csvFile)) {
                Files.writeString(csvFile,
                        "timestamp,invoice_number,po_number,ai_classification,"
                                + "ai_recommendation,human_decision,reason,user\n",
                        StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            System.err.println("Audit CSV could not be created: " + e.getMessage());
        }
    }

    private void appendCsv(AuditEntry e) {
        if (csvFile == null) {
            return;
        }
        String row = String.join(",",
                csv(e.timestamp()), csv(e.invoiceNumber()), csv(e.poNumber()),
                csv(e.classification()), csv(e.aiRecommendation()), csv(e.humanDecision()),
                csv(e.reason()), csv(e.user())) + "\n";
        try {
            Files.writeString(csvFile, row, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            System.err.println("Audit CSV append failed: " + ex.getMessage());
        }
    }

    private static String csv(String value) {
        String v = value == null ? "" : value.replace("\"", "\"\"");
        return "\"" + v + "\"";
    }
}
