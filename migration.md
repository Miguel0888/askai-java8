# Migration zur geschichteten Gradle-Architektur

## Ziel

AskAI wird schrittweise von der derzeit überwiegend in `:app` liegenden Implementierung in klar getrennte Gradle-Subprojects überführt.

Die Modulgrenzen sollen die Abhängigkeitsrichtung bereits beim Kompilieren erzwingen:

```text
app
├── application
├── domain
├── services
├── ai-solon
├── plugin-api
└── plugin-pf4j

services ────────→ application ────────→ domain
ai-solon ───────→ application ────────→ domain
plugin-pf4j ────→ plugin-api ─────────→ application
```

Die UI darf Services und Use Cases verwenden. Fachliche und technische Services dürfen dagegen weder Swing noch Klassen aus `:app` kennen.

## Technischer Zielstack

- Java 8
- Gradle-Multiprojekt
- Swing für die Benutzeroberfläche
- FlatLaf für das Look-and-Feel
- Guice 5.1.0 für Dependency Injection und Lazy Provider
- PF4J für nachinstallierbare Erweiterungen
- Solon AI für Chat, Streaming, Tools, MCP und Agenten
- `ollama4j-java8` als bestehender Java-8-Ollama-Client während der Migration

## Aktuelle Gradle-Subprojects

### `:app`

Verzeichnis: `askai-app`

Verantwortung:

- Programmstart
- Swing-UI
- FlatLaf-Initialisierung
- Guice Composition Root
- Navigation und UI-Lifecycle
- Erzeugung beziehungsweise Anforderung lazy geladener Features
- Fat-JAR-Build

`app` darf alle für die Verdrahtung benötigten Module kennen. Fachlogik und technische Implementierungen sollen dort langfristig nicht verbleiben.

### `:domain`

Verantwortung:

- fachliche Modelle
- Value Objects
- fachliche Regeln und Zustände
- frameworkunabhängige Exceptions, sofern sie fachliche Bedeutung besitzen

Nicht erlaubt:

- Swing
- Guice
- PF4J
- Solon AI
- HTTP-Clients
- Dateisystem- oder Netzwerkinfrastruktur
- Abhängigkeiten auf andere AskAI-Module

### `:application`

Verantwortung:

- Use Cases
- Service-Ports und Gateway-Interfaces
- anwendungsbezogene Commands und Results
- Feature-Lifecycle-Verträge
- Orchestrierung fachlicher Abläufe

Erlaubte Abhängigkeit:

- `:domain`

Nicht erlaubt:

- Swing
- konkrete HTTP-Clients
- Solon AI
- PF4J-Runtime
- Implementierungen aus `:services`

### `:services`

Verantwortung:

- Implementierungen der Ports aus `:application`
- Konfigurationspersistenz
- Netzwerk- und Proxy-Infrastruktur
- Hugging-Face-Zugriffe
- bestehende Ollama-Integration, soweit sie nicht durch Solon AI ersetzt wird
- Speech-to-Text-Infrastruktur
- Datei- und Downloadoperationen

Erlaubte Abhängigkeiten:

- `:application`
- `:domain`
- `:ollama4j-java8`
- technische Drittbibliotheken

Nicht erlaubt:

- Swing
- UI-Dialoge
- Panels oder Frames aus `:app`

### `:ai-solon`

Verantwortung:

- Solon-AI-Adapter
- Chat- und Streaming-Implementierungen
- Tool- und Skill-Adapter
- MCP-Client und MCP-Server
- Agenten und ReAct
- spätere RAG-Integration

Erlaubte Abhängigkeiten:

- `:application`
- `:domain`
- Solon AI

Solon-Typen dürfen nicht in die öffentlichen Interfaces von `:application` gelangen.

### `:plugin-api`

Verantwortung:

- stabile Extension Points
- Plugin-Metadaten
- Verträge für Agenten, Tools, Modellanbieter und optionale UI-Features

