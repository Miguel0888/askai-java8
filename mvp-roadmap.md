# MVP-Roadmap — Research-Buch-Pipeline

Stand: 2026-08-31, `arch@c6018f1c`. Diese Roadmap beschreibt das **funktionale MVP** und die noch
offenen Arbeitspakete. Sie ersetzt keine Epic-Logs; Behauptungen über „existiert bereits" sind gegen
den Code verifiziert (Klassenname genannt) oder als „prüfen" markiert.

## Die MVP-Produktkette

```text
vage Idee
→ gemeinsam Konzept und Scope entwickeln          (Phase 1: Scoping)
→ Recherche planen und durchführen                 (Phase 2: Recherche)
→ Quellen fachlich zu Wissen auswerten             (Knowledge-Konsolidierung)
→ Inhaltsverzeichnis aus dem Konzept WEITERENTWICKELN  (Phase 3: Struktur II)
→ Kapitel schreiben                                (Phase 4: Inhalt II)
→ vollständigen, neustartfesten Research Report öffnen
```

Kernprinzip überall: **akkumulativ, nie regenerativ.** Insbesondere ist das Inhaltsverzeichnis
KEINE Neugenerierung „aus den Sources", sondern die Weiterentwicklung des Konzeptpapiers:

```text
bestehendes Konzeptpapier
+ bestätigter Scope (Facets, Ausschlüsse, Gewichtungen, Lieferumfang)
+ konsolidiertes Wissen aus den Quellen (Findings, Kategorien, Kontraste, Lücken)
→ neue OutlineRevision (explizit vom Benutzer erzeugt und freigegeben)
```

Der Benutzer besitzt sämtliche Phasenübergänge; die KI ist prozess-machtlos (RA-P6-Rollenmodell).

## Statusübersicht

| AP | Arbeitspaket | Stand |
|----|--------------|-------|
| 1 | Scope-Naht live abnehmen (Abnahme 4) | Fixes fertig (`c6018f1c`), Abnahme ausstehend — **akuter Blocker** |
| 2 | Conversation Policy (Gesprächsstaffelung) | offen, Design verbindlich fixiert |
| 3 | `scope_probe` (Closure-Messwerkzeug) | offen, Design verbindlich fixiert |
| 4 | Übergang Scoping → Research Planning | weitgehend offen |
| 5 | Produktive Recherche live abnehmen | Technik weit gebaut, mehrere Live-Gates offen |
| 6 | Weidezaun → Search-Control | offen (der wirtschaftliche Nutzen von Phase 1) |
| 7 | Knowledge-Konsolidierung (Quellen → Corpus) | C1–C4 gebaut+getestet, Live-Gate blockiert durch N4; C5–C8 offen |
| 8 | Inhaltsverzeichnis / Struktur II | Domain-Bausteine existieren, produktive Integration offen |
| 9 | Schreiben / Inhalt II + Report | größter offener Block; Domain-Bausteine existieren |
| 10 | K4 — Concept als alleinige Phase-1-Wahrheit | offen, wartet auf AP 1 |
| 11 | Restart-Restore-Verifikation | teils überholt gegenüber problems.md — **prüfen, nicht neu bauen** |
| 12 | End-to-End-MVP-Abnahme | offen (zuletzt) |

## Die Arbeitspakete

### AP 1 — Live-Abnahme 4 der Scope-Naht (Blocker)

Frische Session, derselbe natürliche Verlauf. Gates:

