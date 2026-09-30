package com.p2pmatchai.engine;

import com.p2pmatchai.data.MockSapRepository;
import com.p2pmatchai.model.Models.Finding;
import com.p2pmatchai.model.Models.GoodsReceipt;
import com.p2pmatchai.model.Models.GrnLine;
import com.p2pmatchai.model.Models.Invoice;
import com.p2pmatchai.model.Models.InvoiceLine;
import com.p2pmatchai.model.Models.LineComparison;
import com.p2pmatchai.model.Models.MatchResult;
import com.p2pmatchai.model.Models.PoLine;
import com.p2pmatchai.model.Models.PurchaseOrder;
import com.p2pmatchai.model.Models.Vendor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Matching and Orchestration Engine (Annexure C).
 *
 * Deliberately deterministic and rule-based: every tolerance is declared as a
 * constant below and every finding carries field-level evidence, so a reviewer
 * or auditor can reproduce the verdict by hand. The LLM layer never decides the
 * classification — it only explains it (see {@link NarrativeService}).
 */
public final class MatchingEngine {

    /** Disclosed tolerance thresholds (Section 12: bias and consistency in matching). */
    public static final double QTY_TOLERANCE_PCT = 1.0;
    public static final double PRICE_TOLERANCE_PCT = 2.0;
    public static final double AMOUNT_ROUNDING_TOLERANCE = 5.00;

    private static final String BLOCKER = "BLOCKER";
    private static final String WARNING = "WARNING";
    private static final String INFO = "INFO";

    private final MockSapRepository repo;
    private final NarrativeService narrator;

    public MatchingEngine(MockSapRepository repo, NarrativeService narrator) {
        this.repo = repo;
        this.narrator = narrator;
    }

