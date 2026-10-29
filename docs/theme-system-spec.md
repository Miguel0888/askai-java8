# Theme-System „Arcane Knowledge“ — Analyse & Implementierungs-Spezifikation

Stand: 2026-10-29, `main`/`arch` @ `69c343ee`. Autor: Review-Session (Claude, voller Projektkontext).
Zielgruppe: eine **frische Implementierungs-Session ohne Vorkontext**. Dieses Dokument ist selbsttragend;
bei Widerspruch zwischen diesem Dokument und eigenem Ermessen gilt dieses Dokument.

## 0. Auftrag

AskAI bekommt ein zentrales Theme-Fundament (semantische Farb-Token, EIN Ort), über das die gesamte
Oberfläche einheitlich eingefärbt wird — als Vorbereitung auf die neue Markenwelt „Arcane Knowledge“
(Marineblau + Gold, abgeleitet vom neuen Logo: goldgerandetes Navy-„A“ mit Magier; siehe
`askai-app/src/main/resources/icons/askai.png` und `docs/images/askai-banner.png`).

**Umfang dieses Auftrags: NUR S1 + S2 (unten).** Die Migration der ~145 verstreuten `new Color(...)`
in App/Plugin ist ausdrücklich NICHT Teil dieses Auftrags (Abschnitt 5).

## 1. Ist-Analyse (verifizierte Fakten)

Drei getrennte Farbwelten existieren:

1. **`ComicPalette`** (`comic-controls/src/main/java/com/aresstack/comiccontrols/theme/ComicPalette.java`)
   — austauschbares Value-Objekt mit 7 Rollen (surface, ink, navigationBlue, agentPetrol,
   accentYellow, accentOrange, accentRed). ~27 Aufrufstellen nutzen `defaultPalette()` direkt.
2. **`ResearchUiPalette`** (gleiches Verzeichnis) — „die EINE Farbtabelle“ der dunklen Research-UI:
   ~32 `public static final Color`-Konstanten (DARK_BASE … CLOUD_CLOSE). Verbraucher in
   comic-controls UND im Research-Plugin referenzieren die Konstanten direkt.
   Wichtig: `static final Color` wird NICHT vom Compiler inline-kopiert (kein Compile-Time-Constant)
   — Wertänderungen erreichen das Plugin ohne Plugin-Rebuild.
3. **Chat-Bubbles** (`askai-app/.../ui/bubble/BubblePalette`, `ThoughtBubbleTheme`) — eigene Welt,
   in diesem Auftrag UNANGETASTET.

Weitere Fakten:

- `ComicTheme.installMenuDefaults()` installiert heute nur **5 UIManager-Keys** (Menüs/Popups).
  Aufrufort: `askai-app/.../ui/AskAiFrame.java` → `buildUserInterface()`, VOR dem UI-Aufbau.
- Die App setzt **kein** Look-and-Feel (`setLookAndFeel` existiert nirgends) → Default
  Cross-Platform-LAF **Metal**. Metal respektiert UIManager-Farbdefaults zuverlässig
  (anders als das Windows-System-LAF). Das ist die Grundlage der ganzen Strategie.
- Roh-Swing dominiert ~50:1: 96× `new JButton(`, 98× `new JTextField(`, 54× `new JScrollPane(`,
  235× `new JPanel(`, 203× `new JLabel(` vs. 9× `ComicButton`, 2× `ComicScrollPane`, …
  → Subklassen-Konvertierung ist der FALSCHE Hebel; der richtige ist UIManager/UI-Delegates.
- Classloader-Topologie: `comic-controls` ist im Plugin `compileOnly` und aus dessen `lib/`
  ausgeschlossen (host-provided, `research-agent-ui-plugin/build.gradle:35,129`). Eine Token-Tabelle
  in comic-controls ist damit EINE Wahrheit für Host und Plugin. **Das Plugin installiert nie
  selbst UIManager-Defaults** — das tut nur der Host.
- Hartkodierte Farben INNERHALB von comic-controls (werden in S1 bereinigt):
  - `ComicButton.java:100` → `Color.WHITE` (Ruhe-Plate)
  - `ComicFindBar.java:39,82-84` → `0xFFF8E1`, `0xFAFAFA`, `0xCCCCCC`, `0x888888`, `0xAAAAAA`
  - `ComicSearchBar.java:64-66` → `Color.WHITE`, `0xBBBBBB`, `0x888888`, `0xAAAAAA`
  - `ComicSearchTag.java:23,33,36` → `0xFFF8E1`, `0xAAAAAA`, `0x888888`
  - `ComicOverlayPanel.java:80,118,124` → `rgba(0,0,0,110)`, `Color.WHITE`
