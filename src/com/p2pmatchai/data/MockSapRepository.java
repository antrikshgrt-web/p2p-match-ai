package com.p2pmatchai.data;

import com.p2pmatchai.model.Models.GoodsReceipt;
import com.p2pmatchai.model.Models.GrnLine;
import com.p2pmatchai.model.Models.PoLine;
import com.p2pmatchai.model.Models.PurchaseOrder;
import com.p2pmatchai.model.Models.Vendor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stand-in for SAP S/4HANA reference data.
 *
 * IMPORTANT (Data Plan, Section 9 of the proposal): every vendor name, GSTIN,
 * material code and amount below is FICTIONAL. No real organisational data is
 * used anywhere in this prototype. In a production build this class would be
 * replaced by a read-only extract of ME23N (PO) and MIGO (GRN) data.
 */
public final class MockSapRepository {

    private final Map<String, Vendor> vendors = new LinkedHashMap<>();
    private final Map<String, PurchaseOrder> purchaseOrders = new LinkedHashMap<>();
    private final Map<String, GoodsReceipt> goodsReceipts = new LinkedHashMap<>();
    private final List<SampleInvoice> samples = new ArrayList<>();

    /** A synthetic invoice document as it would arrive from OCR / a PDF reader. */
    public record SampleInvoice(String id, String label, String category,
                                String expectedClassification, String expectedFindingCode,
                                String rawText) {
    }

    public MockSapRepository() {
        loadVendors();
        loadPurchaseOrders();
        loadGoodsReceipts();
        loadSampleInvoices();
    }

    /* ------------------------------------------------------------------ */
    /* Lookups                                                             */
    /* ------------------------------------------------------------------ */

    public Vendor vendor(String code) {
        return vendors.get(code);
    }

    public PurchaseOrder purchaseOrder(String poNumber) {
        if (poNumber == null) {
            return null;
        }
        return purchaseOrders.get(poNumber.trim());
    }

    /** Finds the GRN posted against a given PO (one GRN per PO in this prototype). */
    public GoodsReceipt goodsReceiptForPo(String poNumber) {
        for (GoodsReceipt grn : goodsReceipts.values()) {
            if (grn.poNumber().equals(poNumber)) {
                return grn;
            }
        }
        return null;
    }

    public List<Vendor> allVendors() {
        return new ArrayList<>(vendors.values());
    }

    public List<PurchaseOrder> allPurchaseOrders() {
        return new ArrayList<>(purchaseOrders.values());
    }

    public List<GoodsReceipt> allGoodsReceipts() {
        return new ArrayList<>(goodsReceipts.values());
    }

    public List<SampleInvoice> allSamples() {
        return new ArrayList<>(samples);
    }

    public SampleInvoice sample(String id) {
        for (SampleInvoice s : samples) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        return null;
    }

    /* ------------------------------------------------------------------ */
    /* Seed data                                                           */
    /* ------------------------------------------------------------------ */

    private void loadVendors() {
        put(new Vendor("V-1001", "ABC Textiles Pvt. Ltd.", "08ABCDE1234F1Z5", "Rajasthan"));
        put(new Vendor("V-1002", "Kota Chemicals & Solvents", "08KOTCH5678G2Z1", "Rajasthan"));
        put(new Vendor("V-1003", "Shree Packaging Industries", "24SHRPK9012H3Z9", "Gujarat"));
    }

    private void put(Vendor v) {
        vendors.put(v.code(), v);
    }

    private void loadPurchaseOrders() {
        purchaseOrders.put("4500123456", new PurchaseOrder("4500123456", "V-1001", "INR", 18.0,
                List.of(
                        new PoLine(10, "MAT-1001", "Cotton Yarn 40s", "KG", 1200, 145.50),
                        new PoLine(20, "MAT-1002", "Viscose Staple Fibre", "KG", 500, 98.00))));

        purchaseOrders.put("4500123457", new PurchaseOrder("4500123457", "V-1002", "INR", 18.0,
                List.of(new PoLine(10, "MAT-2001", "Caustic Soda Lye", "KG", 5000, 32.40))));

        purchaseOrders.put("4500123458", new PurchaseOrder("4500123458", "V-1003", "INR", 12.0,
                List.of(new PoLine(10, "MAT-3001", "Corrugated Box 5-ply", "PCS", 2000, 24.00))));

        purchaseOrders.put("4500123459", new PurchaseOrder("4500123459", "V-1001", "INR", 18.0,
                List.of(new PoLine(10, "MAT-1003", "Polyester Tow", "KG", 800, 112.25))));
    }

