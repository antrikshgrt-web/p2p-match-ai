package com.p2pmatchai.web;

import com.p2pmatchai.data.MockSapRepository.SampleInvoice;
import com.p2pmatchai.model.Models.AuditEntry;
import com.p2pmatchai.model.Models.Finding;
import com.p2pmatchai.model.Models.GoodsReceipt;
import com.p2pmatchai.model.Models.GrnLine;
import com.p2pmatchai.model.Models.Invoice;
import com.p2pmatchai.model.Models.InvoiceLine;
import com.p2pmatchai.model.Models.LineComparison;
import com.p2pmatchai.model.Models.MatchResult;
import com.p2pmatchai.model.Models.PoLine;
import com.p2pmatchai.model.Models.PurchaseOrder;
import com.p2pmatchai.model.Models.TestCaseResult;

import java.util.List;
import java.util.function.Function;

/** Hand-rolled JSON writer so the prototype needs no third-party libraries. */
public final class Json {

    private Json() {
    }

    /* ---------------- primitives ---------------- */

    public static String str(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    public static String num(double d) {
        double rounded = Math.round(d * 10000.0) / 10000.0;
        if (rounded == Math.rint(rounded) && !Double.isInfinite(rounded)) {
            return String.valueOf((long) rounded);
        }
        return String.valueOf(rounded);
    }

    public static <T> String array(List<T> items, Function<T, String> mapper) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(mapper.apply(items.get(i)));
        }
        return sb.append(']').toString();
    }

    public static String error(String message) {
        return "{\"error\":" + str(message) + "}";
    }

    /* ---------------- domain objects ---------------- */

    public static String finding(Finding f) {
        return "{\"code\":" + str(f.code())
                + ",\"severity\":" + str(f.severity())
                + ",\"message\":" + str(f.message())
                + ",\"evidence\":" + str(f.evidence()) + "}";
    }

    public static String lineComparison(LineComparison c) {
        return "{\"materialCode\":" + str(c.materialCode())
                + ",\"description\":" + str(c.description())
                + ",\"uom\":" + str(c.uom())
                + ",\"invoiceQty\":" + num(c.invoiceQty())
                + ",\"grnQty\":" + num(c.grnQty())
                + ",\"poQty\":" + num(c.poQty())
                + ",\"invoiceUnitPrice\":" + num(c.invoiceUnitPrice())
                + ",\"poUnitPrice\":" + num(c.poUnitPrice())
                + ",\"qtyVariancePct\":" + num(c.qtyVariancePct())
                + ",\"priceVariancePct\":" + num(c.priceVariancePct())
                + ",\"status\":" + str(c.status()) + "}";
    }

    public static String invoiceLine(InvoiceLine l) {
        return "{\"materialCode\":" + str(l.materialCode())
                + ",\"description\":" + str(l.description())
                + ",\"uom\":" + str(l.uom())
                + ",\"qty\":" + num(l.qty())
                + ",\"unitPrice\":" + num(l.unitPrice())
                + ",\"amount\":" + num(l.amount()) + "}";
    }

    public static String invoice(Invoice i) {
        return "{\"invoiceNumber\":" + str(i.invoiceNumber())
                + ",\"invoiceDate\":" + str(i.invoiceDate())
                + ",\"vendorName\":" + str(i.vendorName())
                + ",\"vendorGstin\":" + str(i.vendorGstin())
                + ",\"poReference\":" + str(i.poReference())
                + ",\"currency\":" + str(i.currency())
                + ",\"taxRatePct\":" + num(i.taxRatePct())
                + ",\"netAmount\":" + num(i.netAmount())
                + ",\"grossAmount\":" + num(i.grossAmount())
                + ",\"declaredTotal\":" + num(i.declaredTotal())
                + ",\"extractionConfidence\":" + num(i.extractionConfidence())
                + ",\"sourceNote\":" + str(i.sourceNote())
                + ",\"lines\":" + array(i.lines(), Json::invoiceLine) + "}";
    }

    public static String matchResult(MatchResult r) {
        return "{\"invoiceNumber\":" + str(r.invoiceNumber())
                + ",\"poNumber\":" + str(r.poNumber())
                + ",\"grnNumber\":" + str(r.grnNumber())
                + ",\"classification\":" + str(r.classification())
                + ",\"recommendation\":" + str(r.recommendation())
                + ",\"invoiceTotal\":" + num(r.invoiceTotal())
                + ",\"expectedTotal\":" + num(r.expectedTotal())
                + ",\"totalVariance\":" + num(r.totalVariance())
                + ",\"extractionConfidence\":" + num(r.extractionConfidence())
                + ",\"narrative\":" + str(r.narrative())
                + ",\"findings\":" + array(r.findings(), Json::finding)
                + ",\"lines\":" + array(r.lines(), Json::lineComparison) + "}";
    }

    public static String poLine(PoLine l) {
        return "{\"lineNo\":" + l.lineNo()
                + ",\"materialCode\":" + str(l.materialCode())
                + ",\"description\":" + str(l.description())
                + ",\"uom\":" + str(l.uom())
                + ",\"qty\":" + num(l.qty())
                + ",\"unitPrice\":" + num(l.unitPrice()) + "}";
    }

    public static String purchaseOrder(PurchaseOrder p) {
        return "{\"poNumber\":" + str(p.poNumber())
                + ",\"vendorCode\":" + str(p.vendorCode())
                + ",\"currency\":" + str(p.currency())
                + ",\"taxRatePct\":" + num(p.taxRatePct())
                + ",\"lines\":" + array(p.lines(), Json::poLine) + "}";
    }

    public static String grnLine(GrnLine l) {
        return "{\"poLineNo\":" + l.poLineNo()
                + ",\"materialCode\":" + str(l.materialCode())
                + ",\"uom\":" + str(l.uom())
                + ",\"qtyReceived\":" + num(l.qtyReceived()) + "}";
    }

    public static String goodsReceipt(GoodsReceipt g) {
        return "{\"grnNumber\":" + str(g.grnNumber())
                + ",\"poNumber\":" + str(g.poNumber())
                + ",\"postingDate\":" + str(g.postingDate())
                + ",\"lines\":" + array(g.lines(), Json::grnLine) + "}";
    }

    public static String sample(SampleInvoice s) {
        return "{\"id\":" + str(s.id())
                + ",\"label\":" + str(s.label())
                + ",\"category\":" + str(s.category())
                + ",\"expectedClassification\":" + str(s.expectedClassification())
                + ",\"expectedFindingCode\":" + str(s.expectedFindingCode())
                + ",\"rawText\":" + str(s.rawText()) + "}";
    }

    public static String auditEntry(AuditEntry e) {
        return "{\"timestamp\":" + str(e.timestamp())
                + ",\"invoiceNumber\":" + str(e.invoiceNumber())
                + ",\"poNumber\":" + str(e.poNumber())
                + ",\"classification\":" + str(e.classification())
                + ",\"aiRecommendation\":" + str(e.aiRecommendation())
                + ",\"humanDecision\":" + str(e.humanDecision())
                + ",\"reason\":" + str(e.reason())
                + ",\"user\":" + str(e.user()) + "}";
    }

    public static String testCaseResult(TestCaseResult t) {
        return "{\"number\":" + t.number()
                + ",\"category\":" + str(t.category())
                + ",\"description\":" + str(t.description())
                + ",\"expectedClassification\":" + str(t.expectedClassification())
                + ",\"actualClassification\":" + str(t.actualClassification())
                + ",\"expectedFindingCode\":" + str(t.expectedFindingCode())
                + ",\"findingRaised\":" + t.findingRaised()
                + ",\"passed\":" + t.passed() + "}";
    }
}