- `ComicScrollBarUI` existiert bereits als `BasicScrollBarUI`-Ableitung (Konstruktor nimmt Palette,
  `install(JScrollBar)`-Statics). `OllamaChatPanel.java:657` installiert ihn manuell an einer Stelle.

## 2. Verbindliche Architektur-Entscheidungen

- **D1 — Token-Tabelle `AskAiTheme`** (neu, in `com.aresstack.comiccontrols.theme`):
  unveränderliches Value-Objekt. Es TRÄGT die bestehenden Strukturen, ersetzt sie nicht:
  `theme.comicPalette()` liefert eine `ComicPalette`, plus Zusatz-Token (Abschnitt 3).
  Zwei eingebaute Themes: `AskAiTheme.classic()` (heutige Werte, BYTE-IDENTISCH) und
  `AskAiTheme.arcane()` (neue Werte).
- **D2 — Auswahl per JVM-Start-Property** `askai.theme` (`classic` | `arcane`), gelesen von
  `AskAiTheme.active()` (einmalig, lazy, gecacht; unbekannter/fehlender Wert → Default).
  Default nach S1: `classic`. Default nach S2: `arcane`. KEIN Laufzeit-Umschalter, KEINE
  Settings-UI (bewusst vertagt, bis ein zweites Theme dauerhaft gewollt ist).
  Die pure Auflösung `AskAiTheme.resolve(String)` ist getrennt testbar.
- **D3 — Bestehende Tabellen werden Adapter:**
  - `ComicPalette.defaultPalette()` → delegiert an `AskAiTheme.active().comicPalette()`.
  - `ResearchUiPalette`-Konstanten bleiben `public static final Color`, werden aber im
    statischen Initialisierer aus `AskAiTheme.active()` befüllt. (Property ist pro JVM fix →
    keine Klassenlade-Reihenfolge-Probleme.) Signaturen/Namen bleiben unverändert —
    **kein einziger Verbraucher wird angefasst.**
- **D4 — UIManager-Ausbau statt Subklassen:** `ComicTheme` bekommt
  `install(AskAiTheme theme)` (ruft intern auch das bestehende `installMenuDefaults` auf).
  `AskAiFrame.buildUserInterface()` ruft künftig `ComicTheme.install(AskAiTheme.active())`
  an der Stelle des heutigen `installMenuDefaults()`-Aufrufs (gleicher Ort, vor UI-Aufbau, EDT).
  Key-Tabelle in Abschnitt 4/S1b.
- **D5 — Button-Delegate `ComicButtonUI`** (neu, `extends BasicButtonUI`, mit
  `public static ComponentUI createUI(JComponent c)`): übernimmt die Plate-Malerei aus
  `ComicButton.plateFill()` (Ruhe = Token `buttonPlate`; Rollover = accentYellow; Pressed =
  accentOrange; Disabled = surface; Ink-Kontur abgerundet wie `ComicButton`). Registriert via
  `UIManager.put("ButtonUI", "com.aresstack.comiccontrols.control.ComicButtonUI")`.
  **Opt-out:** Client-Property `"askai.plainButton" = Boolean.TRUE` → Delegate malt wie
  `BasicButtonUI` (nur Farben aus den Defaults). **`ComicButton` selbst setzt dieses Property im
  Konstruktor**, denn er malt seine Plate weiterhin in `paintComponent` — sonst Doppelmalerei.
- **D6 — Scrollbar-Delegate global:** `ComicScrollBarUI` erhält
  `public static ComponentUI createUI(JComponent c)` (Palette aus `AskAiTheme.active()`), wird via
  `UIManager.put("ScrollBarUI", …)` registriert. Der manuelle Install in `OllamaChatPanel:657`
  und `ComicScrollPane` bleibt (harmlos redundant).

## 3. Token-Modell & Farbwerte

`AskAiTheme` führt drei Gruppen. **Classic = exakt die heutigen Werte** (Pin-Test!). Die
Arcane-Werte sind kalibrierte STARTWERTE — Feintuning passiert im GUI-Gate des Users, die
Implementierung übernimmt sie unverändert.