    private void loadGoodsReceipts() {
        goodsReceipts.put("5000987001", new GoodsReceipt("5000987001", "4500123456", "2026-08-10",
                List.of(new GrnLine(10, "MAT-1001", "KG", 1200),
                        new GrnLine(20, "MAT-1002", "KG", 500))));

        goodsReceipts.put("5000987002", new GoodsReceipt("5000987002", "4500123457", "2026-08-11",
                List.of(new GrnLine(10, "MAT-2001", "KG", 5000))));

        // Partial delivery: only 1200 of 2000 PCS received.
        goodsReceipts.put("5000987003", new GoodsReceipt("5000987003", "4500123458", "2026-08-12",
                List.of(new GrnLine(10, "MAT-3001", "PCS", 1200))));

        goodsReceipts.put("5000987004", new GoodsReceipt("5000987004", "4500123459", "2026-08-13",
                List.of(new GrnLine(10, "MAT-1003", "KG", 800))));
    }

    private void loadSampleInvoices() {
        samples.add(new SampleInvoice("TC01", "Clean single-line invoice", "Normal",
                "CLEAN_MATCH", null,
                """
                TAX INVOICE
                Invoice No: INV-2026-0001
                Invoice Date: 2026-08-14
                Vendor: Kota Chemicals & Solvents
                GSTIN: 08KOTCH5678G2Z1
                PO Reference: 4500123457
                Currency: INR
                Tax Rate: 18
                Line: MAT-2001 | Caustic Soda Lye | KG | 5000 | 32.40
                Total: 191160.00
                Scan Confidence: 0.97
                """));

        samples.add(new SampleInvoice("TC02", "Clean multi-line invoice", "Normal",
                "CLEAN_MATCH", null,
                """
                TAX INVOICE
                Invoice No: INV-2026-0002
                Invoice Date: 2026-08-14
                Vendor: ABC Textiles Pvt. Ltd.
                GSTIN: 08ABCDE1234F1Z5
                PO Reference: 4500123456
                Currency: INR
                Tax Rate: 18
                Line: MAT-1001 | Cotton Yarn 40s | KG | 1200 | 145.50
                Line: MAT-1002 | Viscose Staple Fibre | KG | 500 | 98.00
                Total: 263848.00
                Scan Confidence: 0.96
                """));

        samples.add(new SampleInvoice("TC03", "GRN quantity equals PO quantity", "Normal",
                "CLEAN_MATCH", null,
                """
                TAX INVOICE
                Invoice No: INV-2026-0003
                Invoice Date: 2026-08-15
                Vendor: ABC Textiles Pvt. Ltd.
                GSTIN: 08ABCDE1234F1Z5
                PO Reference: 4500123459
                Currency: INR
                Tax Rate: 18
                Line: MAT-1003 | Polyester Tow | KG | 800 | 112.25
                Total: 105964.00
                Scan Confidence: 0.98
                """));

        samples.add(new SampleInvoice("TC04", "Partial GRN (part delivery)", "Edge",
                "EXCEPTION", "PARTIAL_GRN",
                """
                TAX INVOICE
                Invoice No: INV-2026-0004
                Invoice Date: 2026-08-15
                Vendor: Shree Packaging Industries
                GSTIN: 24SHRPK9012H3Z9
                PO Reference: 4500123458
                Currency: INR
                Tax Rate: 12
                Line: MAT-3001 | Corrugated Box 5-ply | PCS | 2000 | 24.00
                Total: 53760.00
                Scan Confidence: 0.95
                """));

        samples.add(new SampleInvoice("TC05", "Unit-of-measure mismatch", "Edge",
                "EXCEPTION", "UOM_MISMATCH",
                """
                TAX INVOICE
                Invoice No: INV-2026-0005
                Invoice Date: 2026-08-16
                Vendor: Shree Packaging Industries
                GSTIN: 24SHRPK9012H3Z9
                PO Reference: 4500123458
                Currency: INR
                Tax Rate: 12
                Line: MAT-3001 | Corrugated Box 5-ply | BOX | 100 | 480.00
                Total: 53760.00
                Scan Confidence: 0.94
                """));

        samples.add(new SampleInvoice("TC06", "Rounding difference within tolerance", "Edge",
                "CLEAN_MATCH", null,
                """
                TAX INVOICE
                Invoice No: INV-2026-0006
                Invoice Date: 2026-08-16
                Vendor: Kota Chemicals & Solvents
                GSTIN: 08KOTCH5678G2Z1
                PO Reference: 4500123457
                Currency: INR
                Tax Rate: 18
                Line: MAT-2001 | Caustic Soda Lye | KG | 5000 | 32.4004
                Total: 191162.36
                Scan Confidence: 0.97
                """));

        samples.add(new SampleInvoice("TC07", "Duplicate invoice submission", "Failure/Misuse",
                "EXCEPTION", "DUPLICATE_INVOICE",
                """
                TAX INVOICE
                Invoice No: INV-2026-0001
                Invoice Date: 2026-08-14
                Vendor: Kota Chemicals & Solvents
                GSTIN: 08KOTCH5678G2Z1
                PO Reference: 4500123457
                Currency: INR
                Tax Rate: 18
                Line: MAT-2001 | Caustic Soda Lye | KG | 5000 | 32.40
                Total: 191160.00
                Scan Confidence: 0.97
                """));

        samples.add(new SampleInvoice("TC08", "Missing PO reference", "Failure/Misuse",
                "EXCEPTION", "MISSING_PO_REFERENCE",
                """
                TAX INVOICE
                Invoice No: INV-2026-0008
                Invoice Date: 2026-08-17
                Vendor: ABC Textiles Pvt. Ltd.
                GSTIN: 08ABCDE1234F1Z5
                Currency: INR
                Tax Rate: 18
                Line: MAT-1001 | Cotton Yarn 40s | KG | 1200 | 145.50
                Total: 206028.00
                Scan Confidence: 0.93
                """));

        samples.add(new SampleInvoice("TC09", "Incorrect GST number", "Failure/Misuse",
                "EXCEPTION", "GST_MISMATCH",
                """
                TAX INVOICE
                Invoice No: INV-2026-0009
                Invoice Date: 2026-08-17
                Vendor: ABC Textiles Pvt. Ltd.
                GSTIN: 08ZZZZZ9999Z9Z9
                PO Reference: 4500123456
                Currency: INR
                Tax Rate: 18
                Line: MAT-1001 | Cotton Yarn 40s | KG | 1200 | 145.50
                Line: MAT-1002 | Viscose Staple Fibre | KG | 500 | 98.00
                Total: 263848.00
                Scan Confidence: 0.92
                """));

        samples.add(new SampleInvoice("TC10", "Poor scan quality / illegible", "Failure/Misuse",
                "EXCEPTION", "LOW_CONFIDENCE_EXTRACTION",
                """
                TAX INVOICE
                Invoice No: INV-2026-0010
                Invoice Date: 2026-08-18
                Vendor: ABC Textiles Pvt. Ltd.
                GSTIN: 08ABCDE1234F1Z5
                PO Reference: 4500123456
                Currency: INR
                Tax Rate: 18
                Line: MAT-1001 | Cotton Yarn 40s | KG | 1200 | 145.50
                Line: MAT-1002 | Viscose Staple Fibre | KG | 500 | 98.00
                Total: 263848.00
                Scan Confidence: 0.42
                """));

        samples.add(new SampleInvoice("TC11", "Non-invoice / confidential field submitted", "Risk/Governance",
                "CLEAN_MATCH", "NON_INVOICE_FIELD_REJECTED",
                """
                TAX INVOICE
                Invoice No: INV-2026-0011
                Invoice Date: 2026-08-18
                Vendor: ABC Textiles Pvt. Ltd.
                GSTIN: 08ABCDE1234F1Z5
                PO Reference: 4500123459
                Currency: INR
                Tax Rate: 18
                Employee Payroll Sheet: attached
                Internal Costing Margin: 14 percent
                Line: MAT-1003 | Polyester Tow | KG | 800 | 112.25
                Total: 105964.00
                Scan Confidence: 0.96
                """));

        samples.add(new SampleInvoice("TC12", "Clean match later held by reviewer", "User behaviour",
                "CLEAN_MATCH", null,
                """
                TAX INVOICE
                Invoice No: INV-2026-0012
                Invoice Date: 2026-08-19
                Vendor: Kota Chemicals & Solvents
                GSTIN: 08KOTCH5678G2Z1
                PO Reference: 4500123457
                Currency: INR
                Tax Rate: 18
                Line: MAT-2001 | Caustic Soda Lye | KG | 5000 | 32.40
                Total: 191160.00
                Scan Confidence: 0.99
                """));

        samples.add(new SampleInvoice("TC13", "Unit price above PO tolerance", "Edge",
                "EXCEPTION", "PRICE_VARIANCE",
                """
                TAX INVOICE
                Invoice No: INV-2026-0013
                Invoice Date: 2026-08-19
                Vendor: ABC Textiles Pvt. Ltd.
                GSTIN: 08ABCDE1234F1Z5
                PO Reference: 4500123456
                Currency: INR
                Tax Rate: 18
                Line: MAT-1001 | Cotton Yarn 40s | KG | 1200 | 158.00
                Line: MAT-1002 | Viscose Staple Fibre | KG | 500 | 98.00
                Total: 281548.00
                Scan Confidence: 0.95
                """));
    }
}