    /**
     * Runs the full three-way match.
     *
     * @param processedLedger invoice numbers already seen in this session, used for
     *                        duplicate detection. Mutated on a successful pass.
     */
    public MatchResult verify(Invoice inv, Set<String> processedLedger) {
        List<Finding> findings = new ArrayList<>();
        List<LineComparison> comparisons = new ArrayList<>();

        /* --- 0. Extraction quality and governance checks ------------------ */

        if (!inv.sourceNote().isBlank()) {
            findings.add(new Finding("NON_INVOICE_FIELD_REJECTED", INFO,
                    "Fields outside the permitted invoice schema were found in the document and "
                            + "were discarded before matching. They were not stored or sent onward.",
                    inv.sourceNote()));
        }

        if (ExtractionService.isLowConfidence(inv)) {
            findings.add(new Finding("LOW_CONFIDENCE_EXTRACTION", BLOCKER,
                    "Extraction confidence " + pct(inv.extractionConfidence() * 100)
                            + " is below the " + pct(ExtractionService.minConfidence() * 100)
                            + " threshold. Field values are not reliable enough to match automatically.",
                    "Document-level confidence score"));
        }

        /* --- 1. Duplicate detection --------------------------------------- */

        String key = inv.invoiceNumber().trim().toUpperCase();
        boolean duplicate = processedLedger.contains(key);
        if (duplicate) {
            findings.add(new Finding("DUPLICATE_INVOICE", BLOCKER,
                    "Invoice number " + inv.invoiceNumber()
                            + " has already been verified in this session.",
                    "Invoice header / processed-invoice ledger"));
        }

        /* --- 2. PO reference ---------------------------------------------- */

        if (inv.poReference() == null || inv.poReference().isBlank()) {
            findings.add(new Finding("MISSING_PO_REFERENCE", BLOCKER,
                    "No purchase order reference is present on the invoice. The agent does not "
                            + "guess the PO; the invoice is routed to the AP executive.",
                    "Invoice header"));
            return finish(inv, null, null, findings, comparisons, 0.0, processedLedger, duplicate);
        }

        PurchaseOrder po = repo.purchaseOrder(inv.poReference());
        if (po == null) {
            findings.add(new Finding("PO_NOT_FOUND", BLOCKER,
                    "Purchase order " + inv.poReference() + " does not exist in the PO extract.",
                    "Invoice header PO reference"));
            return finish(inv, null, null, findings, comparisons, 0.0, processedLedger, duplicate);
        }

        /* --- 3. Vendor master / GST checks -------------------------------- */

        Vendor vendor = repo.vendor(po.vendorCode());
        if (vendor != null) {
            if (!normalise(vendor.gstin()).equals(normalise(inv.vendorGstin()))) {
                findings.add(new Finding("GST_MISMATCH", BLOCKER,
                        "GSTIN on the invoice (" + display(inv.vendorGstin())
                                + ") does not match the vendor master GSTIN (" + vendor.gstin() + ").",
                        "Invoice header GSTIN vs vendor master " + vendor.code()));
            }
            if (!similarName(vendor.name(), inv.vendorName())) {
                findings.add(new Finding("VENDOR_NAME_MISMATCH", WARNING,
                        "Vendor name on the invoice (" + display(inv.vendorName())
                                + ") differs from the PO vendor (" + vendor.name() + ").",
                        "Invoice header vendor vs PO " + po.poNumber()));
            }
        }

        if (inv.taxRatePct() > 0 && Math.abs(inv.taxRatePct() - po.taxRatePct()) > 0.001) {
            findings.add(new Finding("TAX_RATE_MISMATCH", WARNING,
                    "Invoice tax rate " + pct(inv.taxRatePct()) + " differs from the PO tax rate "
                            + pct(po.taxRatePct()) + ".",
                    "Invoice header vs PO " + po.poNumber()));
        }

        /* --- 4. GRN retrieval --------------------------------------------- */

        GoodsReceipt grn = repo.goodsReceiptForPo(po.poNumber());
        if (grn == null) {
            findings.add(new Finding("GRN_NOT_FOUND", BLOCKER,
                    "No goods receipt has been posted against PO " + po.poNumber()
                            + ". Invoice cannot be three-way matched.",
                    "MIGO extract for PO " + po.poNumber()));
            return finish(inv, po, null, findings, comparisons, 0.0, processedLedger, duplicate);
        }

        /* --- 5. Line-by-line three-way match ------------------------------ */

        double expectedNet = 0.0;

        for (InvoiceLine line : inv.lines()) {
            PoLine poLine = findPoLine(po, line.materialCode());
            if (poLine == null) {
                findings.add(new Finding("LINE_NOT_IN_PO", BLOCKER,
                        "Material " + line.materialCode()
                                + " is invoiced but does not appear on PO " + po.poNumber() + ".",
                        "Invoice line " + line.materialCode()));
                comparisons.add(new LineComparison(line.materialCode(), line.description(), line.uom(),
                        line.qty(), 0, 0, line.unitPrice(), 0, 0, 0, "NOT_IN_PO"));
                expectedNet += line.amount();
                continue;
            }

            GrnLine grnLine = findGrnLine(grn, line.materialCode());
            double grnQty = grnLine == null ? 0.0 : grnLine.qtyReceived();

            if (grnLine == null) {
                findings.add(new Finding("NOT_RECEIVED", BLOCKER,
                        "Material " + line.materialCode() + " is invoiced but no goods receipt "
                                + "line exists for it.",
                        "PO line " + poLine.lineNo() + " / GRN " + grn.grnNumber()));
                comparisons.add(new LineComparison(line.materialCode(), line.description(), line.uom(),
                        line.qty(), 0, poLine.qty(), line.unitPrice(), poLine.unitPrice(),
                        0, 0, "NOT_RECEIVED"));
                continue;
            }

            // Unit of measure must agree before any variance arithmetic is meaningful.
            if (!line.uom().equalsIgnoreCase(poLine.uom())) {
                findings.add(new Finding("UOM_MISMATCH", BLOCKER,
                        "Invoice unit of measure for " + line.materialCode() + " is " + line.uom()
                                + " but the PO and GRN use " + poLine.uom()
                                + ". Quantity variance was not computed, to avoid a false result.",
                        "Invoice line " + line.materialCode() + " / PO line " + poLine.lineNo()));
                comparisons.add(new LineComparison(line.materialCode(), line.description(), line.uom(),
                        line.qty(), grnQty, poLine.qty(), line.unitPrice(), poLine.unitPrice(),
                        0, 0, "UOM_MISMATCH"));
                expectedNet += grnQty * poLine.unitPrice();
                continue;
            }

            double qtyVariancePct = grnQty == 0 ? 100.0 : ((line.qty() - grnQty) / grnQty) * 100.0;
            double priceVariancePct = poLine.unitPrice() == 0 ? 0.0
                    : ((line.unitPrice() - poLine.unitPrice()) / poLine.unitPrice()) * 100.0;

            String status = "MATCHED";

            if (grnQty + 1e-9 < poLine.qty()) {
                findings.add(new Finding("PARTIAL_GRN", BLOCKER,
                        "Only " + qty(grnQty) + " of " + qty(poLine.qty()) + " " + poLine.uom()
                                + " of " + line.materialCode()
                                + " has been received. A clean match is withheld until the balance "
                                + "is received or the PO is short-closed.",
                        "GRN " + grn.grnNumber() + " line " + grnLine.poLineNo()
                                + " vs PO line " + poLine.lineNo()));
                status = "PARTIAL_GRN";
            }

            if (Math.abs(qtyVariancePct) > QTY_TOLERANCE_PCT) {
                findings.add(new Finding("QTY_VARIANCE", BLOCKER,
                        "Invoiced quantity for " + line.materialCode() + " is " + qty(line.qty())
                                + " " + line.uom() + " against " + qty(grnQty)
                                + " received — a variance of " + pct(qtyVariancePct)
                                + " (tolerance " + pct(QTY_TOLERANCE_PCT) + ").",
                        "Invoice line " + line.materialCode() + " vs GRN " + grn.grnNumber()));
                if (status.equals("MATCHED")) {
                    status = "QTY_VARIANCE";
                }
            }

            if (Math.abs(priceVariancePct) > PRICE_TOLERANCE_PCT) {
                findings.add(new Finding("PRICE_VARIANCE", BLOCKER,
                        "Invoiced unit price for " + line.materialCode() + " is "
                                + money(line.unitPrice()) + " against the PO price "
                                + money(poLine.unitPrice()) + " — a variance of "
                                + pct(priceVariancePct) + " (tolerance "
                                + pct(PRICE_TOLERANCE_PCT) + ").",
                        "Invoice line " + line.materialCode() + " vs PO line " + poLine.lineNo()));
                if (status.equals("MATCHED")) {
                    status = "PRICE_VARIANCE";
                }
            }

            comparisons.add(new LineComparison(line.materialCode(), line.description(), line.uom(),
                    line.qty(), grnQty, poLine.qty(), line.unitPrice(), poLine.unitPrice(),
                    qtyVariancePct, priceVariancePct, status));

            expectedNet += grnQty * poLine.unitPrice();
        }

        /* --- 6. Header-level amount check --------------------------------- */

        double expectedTotal = expectedNet * (1.0 + po.taxRatePct() / 100.0);
        double invoiceTotal = inv.grossAmount();
        double variance = invoiceTotal - expectedTotal;

        if (Math.abs(variance) > AMOUNT_ROUNDING_TOLERANCE && comparisons.stream()
                .allMatch(c -> c.status().equals("MATCHED"))) {
            findings.add(new Finding("TOTAL_VARIANCE", WARNING,
                    "Invoice total " + money(invoiceTotal) + " differs from the expected payable "
                            + money(expectedTotal) + " by " + money(variance) + ".",
                    "Invoice header total vs GRN-priced total"));
        }

        if (inv.declaredTotal() > 0
                && Math.abs(inv.declaredTotal() - invoiceTotal) > AMOUNT_ROUNDING_TOLERANCE) {
            findings.add(new Finding("DECLARED_TOTAL_MISMATCH", WARNING,
                    "The total printed on the invoice (" + money(inv.declaredTotal())
                            + ") does not equal the sum of its own line items ("
                            + money(invoiceTotal) + ").",
                    "Invoice header total vs invoice line items"));
        }

        return finish(inv, po, grn, findings, comparisons, expectedTotal, processedLedger, duplicate);
    }