### 3.1 Comic-Gruppe (helle Seite)

| Token | Classic (heute) | Arcane |
|---|---|---|
| surface | `0xF4F4F4` | `0xF7F5F0` |
| ink | `0x252525` | `0x253048` |
| navigationBlue | `0x1676D2` | `0x315B91` |
| agentPetrol | `0x15827A` | `0x287D79` |
| accentYellow | `0xF2C94C` | `0xE6B650` |
| accentOrange | `0xE39A18` | `0xC99232` |
| accentRed | `0xC94C4C` | `0xC94C4C` (Funktionsfarbe, NIE ändern) |
| buttonPlate (neu) | `0xFFFFFF` | `0xFFFDF8` |
| warmFocus (neu; FindBar/SearchTag-Creme) | `0xFFF8E1` | `0xF8EFD8` |
| fieldBg (neu; SearchBar-Weiß, FindBar `0xFAFAFA`) | `0xFFFFFF` / FindBar behält `0xFAFAFA`→Token fieldBgQuiet | `0xFFFDF8` / `0xFAF7F0` |
| fieldBorder (neu; `0xCCCCCC`/`0xBBBBBB` → EIN Token) | `0xC6C6C6` | `0xCBC4B4` |
| hintText (neu; `0x888888`) | `0x888888` | `0x8F897B` |
| hintTextSoft (neu; `0xAAAAAA`) | `0xAAAAAA` | `0xA9A295` |
| overlayDim (neu; Overlay-Backdrop) | `rgba(0,0,0,110)` | gleich |

Hinweis zu fieldBorder: Classic fasst `0xCCCCCC`/`0xBBBBBB` zu `0xC6C6C6` zusammen — das ist die
EINZIGE erlaubte sichtbare Classic-Abweichung (zwei fast identische Grautöne werden eins).

### 3.2 Research-Gruppe (dunkle Seite) — Namen = exakt die `ResearchUiPalette`-Konstanten

| Konstante | Classic (heute) | Arcane |
|---|---|---|
| DARK_BASE | `0x0C1117` | `0x0B1220` |
| DARK_SURFACE | `0x12181F` | `0x111A28` |
| WORKSPACE_SURFACE | `0x1B2028` | `0x192338` |
| CHIP_SURFACE | `0x0E1218` | `0x0D1524` |
| SECONDARY_SURFACE | `0x141A21` | `0x141E30` |
| SECONDARY_HOVER | `0x202832` | `0x1E2A44` |
| TEXT_PRIMARY / TEXT_MUTED / TEXT_DARK | unverändert | unverändert |
| PURPLE_PRIMARY | `0x5E3BA3` | `0xC08A2E` (Gold) |
| PURPLE_ACTION | `0x754DB8` | `0xDAA648` |
| PURPLE_HOVER | `0x6943AD` | `0xCE9838` |
| BORDER_DARK | `0x5E6370` | `0x645F55` |
| BORDER_WINDOW | `0x38404D` | `0x3A3F52` |
| ACCENT_BLUE | `0x2979FF` | `0xDAA648` (aktive Navigation wird GOLD) |
| DANGER_RED | `0xD32F2F` | unverändert (Funktionsfarbe) |
| LIGHT_CONTROL_BG | `0xFFFFFF` | `0xFFFDF8` |
| LIGHT_CONTROL_BORDER | `0xC9CED6` | `0xCFC8B8` |
| LIGHT_CONTROL_TEXT | `0x44484D` | `0x474439` |
| LIGHT_TEXT_MUTED | `0x8A909B` | `0x8E897C` |
| CHIP_HOVER_SURFACE | `0x151B23` | `0x161F33` |
| CHIP_HOVER_BORDER | `0x767D89` | `0x7D7664` |
| CHIP_CLOSE_HOVER | `0x2A3039` | `0x2C3347` |
| WORKSPACE_DIVIDER | `0x282E38` | `0x2A3144` |
| SKY_TOP | `0xF5FAFE` | `0xFAF6EC` |
| SKY_BAR_SURFACE | `0xBFDCF2` | `0xE8D9B0` |
| SKY_CAPTION | `0x6E87A2` | `0x8A7A55` |
| CLOUD_SURFACE | `0xF7FBFF` | `0xFDFAF2` |
| CLOUD_BORDER | `0xAFCDE8` | `0xD9C690` |
| CLOUD_TEXT | `0x40556B` | `0x5C5238` |
| CLOUD_HOVER_SURFACE | `0xEAF4FC` | `0xF7F0DD` |
| CLOUD_HOVER_BORDER | `0x8FB8DE` | `0xC8B274` |
| CLOUD_CLOSE | `0x72879A` | `0x8A8064` |