1. Nach dem Erstauftrag: Mission im `scope_snapshot` (Vertragsregel „setMission sobald das Ziel
   genannt ist"), lesbare PROVISIONAL-Facets, keine technischen/prosaartigen IDs.
2. Nach „ESP-IDF möchte ich doch nicht behandeln, nur Arduino":
   `arduino [CONFIRMED]` + `esp-idf [EXCLUDED]`, keine freie Doppel-Exclusion, keine
   widersprüchliche Rejection-Meldung im Chat, nur gültige IDs (`^[a-z0-9][a-z0-9_-]{0,63}$`).
3. `check-scope`: `negotiated posts >= 2` UND `mission samples > 0`. Die Memberships im Snapshot
   sind der eigentliche Nachweis; der Zähler allein reicht nicht.
4. „Was ist mit ESP-IDF Task Notifications?": differenzierte Grenzanwendung — ESP-IDF-spezifische
   Behandlung bleibt draußen, allgemeine FreeRTOS-Task-Notifications dürfen unter Arduino behandelt
   werden; keine irrelevante Concept-/Scope-Manipulation.

Vor bestandenem AP 1 wird nichts Neues in Phase 1 begonnen.

### AP 2 — Conversation Policy

Staffelung `OPEN_EXPLORATION → GUIDED_EXPLORATION → BOUNDARY_PROBING → CLOSURE_CHECK`, abgeleitet
aus COVERAGE/NOVELTY/BOUNDARY/DRIFT (nie aus Anker-Zählungen). Ephemerer Hint pro Inferenz,
ausdrücklich KEINE zweite State-Machine. Agent-Vorschläge und Webfunde bleiben im Scope
PROVISIONAL; nur Benutzerentscheidungen erzeugen harte IN-/OUT-Anker.

### AP 3 — `scope_probe`

Dünne, phasengegatete MCP-Hülle über dem existierenden `ScopeFenceEvaluator`: mehrere Begriffe
gesammelt prüfen; Vokabular LIKELY_IN/LIKELY_OUT/BOUNDARY/NOVEL; Confidence-BAND (Cosine ist
unkalibriert — keine „Wahrscheinlichkeit"); nächster Anker + dessen Autorität (USER_CONFIRMED
stark, PROVISIONAL Hinweis). Sensor, nie Autor: keine automatische Scope-Änderung; Ergebnis erzeugt
höchstens eine letzte Benutzerfrage. Per Prompt auf die Closure-Phase beschränkt.

### AP 4 — Übergang Scoping → Research Planning

`submit-scope` existiert; daraus muss ein produktiver Rechercheauftrag werden: autoritativen Stand
von Konzept + Scope übernehmen (inkl. Lieferumfang/Ziel-Länge, importance/researchDepth,
Ausgabeschwerpunkte), Forschungsbereiche und Informationslücken ableiten, Suchaufgaben planen.
Kategorien sind hier FORSCHUNGSHYPOTHESEN — noch kein Inhaltsverzeichnis; sie dürfen sich während
der Recherche ändern.

### AP 5 — Produktive Recherche live abnehmen

Verifiziert bereits GEBAUT (Abnahme fehlt): Stop-Regel als Policy
(`FixedAcceptedSourceCountPolicy`, `SearchCompletionPolicy`, `MinimumEvidenceCompletionPolicy` in
der Runtime — konfigurierte Zielzahl akzeptierter Quellen, kein Neubau nötig); fullText/excerpt/
score, Park-all, Captcha-Wait/Skip, zweistufiger Readiness-Loop, LLM-Readiness-Judge
(fulltext-park-readiness, committed); Sidecar-Actor+Pump gegen Freezes.

Offen für das MVP:
- Browser-Sidecar Slice 3: Skip-Abort bei hängendem Data-Call, Deadlines, Todes-Erkennung des
  Browsers, Websuche-Bubble — plus Live-Gate.
- Full-text/Park/Readiness: das ausstehende Live-Browser-GUI-Gate.
- Niemals grüner Erfolg bei technischem Abbruch oder null tatsächlich durchgeführten Suchen
  (Such-Fake-Erfolg wurde schon einmal gefixt — im Gate erneut prüfen).
- Skip/Pause/Cancel auch bei blockiertem Browseraufruf; Fortsetzen nach übersprungenen/fehlerhaften
  Treffern; persistente, nachvollziehbare Search-Runs.
- Leichtes Orientierungssuch-Profil (für gelbe orientationSuggestions): SERP-Snippets sammeln,
  keine Seitenbesuche, keine Captcha-/Cookie-Interaktion, 2–3 Ergebnisseiten, strukturierte
  Kandidaten; einzelne Kandidaten später gezielt vertiefbar ohne erneute Suche.
  (Anschluss: research-command-architecture S2–S4.)

Automatische semantische Suchbeendigung gehört ausdrücklich NICHT hinein.

### AP 6 — Weidezaun → Search-Control

Der wirtschaftliche Nutzen der Phase-1-Arbeit:

```text
USER_CONFIRMED OUT  → hart aussortieren / Seite nicht besuchen
PROVISIONAL         → höchstens herunterranken, nie unterdrücken
CONFIRMED IN        → positives Relevanzsignal
```

Semantische Grenze, keine Wort-Blacklist: „ESP-IDF Task Notifications" muss in einen allgemeinen
FreeRTOS-Anteil und eine ausgeschlossene ESP-IDF-spezifische Behandlung differenzierbar bleiben.

### AP 7 — Knowledge-Konsolidierung (eigenes MVP-Paket, nicht nur „Gates")

Zwischen Recherche und Inhaltsverzeichnis liegt echte Produktarbeit — ohne sie entstünde das
Inhaltsverzeichnis aus rohen Quellen oder Modellfantasie:

```text
akzeptierte Quelle → relevante Passagen → Findings/Claims (mit Herkunft und Beleg)
→ Zuordnung zu Forschungsbereichen → Widersprüche und Lücken
→ ActiveKnowledgeCorpus → Topic-/Category-Projection → outlinefähiges Wissen
```

Stand: C1–C4 gebaut und getestet (SemanticKnowledgeIndex mit Lucene+Cosine, kanonische
vectors.bin, produktiver Worker); das C4-Live-GUI-Gate ist durch **N4** blockiert (gepinnte
OpenNLP-1.5-Modellquelle url+sha256 fehlt). Danach C5–C8 (ActiveKnowledgeCorpus + Continuous Topic
Projection zuerst, bewusst klein: Cluster ohne Labeling/Outline).

Produktregeln: Quellenprüfung bleibt benutzerinitiiert; kein verstecktes Auto-Outline oder
Background-Processing; fehlgeschlagene Prüfung wiederholbar; ein Neustart vergisst keine
ausstehende Prüfung; die Sources-Ansicht muss fachlich benutzbar sein (Comic-Vollausbau ist kein
Blocker).

### AP 8 — Inhaltsverzeichnis / Struktur II

Verifiziert: `OutlineRevision`, `OutlineProposal`, `EvidenceReview`, `EvidenceBaseline` existieren
im Domain-Core (`research-domain`, fachlicher Vertikal-Slice-Test vorhanden) — das AP ist
INTEGRATION, keine grüne Wiese.

Offen für das MVP:
- outline-Sektion im Buch-Umschlag (eigene View + eigener Tool-Vertrag; Adressierung/Guards pro
  Sektion — das Konzept adressiert per Namenskette, das Outline bekommt seinen eigenen Vertrag);
- Erzeugung/Aktualisierung ausschließlich EXPLIZIT durch den Benutzer (keine laufende Neuerzeugung
  während der Recherche), akkumulativ aus dem Konzept weiterentwickelt (siehe Kette oben);
- nachvollziehbare Kapitelstruktur, Änderungswünsche des Benutzers, und eine bestimmte Revision
  wird vor dem Schreiben FREIGEGEBEN/eingefroren (Phasen-Freeze: eingefrorene Artefakte nur lesbar).

### AP 9 — Schreiben / Inhalt II + Report

Größter offener Wertschöpfungsschritt. Bausteine im Domain-Core (EvidenceReview/Baseline,
buildEvidenceReview) existieren; produktiv fehlt alles:

- content/Manuskript-Sektion im Buch-Umschlag mit POSITIONS-Adressierung (nicht Namenskette);
  Blocktyp-als-Knoten (paragraph/code/quote), citations/style/images als versiegelte ObjectLeafs;
- Abschnitt für Abschnitt aus der FREIGEGEBENEN Outline schreiben, ausschließlich gegen
  nachvollziehbare Findings/Quellen (Belege + Links bleiben erhalten);
- Umfang/Gewichtungen aus dem Scope beachten; Kategorien VERGLEICHEN statt ähnliche Einzelobjekte
  aufzählen (SynthesisPolicy);
- jeden fertigen Abschnitt persistieren; Abbruch/Retry/Fortsetzung; Evidence-Review-UI
  („Belege prüfen");
- vollständiges Dokument assemblieren. Fürs erste MVP reicht ein persistierter, in AskAI lesbarer
  Gesamtbericht — Word-/PDF-Exportqualität kommt danach.

### AP 10 — K4: Concept als alleinige Phase-1-Wahrheit

Nach bestandenem AP 1 (der [Brief] ist bis dahin Kontrollgruppe): `researchBriefMarkdown` aus dem
Kontrakt, [Brief]-Karte + Umschalter aus dem Concept-Tab (der private ComicToggle stirbt mit),
ComicSearchBar („Chats durchsuchen…"-Idiom) in den Footer, `submit-scope`/Freigabe-Gate auf
ConceptStore + ResearchScopeDraft stützen.

### AP 11 — Restart-Restore verifizieren (vor AP 8/9!)

Teilweise ÜBERHOLT gegenüber problems.md (RA-P002): der produktive Pfad nutzt bereits den
Datei-Store — `ProductiveResearchBackendFactory` bindet `FileResearchSourceRepository` über den
`ResearchProjectStore`; Konzept (`FileConceptStore`), Scope (`FileResearchScopeDraftStore`) und
Brief sind ebenfalls datei-persistent. In-memory ist nur der FAKE-Modus.

Zu VERIFIZIEREN (nicht blind neu bauen): überlebt eine produktive Session den App-Neustart
vollständig — Session-State/Memento, ausstehende Quellenprüfungen, Scope-Revision, Konzept-Revision,
laufende Freigaben? problems.md nennt `session/load`/Memento-Restore als unvollständig (Stand
Commit 35b). Wenn Outline und Manuskript auf ungeklärter Persistenzbasis entstünden, wäre jedes
spätere AP gefährdet — deshalb gehört diese Prüfung VOR AP 8/9.

Übrige problems.md-Punkte (RA-P003 Erste-Prompt-Race, RA-P006–P010): jeweils erst dann
verifizieren, wenn ihr Ablauf im nächsten Live-Gate tatsächlich berührt wird — nicht jetzt alle
untersuchen, sonst geht der kritische Pfad wieder verloren.

### AP 12 — End-to-End-MVP-Abnahme

Ein vollständiger Live-Lauf:

```text
„Ich möchte ein Buch über …"
→ Scoping (Konzept + Scope bestätigen)
→ Recherche durchführen
→ Quellen prüfen (Knowledge Corpus)
→ Outline erzeugen und freigeben
→ Kapitel schreiben
→ finalen Bericht öffnen
```

Dabei müssen App-Neustart, Pause, Cancel, Retry und fertige Zwischenergebnisse erhalten bleiben;
der Benutzer besitzt sämtliche Phasenübergänge.

## Reihenfolge (kritischer Pfad)

```text
AP 1  (Abnahme 4 — Blocker, nichts Neues vorher)
AP 2  Conversation Policy
AP 3  scope_probe
AP 6  Search-Control          ← zieht den Nutzen aus 1–3
AP 10 K4
AP 11 Restart-Restore-Verifikation
AP 5  Recherche-Live-Gates    ← parallelisierbar mit AP 4
AP 4  Research Planning
AP 7  Knowledge-Konsolidierung (N4 → C4-Gate → C5–C8)
AP 8  Outline / Struktur II
AP 9  Schreiben / Inhalt II + Report
AP 12 End-to-End-Abnahme
```

## Ausdrücklich NICHT im funktionalen MVP

- automatische semantische Suchbeendigung;
- gelernte/self-healing Browser-Skills; Firefox/BiDi als zusätzlicher Backendweg;
- vollständige ComicControls-Migration; perfekte Sources-Seitenleiste;
- sämtliche Anbieter-Playgrounds (Brave/BrightData-Karten);
- weitere TTS-/Diktat-Verfeinerungen;
- mathematisch perfekt kalibrierter Weidezaun;
- ChatGPT-Connector-Vollausbau: der Connector ist unser TESTWERKZEUG für die Live-Gates
  (scope_snapshot/technical_log/concept_json), aber „Fernsteuerung durch ChatGPT" ist kein Teil
  der fachlichen Buchpipeline;
- Word-/PDF-Exportqualität des Reports.

## Ehrlicher Gesamtstand

Die Infrastruktur ist weit: Konzept-Pipeline live bewiesen, Scope-Domäne komplett, Recherche-Technik
inklusive Stop-Policies und Readiness gebaut, Knowledge-Pipeline bis C4 getestet, Outline-/
Evidence-Bausteine im Domain-Core vorhanden. Die PRODUKTKETTE endet aber derzeit nach Scoping,
manueller Suche und Teilen der Quellenverarbeitung. Die beiden größten ungebauten Produktblöcke
sind Outline-Freigabe (AP 8) und Kapitelproduktion + Report (AP 9); davor stehen die Scope-Abnahme
(AP 1) als akuter Blocker und die Restart-Restore-Verifikation (AP 11) als wichtigste
Querschnittsprüfung.
