# P2P Match AI — working prototype

An AI-assisted invoice verification and exception-triage agent for a SAP-based
Procure-to-Pay process. Java backend, browser front end, no external libraries.

The agent reads a vendor invoice, extracts the fields, retrieves the matching PO
and GRN, runs a three-way match, classifies the invoice as a clean match or an
exception, explains the exception in plain language, and asks a human to decide.
It never posts to SAP.

All data in this prototype is fictional. No real organisational data is used.

---

## 1. What you need

Only a JDK, version 17 or later. Check with:

```
java -version
javac -version
```

If `javac` is missing you have a JRE, not a JDK — install Temurin or Oracle JDK 21.
There is nothing else to install: no Maven, no Gradle, no Spring, no internet access.

## 2. Run it

**macOS / Linux**

```
cd p2p-match-ai
chmod +x run.sh
./run.sh
```

**Windows**

```
cd p2p-match-ai
run.bat
```

Then open <http://localhost:8080> in a browser.

To use a different port: `./run.sh 9090`

To compile and run by hand:

```
javac -d out $(find src -name "*.java")
java -cp out com.p2pmatchai.App 8080
```

## 3. Optional: LLM-written explanations

The prototype is fully functional offline — exception narratives are generated
from the engine's findings by a deterministic template, which means zero cost and
zero hallucination risk.

To have Claude rewrite those narratives instead, set an API key before starting:

```
export ANTHROPIC_API_KEY=sk-ant-...      # macOS / Linux
setx ANTHROPIC_API_KEY "sk-ant-..."      # Windows, then reopen the terminal
```

The key is read from the environment only. It is never written to a file, logged,
or sent to the browser. The model may only rephrase findings the engine produced —
it cannot change a classification. If the call fails, the offline narrative is used.

## 4. Demonstration script (3–5 minutes)

1. Open the console. The status line shows the backend is connected.
2. Pick **TC01 Clean single-line invoice** and choose *Run verification*.
   Show the extracted fields, the three-way comparison, the clean-match verdict.
3. Choose *Confirm and park*, then open the **Audit trail** tab — the recommendation
   and your decision are both there.
4. Back on **Verify**, pick **TC04 Part delivery, full invoice**. Show the partial-GRN
   finding and the evidence line that names the GRN and PO line it came from.
5. Pick **TC09 GST number does not match master** — the agent escalates rather than holding.
6. Pick **TC07 Same invoice submitted twice** — duplicate caught before parking.
7. Pick **TC10 Barely legible scan** — the agent refuses to guess and asks for manual entry.
8. Edit any line in the document box by hand and re-run, to show the engine is live.
9. Open **Test results** and run all 13 labelled cases: 13 of 13 behave as specified.
10. Close on the audit trail: every verdict is paired with a human decision.

## 5. Project layout

```
src/com/p2pmatchai/
  App.java                     HTTP server, API routes, evaluation harness
  model/Models.java            all domain records
  data/MockSapRepository.java  synthetic vendor master, POs, GRNs, 13 test invoices
  engine/ExtractionService.java  document to structured fields, with confidence
  engine/MatchingEngine.java     three-way match, tolerances, classification
  engine/NarrativeService.java   plain-language explanation, optional LLM
  engine/LlmClient.java          minimal Anthropic API client (JDK only)
  engine/AuditLog.java           append-only trail, mirrored to audit-log.csv
  web/Json.java                  JSON serialisation without a library
web/index.html                 the review console
```

`audit-log.csv` is written next to wherever you run the command. Attach it to the report.

## 6. API

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/health` | status and the tolerance thresholds in force |
| GET | `/api/samples` | the 13 labelled test documents |
| GET | `/api/reference` | vendor master, POs, GRNs |
| GET | `/api/grncheck?po=4500123458` | GRN-vs-PO check at the moment of posting |
| POST | `/api/verify` | `rawText=<document>` or `sampleId=TC04` |
| POST | `/api/decision` | `invoiceNumber`, `decision`, `reason` |
| GET | `/api/audit` | the full trail and its completeness percentage |
| POST | `/api/evaluate` | runs all 13 test cases, returns pass or fail per case |
| POST | `/api/reset` | clears the session ledger |

Bodies are `application/x-www-form-urlencoded`; responses are JSON.

## 7. Matching rules

Every threshold is declared as a constant in `MatchingEngine.java` and shown on
`/api/health`, so a reviewer can reproduce any verdict by hand.

| Rule | Threshold |
|---|---|
| Quantity variance, invoice against goods received | 1% |
| Unit price variance, invoice against PO | 2% |
| Header rounding difference | INR 5.00 |
| Minimum extraction confidence | 70% |

Findings are graded **BLOCKER**, **WARNING** or **INFO**. Any blocker or warning
makes the invoice an exception. Duplicate submissions, GST mismatches, missing or
unknown PO references and un-ordered materials are escalated; everything else is held.

## 8. Design decisions worth defending in a viva

- **The LLM never decides.** Classification comes from deterministic rules. The model
  only rewrites the explanation, and cannot alter the verdict.
- **Unit-of-measure mismatches suppress variance arithmetic.** Comparing 100 boxes to
  1200 pieces would produce a confident, wrong number, so the agent reports the
  mismatch instead of a false variance.
- **Low confidence blocks rather than guesses.** Below 70% the agent asks for manual
  entry. A missing PO reference is never inferred.
- **The audit row is written before a human sees the recommendation,** so no AI output
  can exist without a trace.
- **Human disagreement is recorded, not corrected.** Holding a clean match logs the
  hold; it does not re-classify the invoice.
- **Fields outside the invoice schema are discarded at extraction,** so confidential
  material in an uploaded document never reaches the matching pipeline.

## 9. Known limitations

- Synthetic text documents stand in for PDFs and scans. Real OCR of a photographed
  invoice is out of scope; `ExtractionService` is the seam where it would attach.
- Reference data is a mock of ME23N and MIGO. No live SAP connection exists and no
  write access is sought.
- Duplicate detection is scoped to the running session plus the CSV trail, not a
  permanent invoice register.
- One GRN per PO. Multiple partial receipts against one PO are not consolidated.