(Fehlt eine Konstante in dieser Liste, gilt: Classic-Wert behalten, Arcane = Classic.)

### 3.3 Nicht-Ziele der Token

Typografie (`ResearchUiTypography`), Metriken (`ResearchUiMetrics`), Bubble-Farben,
Markdown-/HTML-Rendering-Farben: unverändert.

## 4. Arbeitspakete

### S1a — Token-Fundament + Adapter (1 Commit)

Dateien (alle in `comic-controls`):
- NEU `theme/AskAiTheme.java`: Value-Objekt + `classic()` + `arcane()` + `resolve(String)` +
  `active()` (cached; Property `askai.theme`, Default in S1 = `classic`).
- `theme/ComicPalette.java`: `defaultPalette()` delegiert an `AskAiTheme.active()`.
- `theme/ResearchUiPalette.java`: Konstanten aus `AskAiTheme.active()` befüllen; Javadoc der
  Klasse auf die neue Quelle anpassen; KEINE Namensänderung.
- Hartkodierungen aus Abschnitt 1 auf Token umstellen (`ComicButton`, `ComicFindBar`,
  `ComicSearchBar`, `ComicSearchTag`, `ComicOverlayPanel`).

Tests (JUnit 4, headless-sicher, `comic-controls/src/test/...`):
- `AskAiThemeTest`: `resolve("classic"/"arcane"/null/"garbage"/" ARCANE ")`;
  Classic-Pin: JEDER Classic-Token == dokumentierter Hex-Wert (die Tabelle oben als Testdaten);
  Arcane-Pin: accentRed/DANGER_RED/TEXT_* identisch zu Classic.
- `ResearchUiPaletteDelegationTest`: Konstanten == `AskAiTheme.active()`-Werte.

### S1b — UIManager-Ausbau + Delegates (1 Commit)

- NEU `control/ComicButtonUI.java` (D5), `ComicScrollBarUI.createUI` (D6).
- `theme/ComicTheme.install(AskAiTheme)` mit dieser Key-Tabelle (Werte = Token):

| UIManager-Key(s) | Wert |
|---|---|
| `Panel.background`, `Viewport.background`, `ScrollPane.background`, `SplitPane.background`, `OptionPane.background` | surface |
| `Label.foreground` | ink |
| `ButtonUI` | ComicButtonUI (D5); zusätzlich `Button.foreground`=ink |
| `ScrollBarUI` | ComicScrollBarUI (D6) |
| `TextField.*`, `TextArea.*`, `PasswordField.*`, `FormattedTextField.*`: `background`=fieldBg, `foreground`=ink, `caretForeground`=ink, `selectionBackground`=accentYellow, `selectionForeground`=ink, `inactiveForeground`=hintTextSoft | |
| `TextField.border` | LineBorder(fieldBorder) + EmptyBorder(3,6,3,6) als CompoundBorder |
| `CheckBox.background`/`.foreground`, `RadioButton.*` | surface / ink (Icons bleiben Metal) |
| `Table.background`=fieldBg, `.foreground`=ink, `.gridColor`=fieldBorder, `.selectionBackground`=accentYellow, `.selectionForeground`=ink; `TableHeader.background`=surface, `.foreground`=ink | |
| `List.selectionBackground`/`Tree.selectionBackground`=accentYellow, `…Foreground`=ink | |
| `ComboBox.background`=fieldBg, `.foreground`=ink, `.selectionBackground`=accentYellow, `.selectionForeground`=ink | |
| `TabbedPane.background`=surface, `.foreground`=ink, `.selected`=buttonPlate, `.contentAreaColor`=surface | |
| `ProgressBar.foreground`=accentOrange, `.background`=surface, `.selectionForeground`=surface, `.selectionBackground`=ink | |
| `ToolTip.background`=warmFocus, `.foreground`=ink, `.border`=LineBorder(ink) | |
| `MenuBar.background`=surface, `Menu.background`/`MenuItem.background`/`PopupMenu.background`=buttonPlate | |

  Alle Farbwerte als `ColorUIResource`, Borders als `BorderUIResource` (sonst ignoriert Metal sie
  teilweise bzw. überschreibt sie bei `updateUI`).