Das Modul enthält keine PF4J-Runtime-Logik. Externe Plugins sollen möglichst nur gegen `:plugin-api`, `:application` und `:domain` kompilieren müssen.

### `:plugin-pf4j`

Verantwortung:

- PF4J `PluginManager`
- Plugin-Erkennung
- Plugin-Lifecycle
- ClassLoader-Isolation
- spätere Guice-Integration für PF4J-Extensions

Erlaubte Abhängigkeiten:

- `:plugin-api`
- `:application`
- `:domain`
- PF4J
- Guice

### `:ollama4j-java8`

Bestehender Java-8-kompatibler Ollama-Client. Das Modul bleibt zunächst unverändert und wird von `:services` verwendet. Seine langfristige Rolle wird nach der Solon-AI-Integration neu bewertet.

## Verbindliche Abhängigkeitsregeln

1. `:domain` hängt von keinem anderen AskAI-Modul ab.
2. `:application` hängt ausschließlich von `:domain` ab.
3. Technische Module implementieren Ports aus `:application`.
4. `:services`, `:ai-solon` und `:plugin-pf4j` dürfen niemals von `:app` abhängen.
5. Swing- und FlatLaf-Typen bleiben in `:app`.
6. Solon-AI-Typen bleiben in `:ai-solon`.
7. PF4J-Runtime-Typen bleiben in `:plugin-pf4j`.
8. Guice wird im Composition Root und bei der Plugin-Verdrahtung verwendet, nicht als globaler Service Locator.
9. Dependency Injection erfolgt über Konstruktoren. Field Injection ist nicht vorgesehen.
10. Netzwerkzugriffe, Modellabfragen und andere teure Operationen dürfen nicht in Konstruktoren ausgeführt werden.
11. Schwere Features werden über `Provider<T>` oder dedizierte Feature-Loader erst bei Bedarf initialisiert.
12. `api` wird in Gradle nur verwendet, wenn Typen einer Abhängigkeit Bestandteil der öffentlichen API eines Moduls sind. Ansonsten wird `implementation` verwendet.

## Ziel für das Startverhalten

Die sichtbare Anwendungshülle soll möglichst sofort erscheinen:

```text
Programmstart
    ↓
FlatLaf aktivieren
    ↓
Guice-Kern erzeugen
    ↓
MainFrame und Navigation anzeigen
    ↓
Benutzer kann sich orientieren
    ↓
Features bei der ersten Verwendung laden
```

Beim Start nicht automatisch ausführen:

- Ollama-Verbindung aufbauen
- Modelllisten abrufen
- Hugging Face abfragen
- MCP-Server verbinden
- Agenten initialisieren
- RAG-Indizes laden
- Audio-Subsystem initialisieren

## Migrationsstrategie

Die Migration erfolgt in kleinen, jederzeit baubaren Schritten. Große Paketverschiebungen ohne Zwischentests sind zu vermeiden.

### Phase 1: Gradle-Grenzen vorbereiten

Status: abgeschlossen

- Subprojects anlegen
- `askai-app` logisch als `:app` registrieren
- zentrale Java-8-Konfiguration definieren
- Frameworkversionen zentralisieren
- FlatLaf einbinden
- Java-8-CI für Pull Requests ergänzen
- Release- und Fat-JAR-Build auf `:app` umstellen

### Phase 2: Fachliche Verträge herauslösen

Ziel:

- öffentliche Service-Interfaces aus bestehenden Implementierungen extrahieren
- Request-, Result- und fachliche Modelltypen einordnen
- UI-spezifische Rückgabewerte aus Service-Verträgen entfernen

Vorgehen:

1. Für einen Anwendungsfall den aktuellen UI-Aufruf identifizieren.
2. Einen fachlich benannten Port oder Use Case in `:application` definieren.
3. Benötigte fachliche Modelle nach `:domain` verschieben.
4. Bestehende Implementierung gegen den neuen Port anpassen.
5. UI ausschließlich gegen den Port kompilieren lassen.
6. Build und Tests ausführen.