    /**
     * GRN-posting pre-check (Annexure B, step 1): compares a goods receipt against its
     * PO at the moment of posting, before any invoice arrives.
     */
    public List<Finding> checkGrnAgainstPo(String poNumber) {
        List<Finding> findings = new ArrayList<>();
        PurchaseOrder po = repo.purchaseOrder(poNumber);
        if (po == null) {
            findings.add(new Finding("PO_NOT_FOUND", BLOCKER,
                    "Purchase order " + poNumber + " not found.", "PO extract"));
            return findings;
        }
        GoodsReceipt grn = repo.goodsReceiptForPo(poNumber);
        if (grn == null) {
            findings.add(new Finding("GRN_NOT_FOUND", INFO,
                    "No goods receipt posted yet against PO " + poNumber + ".",
                    "MIGO extract"));
            return findings;
        }
        for (PoLine poLine : po.lines()) {
            GrnLine grnLine = findGrnLine(grn, poLine.materialCode());
            if (grnLine == null) {
                findings.add(new Finding("NOT_RECEIVED", WARNING,
                        "PO line " + poLine.lineNo() + " (" + poLine.materialCode()
                                + ") has no goods receipt line.",
                        "PO " + poNumber + " line " + poLine.lineNo()));
                continue;
            }
            if (!grnLine.uom().equalsIgnoreCase(poLine.uom())) {
                findings.add(new Finding("UOM_MISMATCH", WARNING,
                        "GRN unit of measure " + grnLine.uom() + " differs from PO unit "
                                + poLine.uom() + " for " + poLine.materialCode() + ".",
                        "GRN " + grn.grnNumber() + " vs PO line " + poLine.lineNo()));
                continue;
            }
            if (grnLine.qtyReceived() + 1e-9 < poLine.qty()) {
                findings.add(new Finding("PARTIAL_GRN", WARNING,
                        "Short receipt on " + poLine.materialCode() + ": " + qty(grnLine.qtyReceived())
                                + " of " + qty(poLine.qty()) + " " + poLine.uom() + " received.",
                        "GRN " + grn.grnNumber() + " vs PO line " + poLine.lineNo()));
            } else if (grnLine.qtyReceived() > poLine.qty() + 1e-9) {
                findings.add(new Finding("OVER_RECEIPT", WARNING,
                        "Over receipt on " + poLine.materialCode() + ": " + qty(grnLine.qtyReceived())
                                + " received against a PO quantity of " + qty(poLine.qty()) + ".",
                        "GRN " + grn.grnNumber() + " vs PO line " + poLine.lineNo()));
            }
        }
        return findings;
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                             */
    /* ------------------------------------------------------------------ */

    private MatchResult finish(Invoice inv, PurchaseOrder po, GoodsReceipt grn,
                               List<Finding> findings, List<LineComparison> comparisons,
                               double expectedTotal, Set<String> processedLedger,
                               boolean duplicate) {

        boolean exception = findings.stream()
                .anyMatch(f -> f.severity().equals(BLOCKER) || f.severity().equals(WARNING));

        String classification = exception ? "EXCEPTION" : "CLEAN_MATCH";
        String recommendation = recommend(findings, exception);

        if (!duplicate) {
            processedLedger.add(inv.invoiceNumber().trim().toUpperCase());
        }

        double invoiceTotal = inv.grossAmount();
        String narrative = narrator.explain(inv, classification, recommendation, findings);

        return new MatchResult(
                inv.invoiceNumber(),
                po == null ? "" : po.poNumber(),
                grn == null ? "" : grn.grnNumber(),
                classification,
                recommendation,
                findings,
                comparisons,
                invoiceTotal,
                expectedTotal,
                invoiceTotal - expectedTotal,
                inv.extractionConfidence(),
                narrative);
    }

    private String recommend(List<Finding> findings, boolean exception) {
        if (!exception) {
            return "CONFIRM";
        }
        List<String> escalationCodes = List.of("DUPLICATE_INVOICE", "GST_MISMATCH",
                "MISSING_PO_REFERENCE", "PO_NOT_FOUND", "LINE_NOT_IN_PO");
        for (Finding f : findings) {
            if (escalationCodes.contains(f.code())) {
                return "ESCALATE";
            }
        }
        return "HOLD";
    }

    private PoLine findPoLine(PurchaseOrder po, String materialCode) {
        for (PoLine l : po.lines()) {
            if (l.materialCode().equalsIgnoreCase(materialCode)) {
                return l;
            }
        }
        return null;
    }

    private GrnLine findGrnLine(GoodsReceipt grn, String materialCode) {
        for (GrnLine l : grn.lines()) {
            if (l.materialCode().equalsIgnoreCase(materialCode)) {
                return l;
            }
        }
        return null;
    }

    private static String normalise(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "").toUpperCase();
    }

    /** Loose vendor-name comparison: ignores case, punctuation and suffixes. */
    private static boolean similarName(String a, String b) {
        String x = simplify(a);
        String y = simplify(b);
        if (x.isEmpty() || y.isEmpty()) {
            return true;
        }
        return x.contains(y) || y.contains(x);
    }

    private static String simplify(String s) {
        if (s == null) {
            return "";
        }
        return s.toUpperCase()
                .replaceAll("PVT|PRIVATE|LTD|LIMITED|LLP|INDUSTRIES|CO\\.|CO\\b", "")
                .replaceAll("[^A-Z0-9]", "");
    }

    private static String pct(double v) {
        return String.format("%.2f%%", v);
    }

    private static String qty(double v) {
        return String.format("%,.2f", v);
    }

    private static String money(double v) {
        return String.format("INR %,.2f", v);
    }
}