- `AskAiFrame.buildUserInterface()`: `ComicTheme.installMenuDefaults()` → `ComicTheme.install(AskAiTheme.active())`.
- `ComicButton`-Konstruktor: `putClientProperty("askai.plainButton", Boolean.TRUE)` (D5-Doppelmal-Schutz).

Tests:
- `ComicThemeInstallTest`: nach `install(classic())` liefert `UIManager.getColor(key)` für JEDEN
  Key der Tabelle den erwarteten Token-Wert (tabellengetrieben); `ButtonUI`/`ScrollBarUI`-Strings gesetzt.
- `ComicButtonUITest`: Plate-Farblogik (Ruhe/Rollover/Pressed/Disabled/Critical) als pure Funktion
  extrahiert und direkt getestet; Opt-out-Property respektiert; `ComicButton` trägt das Property.

### S2 — Arcane wird Default (1 Commit)

- `AskAiTheme.active()`-Default: `arcane`. `-Daskai.theme=classic` liefert exakt den S1-Look (A/B-Test!).
- Tests: Default-Pin aktualisieren; Classic bleibt via Property byte-identisch erreichbar.
- `docs/theme-system-spec.md`: Abschnitt „Implementation notes“ ANHÄNGEN (Abweichungen, offene Punkte).

Danach ist Schluss. GUI-Gates (User startet die App selbst, zweimal: ohne Property und mit
`-Daskai.theme=classic`) und das Review übernimmt die Review-Session.

## 5. Die teuren Teile — analysiert, aber BEWUSST NICHT in diesem Auftrag

~145 verstreute `new Color(`-Stellen in App + Plugin, nach Bereich (Dateizahl):

| Bereich | Dateien | Inhalt / Risiko |
|---|---|---|
| `askai-app/ui` | 20 | Chat-Panel, Model-Browser, Settings-Panels — größter Block, braucht semantische Einzelentscheidungen |
| `research-agent-ui-plugin/agent` | 6 | Research-Workspace-Panels |
| `askai-app/ui/markdown` | 5 | Markdown/Mermaid-Rendering — eigene Lesbarkeitswelt, zuletzt anfassen |
| `askai-app/ui/bubble` | 4 + `BubblePalette` | Bubble-Familie (User-Blau, Agent-Petrol, Amber, Failure) — eigener Slice mit eigener Token-Untergruppe |
| Rest (`host`, `document`, `notify`, `config`) | 5 | Kleinkram |

Jeder Bereich wird später ein EIGENER Slice mit eigenem User-GUI-Gate. Wer in S1/S2 eine dieser
Stellen „mitnimmt“, verstößt gegen den Auftrag.

## 6. Leitplanken (Repo-Regeln, verbindlich)

1. **Java 8**, Bytecode-kompatibel; keine neuen Dependencies.
2. **Alle statischen UI-Texte Englisch** (auch neue Javadoc/Kommentare Englisch, Repo-Konvention).
3. **Die App NIE selbst starten oder beenden** (keine Run-Configs, kein `java -jar`). GUI-Gates macht der User.
4. **Gezielte Builds:** nur `./gradlew --no-daemon :comic-controls:test` und
   `:askai-app:test --tests '*ComicTheme*' --tests '*AskAiTheme*' --tests '*ComicButtonUI*'`
   plus `:askai-app:compileJava`. KEINE vollen Suiten, KEIN `installDevPlugin` (Plugin unverändert),
   KEINE Suiten, die Ollama treffen (Live-Tests laufen bei erreichbarem Ollama automatisch mit).
5. **Jeder Turn mit Codeänderung endet mit commit + push auf `arch`** (NICHT auf main; der Merge
   nach main passiert erst nach dem Review). Branch vor Arbeitsbeginn: `git checkout arch`.
6. UIManager-Installation ausschließlich im Host (`AskAiFrame`), nie im Plugin.
7. Grenzwerte/Timeouts wären settings-pflichtig — hier entstehen keine; die Theme-Property ist
   ein Entwicklungs-/A-B-Schalter und bewusst KEINE Setting-Zeile.