Mögliche erste Kandidaten:

- `OllamaService`
- `SpeechToTextService`
- Chat-Senden und Streaming
- Laden installierter Modelle
- Laden laufender Modelle
- Hugging-Face-Suche
- Modellinstallation

### Phase 3: Technische Services nach `:services` verschieben

Voraussichtliche Paketgruppen:

```text
config/
net/
hf/
stt/
client/
service/
settings/
```

Die Paketnamen sind nicht automatisch gleichbedeutend mit der Zielschicht. Jede Klasse wird vor dem Verschieben geprüft.

Prüffragen:

- Enthält die Klasse fachliche Regeln? Dann gehört dieser Teil eventuell in `:domain`.
- Orchestriert sie einen Anwendungsfall? Dann gehört dieser Teil eventuell in `:application`.
- Führt sie HTTP-, Datei-, Proxy- oder Ollama-Zugriffe aus? Dann gehört sie in `:services`.
- Importiert sie Swing? Dann muss die UI-Abhängigkeit vor dem Verschieben entfernt werden.
- Gibt sie UI-Texte oder Dialogentscheidungen zurück? Dann ist der Vertrag zu entkoppeln.

### Phase 4: Guice als Composition Root einführen

Ziel:

- manuelle Konstruktion in `AskAiJava8App` durch klar definierte Guice-Module ersetzen
- Interfaces auf Implementierungen binden
- Scopes bewusst festlegen
- schwere Komponenten lazy bereitstellen

Grundregeln:

- `Injector` nur im Bootstrap erzeugen.
- `Injector.getInstance(...)` nicht quer durch die Anwendung verwenden.
- Controller erhalten Abhängigkeiten per Constructor Injection.
- Schwere Features erhalten `Provider<T>` oder dedizierte Loader.
- Nicht jede Klasse wird automatisch zum Singleton.

### Phase 5: Solon AI integrieren

Ziel:

- Solon AI hinter AskAI-eigenen Ports integrieren
- bestehende Chat- und Agentenfunktionen schrittweise adaptieren
- Ollama-spezifische Details aus UI und Application Layer entfernen

Reihenfolge:

1. Chat-Adapter ohne Agenten und MCP
2. Streaming
3. Chat-Sessions
4. Tools
5. MCP
6. ReAct-Agenten
7. optionale RAG- und Flow-Funktionen

Während der Migration können die bestehende `ollama4j-java8`-Implementierung und Solon AI parallel hinter verschiedenen Implementierungen desselben Ports existieren.

### Phase 6: PF4J integrieren

Ziel:

- stabile Extension Points definieren
- externe Agenten, Tools oder Anbieter als Plugins laden
- Plugin-Erzeugung später mit Guice verbinden

PF4J wird nur für tatsächlich installierbare oder austauschbare Erweiterungen verwendet. Fest eingebaute Features bleiben normale Gradle-Subprojects.

### Phase 7: `:app` bereinigen

Abschlussziel für `:app`:

```text
bootstrap/
ui/
controller/
guice/
```

Nicht mehr in `:app` enthalten:

- HTTP-Clients
- Ollama-Protokollklassen
- Hugging-Face-Clients
- Konfigurationspersistenz
- Downloadimplementierungen
- Speech-to-Text-Backends
- Solon-AI-Adapter
- PF4J-Runtime

## Reihenfolge für konkrete Klassenmigrationen

Die folgende Reihenfolge reduziert zyklische Abhängigkeiten:

1. kleine Value Objects und Results ohne Swing-Abhängigkeit
2. Service-Interfaces
3. Konfigurationsmodelle
4. Konfigurationsrepository
5. Netzwerk-, Proxy- und Zertifikatsklassen
6. Ollama-Client-Adapter
7. Hugging-Face-Client und Downloads
8. Speech-to-Text-Backend
9. übergreifende Service-Orchestrierung
10. Guice-Bindings
11. Solon-AI-Adapter
12. PF4J-Runtime

