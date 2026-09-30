package com.p2pmatchai.model;

import java.util.List;

/**
 * All domain records for P2P Match AI.
 * Kept in one holder class so the prototype stays easy to read and compile.
 */
public final class Models {

    private Models() {
    }

    /* ---------------- SAP-side master / transaction data (synthetic) ---------------- */

    /** Vendor master record (simulates SAP XK03 / vendor master). */
    public record Vendor(String code, String name, String gstin, String state) {
    }

    /** One line of a Purchase Order (simulates ME23N line item). */
    public record PoLine(int lineNo, String materialCode, String description,
                         String uom, double qty, double unitPrice) {
        public double amount() {
            return qty * unitPrice;
        }
    }

    /** Purchase Order header (simulates ME23N). */
    public record PurchaseOrder(String poNumber, String vendorCode, String currency,
                                double taxRatePct, List<PoLine> lines) {
    }

    /** One line of a Goods Receipt Note (simulates MIGO line item). */
    public record GrnLine(int poLineNo, String materialCode, String uom, double qtyReceived) {
    }

    /** Goods Receipt Note header (simulates MIGO document). */
    public record GoodsReceipt(String grnNumber, String poNumber, String postingDate,
                               List<GrnLine> lines) {
    }

    /* ---------------- Invoice side (extracted by the AI layer) ---------------- */

    public record InvoiceLine(String materialCode, String description,
                              String uom, double qty, double unitPrice) {
        public double amount() {
            return qty * unitPrice;
        }
    }

    /**
     * A vendor invoice after extraction.
     *
     * @param extractionConfidence 0.0 - 1.0, simulates OCR / LLM extraction confidence
     */
    public record Invoice(String invoiceNumber, String invoiceDate, String vendorName,
                          String vendorGstin, String poReference, String currency,
                          double taxRatePct, List<InvoiceLine> lines,
                          double declaredTotal, double extractionConfidence,
                          String sourceNote) {

        public double netAmount() {
            double sum = 0.0;
            for (InvoiceLine l : lines) {
                sum += l.amount();
            }
            return sum;
        }

        public double grossAmount() {
            return netAmount() * (1.0 + taxRatePct / 100.0);
        }
    }

    /* ---------------- Matching output ---------------- */

    /**
     * A single audit-ready finding produced by the matching engine.
     *
     * @param severity BLOCKER | WARNING | INFO
     * @param evidence field-level reference (invoice line / PO line / GRN line)
     */
    public record Finding(String code, String severity, String message, String evidence) {
    }

    /** Line-by-line 3-way comparison row shown in the dashboard. */
    public record LineComparison(String materialCode, String description, String uom,
                                 double invoiceQty, double grnQty, double poQty,
                                 double invoiceUnitPrice, double poUnitPrice,
                                 double qtyVariancePct, double priceVariancePct,
                                 String status) {
    }

    /**
     * Full verification result for one invoice.
     *
     * @param classification CLEAN_MATCH | EXCEPTION
     * @param recommendation CONFIRM | HOLD | ESCALATE
     */
    public record MatchResult(String invoiceNumber, String poNumber, String grnNumber,
                              String classification, String recommendation,
                              List<Finding> findings, List<LineComparison> lines,
                              double invoiceTotal, double expectedTotal, double totalVariance,
                              double extractionConfidence, String narrative) {
    }

    /** One row of the immutable audit trail. */
    public record AuditEntry(String timestamp, String invoiceNumber, String poNumber,
                             String classification, String aiRecommendation,
                             String humanDecision, String reason, String user) {
    }

    /** One row of the automated evaluation harness (Annexure E). */
    public record TestCaseResult(int number, String category, String description,
                                 String expectedClassification, String actualClassification,
                                 String expectedFindingCode, boolean findingRaised,
                                 boolean passed) {
    }
}