## 7. Review-Checkliste (prüft die Review-Session — zur Selbstkontrolle)

- [ ] Classic ist byte-identisch gepinnt (Tabellen-Hexwerte als Testdaten, nicht abgeschrieben aus dem Code).
- [ ] `ResearchUiPalette`: Namen/Sichtbarkeit unverändert, kein Verbraucher angefasst.
- [ ] Kein einziger der 145 Scatter-Punkte angefasst; keine Datei außerhalb comic-controls + `AskAiFrame` geändert.
- [ ] `ComicButton` ohne Doppelmalerei (Opt-out-Property im Konstruktor, Test vorhanden).
- [ ] Alle UIManager-Werte `ColorUIResource`/`BorderUIResource`.
- [ ] accentRed/DANGER_RED in Arcane unverändert (Funktionssemantik).
- [ ] `resolve()` pur und getestet; `active()` cached; Default S2 = arcane, `classic` per Property erreichbar.
- [ ] Nur gezielte Gradle-Tasks gelaufen; Commits auf arch gepusht; keine App-Starts.
- [ ] „Implementation notes“ am Dokumentende ergänzt.

## Implementation notes

Implemented on `arch` in three commits (S1a `ef5d43e4`, S1b `a196aee9`, S2 = the commit adding this section).
Deviations and open points — documented, not decided:

1. **Research tokens as a name-keyed map.** `AskAiTheme` exposes the research group via
   `research(String constantName)` / `researchTokens()` (keys = exactly the `ResearchUiPalette`
   constant names) instead of ~33 typed getters. A delegation test checks that every public
   `Color` constant has a token and vice versa; an unknown name throws.
2. **`fieldBgQuiet` is a real token** (`fieldBgQuiet()`), as the table row in 3.1 implies:
   classic `0xFAFAFA`, arcane `0xFAF7F0`; used only by `ComicFindBar` at rest.
3. **`ComicOverlayPanel` close chip whites → `buttonPlate`.** The spec lists lines 118/124
   (`Color.WHITE`) as hardcodings but names no token; `buttonPlate` was used (classic `0xFFFFFF`,
   so classic stays identical). Review may prefer a dedicated token.
4. **Controls read the extra tokens from `AskAiTheme.active()`**, not from the `ComicPalette`
   passed to their constructor (the palette has no slots for them). A control built with a custom
   palette therefore still gets the active theme's greys/creams.
5. **`ComicButtonUI` border handling.** To avoid a Metal bevel under the rounded ink plate, the
   delegate replaces a `UIResource` border by insets-equal empty space; for a compound border
   (Metal: bevel + margin border) only the OUTSIDE part is flattened, the inside margin border
   stays live, so `setMargin(...)` keeps working (review fix, pinned). Custom borders are untouched.
   This also applies to buttons that opt out via `askai.plainButton` after installation (the
   property is evaluated at paint time only). Buttons with `contentAreaFilled == false` get no
   plate. Rollover is enabled via `LookAndFeel.installProperty` (explicit client settings win).
   Review fixes beyond the spec: opaque buttons fill the corners outside the plate with the parent
   background (opaque contract, no repaint garbage), and keyboard focus is shown as a thin
   navigation-blue inner ring (BasicButtonUI paints none, Metal's ring is gone with the delegate).
   Plate logic is the shared pure function `ComicButtonUI.plateFill(...)`, also used by `ComicButton`.
   Raw `JButton`s have no CRITICAL role — the delegate always uses the ACTION accent.
   GUI-gate watch points: LAF-internal JButtons also get the plate (e.g. `MetalComboBoxButton`,
   option-pane/file-chooser buttons, toolbar buttons lose Metal's rollover border).
6. **`ComicScrollBarUI.createUI`** returns the delegate only; it does not apply the opaque/slim
   preferred-size tweaks of `install(JScrollBar)` (those remain for the explicit call sites).
7. **`MenuBar.border`** (from the pre-existing `installMenuDefaults`) is still a plain
   `MatteBorder`, not a `BorderUIResource` — pre-existing code, left untouched.
8. **Tests live in `comic-controls`** only; the `:askai-app:test` filters from 6.4 match no tests
   there, so only `:comic-controls:test` + `:askai-app:compileJava` were run.
9. Open: GUI gates (default = arcane, and `-Daskai.theme=classic`) by the user.