## Umgang mit UI-Abhängigkeiten in Services

Ein Service darf keine Dialoge öffnen und keine Swing-Komponenten verändern.

Stattdessen liefert er strukturierte Ergebnisse oder meldet Ereignisse:

```text
Service
    ↓
Result / Progress Event / Exception
    ↓
Controller
    ↓
Swing View
```

Fortschritt, Bestätigung und Fehlerdarstellung bleiben Aufgaben der UI beziehungsweise des Controllers.

## Lazy Loading

Gradle-Subprojects begrenzen die statischen Abhängigkeiten. Lazy Loading ist eine zusätzliche Laufzeitanforderung.

Vorgesehene Feature-Zustände:

```text
NOT_LOADED
LOADING
READY
FAILED
STOPPED
```

Ein Feature darf beim ersten Zugriff im Hintergrund initialisiert werden. Swing darf dabei nicht auf dem Event Dispatch Thread blockiert werden.

Kandidaten für Lazy Loading:

- Chat-Runtime
- Modellverwaltung
- Hugging-Face-Katalog
- Speech-to-Text
- MCP-Runtime
- Agenten-Runtime
- RAG-Wissensbasis

## Build und Prüfung

Vollständiger Build unter Java 8:

```bash
bash "$ROOT_DIR/gradlew" --no-daemon clean build
```

Fat JAR:

```bash
bash "$ROOT_DIR/gradlew" --no-daemon :app:fatJar
```

ChatGPT-kompatibler Build:

```bash
bash chatgpt-build.sh
```

Für lokale und automatisierte Builds gilt:

```text
GRADLE_USER_HOME=$ROOT_DIR/.chatgpt/gradle-home
```

## Definition of Done je Migrationsschritt

Ein verschobener Anwendungsfall gilt erst als migriert, wenn:

- die Klasse im fachlich richtigen Subproject liegt,
- die Gradle-Abhängigkeitsrichtung eingehalten wird,
- kein technisches Framework in `:domain` oder `:application` sichtbar ist,
- kein Service Swing importiert,
- die UI ausschließlich gegen Application-Ports oder Use Cases arbeitet,
- Konstruktoren keine teuren I/O-Operationen ausführen,
- Lazy Loading das UI nicht blockiert,
- der Java-8-Build erfolgreich ist,
- bestehendes Verhalten erhalten bleibt,
- relevante Tests im richtigen Modul liegen.

## Nichtziele dieser Migration

- keine vollständige Neuentwicklung der UI
- kein Wechsel von Swing zu JavaFX
- keine gleichzeitige Aufteilung jedes Features in ein eigenes Subproject
- keine direkte Kopplung der Anwendungsschicht an Solon AI
- keine Ersetzung des bestehenden Ollama-Clients ohne funktionsgleichen Migrationspfad
- kein Einsatz von OSGi oder einer anderen zusätzlichen Plugin-Plattform
- keine Java-Sprachfeatures oberhalb von Java 8

## Aktueller Übergangszustand

Während der Migration enthält `:app` weiterhin direkte Abhängigkeiten auf bestehende technische Bibliotheken und Implementierungsmodule. Das ist vorübergehend notwendig, damit AskAI während der schrittweisen Verschiebung ausführbar bleibt.

Dieser Übergang ist beendet, sobald:

- alle technischen Implementierungen aus `:app` entfernt sind,
- der Composition Root ausschließlich Bindings und Bootstrap-Code enthält,
- UI-Klassen nur noch `:application`, `:domain` und UI-eigene Typen verwenden,
- Frameworktypen ausschließlich in ihren jeweiligen Infrastrukturmodulen verbleiben.
