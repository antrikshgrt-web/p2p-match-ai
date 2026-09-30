package com.p2pmatchai.engine;

import com.p2pmatchai.model.Models.Invoice;
import com.p2pmatchai.model.Models.InvoiceLine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Document Extraction Layer (Annexure C).
 *
 * In the full design this layer is an LLM call that reads a PDF/scan and returns
 * structured JSON. For an offline, reproducible, zero-cost prototype it parses the
 * text layer of the document instead. The contract is identical either way:
 * raw document in, a structured {@link Invoice} plus a confidence score out.
 *
 * Governance behaviour (Test case 11): only whitelisted invoice fields are accepted.
 * Anything else found in the document is rejected and reported, never stored or
 * forwarded, so confidential material cannot leak into the matching pipeline.
 */
public final class ExtractionService {

    /** Only these header fields are accepted from a document. */
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "invoice no", "invoice number", "invoice date", "vendor", "vendor name",
            "gstin", "gst no", "po reference", "po no", "po number", "currency",
            "tax rate", "total", "scan confidence", "line", "tax invoice");

    private static final double MIN_CONFIDENCE = 0.70;

    public Invoice extract(String rawDocument) {
        String invoiceNumber = null;
        String invoiceDate = "";
        String vendorName = "";
        String gstin = "";
        String poReference = "";
        String currency = "INR";
        double taxRatePct = 0.0;
        double declaredTotal = 0.0;
        double confidence = 0.95;

        List<InvoiceLine> lines = new ArrayList<>();
        List<String> rejected = new ArrayList<>();

        for (String rawLine : rawDocument.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                // Headings such as "TAX INVOICE" carry no field data.
                if (!ALLOWED_FIELDS.contains(line.toLowerCase())) {
                    rejected.add(line);
                }
                continue;
            }

            String key = line.substring(0, colon).trim().toLowerCase();
            String value = line.substring(colon + 1).trim();

            if (!ALLOWED_FIELDS.contains(key)) {
                rejected.add(line.substring(0, colon).trim());
                continue;
            }

            switch (key) {
                case "invoice no", "invoice number" -> invoiceNumber = value;
                case "invoice date" -> invoiceDate = value;
                case "vendor", "vendor name" -> vendorName = value;
                case "gstin", "gst no" -> gstin = value;
                case "po reference", "po no", "po number" -> poReference = normalisePo(value);
                case "currency" -> currency = value;
                case "tax rate" -> taxRatePct = toDouble(value, 0.0);
                case "total" -> declaredTotal = toDouble(value, 0.0);
                case "scan confidence" -> confidence = toDouble(value, 0.95);
                case "line" -> {
                    InvoiceLine parsed = parseLine(value);
                    if (parsed == null) {
                        rejected.add("Unreadable line item");
                    } else {
                        lines.add(parsed);
                    }
                }
                default -> rejected.add(key);
            }
        }

        // Missing mandatory fields reduce confidence — the agent must never invent them.
        if (invoiceNumber == null || invoiceNumber.isBlank()) {
            invoiceNumber = "UNKNOWN";
            confidence -= 0.20;
        }
        if (lines.isEmpty()) {
            confidence -= 0.30;
        }
        if (gstin.isBlank()) {
            confidence -= 0.10;
        }
        confidence = Math.max(0.0, Math.min(1.0, confidence));

        String note = rejected.isEmpty()
                ? ""
                : "Rejected non-invoice fields: " + String.join("; ", rejected);

        return new Invoice(invoiceNumber, invoiceDate, vendorName, gstin, poReference,
                currency, taxRatePct, lines, declaredTotal, confidence, note);
    }

    public static boolean isLowConfidence(Invoice invoice) {
        return invoice.extractionConfidence() < MIN_CONFIDENCE;
    }

    public static double minConfidence() {
        return MIN_CONFIDENCE;
    }

    /** Parses "MAT-1001 | Cotton Yarn 40s | KG | 1200 | 145.50". */
    private InvoiceLine parseLine(String value) {
        String[] parts = value.split("\\|");
        if (parts.length < 5) {
            return null;
        }
        String material = parts[0].trim();
        String description = parts[1].trim();
        String uom = parts[2].trim().toUpperCase();
        double qty = toDouble(parts[3], -1);
        double price = toDouble(parts[4], -1);
        if (material.isEmpty() || qty < 0 || price < 0) {
            return null;
        }
        return new InvoiceLine(material, description, uom, qty, price);
    }

    /** Normalises "PO 4500123456", "PO#4500123456" and "4500123456" to "4500123456". */
    private static String normalisePo(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        String cleaned = sb.toString();
        if (cleaned.length() > 2 && cleaned.toUpperCase().startsWith("PO")
                && cleaned.substring(2).chars().allMatch(Character::isDigit)) {
            cleaned = cleaned.substring(2);
        }
        return cleaned;
    }

    private static double toDouble(String value, double fallback) {
        if (value == null) {
            return fallback;
        }
        String cleaned = value.replaceAll("[^0-9.\\-]", "");
        if (cleaned.isEmpty() || cleaned.equals("-") || cleaned.equals(".")) {
            return fallback;
        }
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
