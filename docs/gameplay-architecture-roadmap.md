# Gameplay-Architektur: schneller Turn-Start und konsistente Welt

## Zweck und Leitentscheidung

Dieses Papier beschreibt eine schrittweise Erweiterung des Spiels für zwei lokale, CPU-basierte LLMs. Das Primary-LLM erzählt sichtbar; ein kleineres Secondary-LLM erledigt parallel vorbereitende und strukturierende Aufgaben. Ziel ist bessere Kontinuität, Erinnerungen und Regieimpulse, ohne die Wartezeit zwischen **Absenden** und dem ersten sichtbaren Token zu erhöhen.

Der strukturierte Weltzustand ist die maßgebliche Wahrheit für prüfbare Spielfakten. Die Erzählung bleibt frei für Ton, Beschreibungen, Dialoge und kleine improvisierte Details. Das ist ein Geländer für das Modell, kein Drehbuch und keine Railroad.

## Ausgangslage

Ein Turn hat drei für das Design relevante Zeiträume:

1. **Phase 1 – sichtbare Generierung:** Das Primary-LLM streamt die Spielleiterantwort. Eine Rate von ungefähr 5–6 Token/s reicht, solange sie den Lesefluss nicht bremst.
2. **Phase 2 – Nutzer tippt:** Diese Zeit ist für den Nutzer kostenlos. Hier dürfen Hintergrundaufgaben laufen und der exakt getippte Aktionstext wird für das Primary-LLM vorab gecacht. Ihre Dauer ist nicht planbar.
3. **Phase 3 – Nutzer hat abgesendet:** Bis zum ersten Ausgabetoken ist jede zusätzliche Wartezeit sichtbar und daher kritisch.

Nach dem sichtbaren Stream werden bereits ein Runtime-Fragment gesucht, bewertet und der kanonische Prompt-Prefix für den nächsten Turn gewärmt. Während des Tippens ergänzt ein debounctes Prefill den Aktionstext. Diese Cache-Kette bleibt die zentrale Performance-Invariante.

Ein erster CPU-Schnappschuss zeigt genügend Parallelität für einen Versuch mit beiden Modellen: primary model auf vier Kernen lag allein bei 35,7 Prompt- und 10,2 Generierungs-Token/s; parallel zu secondary model auf zwei Kernen bei 38,4 und 7,9 Token/s. Mit vier Kernen je Modell lagen die Messwerte bei 34,6 und 8,9 Token/s für das Primary-Modell. Diese Werte sind keine Garantie; sie sind aber ausreichend, um eine messbar abgesicherte Zwei-Modell-Architektur zu verfolgen.

## Unverhandelbare Latenz- und Sicherheitsregeln

- Das Primary-LLM hat in Phase 3 absolute Priorität, außer während einer seltenen, geplanten Primary-Kontextkompression. Beim Absenden wird keine neue Secondary-Arbeit gestartet. Bereits laufende Secondary-Arbeit darf kooperativ auslaufen, darf aber weder einen Dienst-Queue-Slot noch den Start des Primary-Turns blockieren.
- Kein Ergebnis des Secondary-LLM liegt auf dem kritischen Turn-Pfad. Ist es beim Absenden nicht bereits kanonisch übernommen, verändert es weder den laufenden Turn noch dessen Primary-Prompt. Es darf als versionierter Analyse-Kandidat für einen späteren sicheren Commit erhalten bleiben.
- Der kanonische Primary-Prompt bleibt deterministisch: Anweisung, Original-Szenario, kanonische Historie, neue Aktion. Jeder eingefügte Kontext muss vor dem passenden Warmup feststehen.
- Prefill und Turn verwenden weiterhin dieselbe `sessionId` und denselben behaltenen Primary-Slot. Cache-Reuse wird anhand der für die verwendete llama.cpp-Version bestätigten Reuse- und Evaluierungszähler geprüft; bei aktuellen Versionen sind dies `timings.cache_n` und `timings.prompt_n`.
- LLM-Ausgaben sind untrusted input. Das Secondary-LLM darf nur ein enges, schema-validiertes Ergebnis vorschlagen; deterministische Logik entscheidet über Übernahme, Begrenzung und Konflikte.
- Szenario, Verlauf, Aktionen und LLM-Text gehören nicht in Betriebslogs. Messungen verwenden Request-ID, Dauer, Cache-Metriken und Klassifikationen.

## Zielbild

### Rollen der Komponenten

| Komponente | Verantwortung | Darf nicht tun |
| --- | --- | --- |
| Primary-LLM | Sichtbare Spielleiterantwort, Primary-Prompt-Cache, Streaming | Auf Phase-3-Nebenarbeit warten |
| Secondary-LLM | Kurze strukturierte Vorschläge, Rerank, State-Patches, Erinnerungs-Extraktion, Regieoptionen | Die Erzählung direkt ausgeben oder den Turn blockieren |
| Weltzustands-Reducer | Schemata prüfen, erlaubte Übergänge anwenden, Konflikte erkennen | Kreative Prosa erzeugen |
| Memory-Index | Akzeptierte episodische Erinnerungen abrufen | Autoritative Fakten definieren |
| Orchestrator | Fristen, Priorität, Startgrenzen, Commit-Zeitpunkte und Metriken | Fachliche Spielentscheidungen treffen |

### Weltzustand: klein, explizit und maßgeblich

Der State enthält nur Fakten, die sich bestimmen oder prüfen lassen:

- aktueller Ort, Zeit und bekannte Reisezeiten;
- anwesende NPCs, Zustände und wichtige Beziehungen;
- Inventar, Türen, Ressourcen und andere überprüfbare Objekte;
- Geheimnisse mit Enthüllungsstufe statt freiem Geheimtext;
- offene Ereignisuhren mit klaren Auslösebedingungen;
- bestätigte Folgen vergangener Handlungen.

Er enthält ausdrücklich nicht die vollständige Handlung, sprachliche Stimmung oder alle Beschreibungsdetails. Für jeden neuen Turn erhält das Primary-LLM einen kurzen kanonischen Snapshot. Seine Antwort darf die Welt farbig ausgestalten, soll aber keine harten Fakten überschreiben.

Ein erkannter Widerspruch wird nicht mitten im Stream korrigiert. Der Nachlauf verwirft den unzulässigen Patch und legt eine knappe, nächster-Turn-taugliche Reparatur-Notiz an. So verbreitet sich der Fehler nicht weiter, ohne den sichtbaren Turn oder dessen Tempo zu beschädigen.

### Konkretes minimales State-Modell

Der erste State ist kein allgemeines Rollenspiel-Regelwerk. Er besteht aus stabilen IDs und Zuständen, die der Reducer ohne Sprachverständnis prüfen kann. Jede Session besitzt eine monoton steigende `revision`; jeder Patch nennt seine Basisrevision. Ein verspäteter oder zu einer anderen Historie gehörender Patch wird nicht übernommen.

```yaml
revision: 17
time: { day: 2, minute-of-day: 1335 }
player:
  location: quay
  inventory: [brass-compass, warehouse-key]
actors:
  mira: { location: quay, status: wary }
location-state:
  quay: { signal-fire: unlit }
items:
  warehouse-key: { holder: player, state: intact }
facts: [mira-promised-a-meeting]
player-knowledge: [mira-knows-the-smuggling-route]
clocks:
  patrol-arrives: { progress: 2, threshold: 4 }
```

In den Primary-Prompt kommt nicht der ganze State, sondern ein deterministisch gebauter lokaler Snapshot: aktueller Ort, erlaubte Ausgänge, sichtbare Akteure und Objekte, unmittelbar nutzbares Inventar, relevante bekannte Fakten und fällige Uhren.

```text
AUTHORITATIVE STATE — do not contradict:
Day 2, 22:15; player at quay.
Here: Mira (wary), unlit signal fire.
Exits: market, lighthouse path. Inventory: brass compass, warehouse key.
Known: Mira knows a smuggling route. Pressure: patrol arrives in 2 steps.
```

Die IDs und erlaubten Übergänge stammen aus der Frontmatter; der beschreibende Szenariotext bleibt frei formuliert.

```yaml
world-state:
  locations:
    quay: { exits: { market: 5, lighthouse-path: 10 } }
  actors:
    mira: { home: quay, initial-status: wary }
  items: [brass-compass, warehouse-key]
  facts: [mira-promised-a-meeting]
  clocks:
    patrol-arrives: { threshold: 4 }
```

Der erste Ausbauschritt enthält nur Ort, Zeit, anwesende Akteure, Inventar und bestätigte Flags. Beziehungen, Wissenslisten, Objektzustände und Ereignisuhren folgen erst, wenn der Kern zuverlässig funktioniert.

### Herkunft von IDs und Fakten

`brass-compass` ist eine **autorisierte Entitäts-ID** aus der
Szenariodefinition, genauer aus `world-state.items`. Gleiches gilt für Orte,
NPCs und Ereignisuhren. Das Secondary-LLM darf beispielsweise den Besitzer
des Kompasses ändern, aber im ersten Ausbau keine neue Entität mit frei
erfundenem Namen anlegen.

`mira-promised-a-meeting` ist ein **autorisierter Fact-Key** aus
`world-state.facts`. Die sichtbare Erzählung kann die Bedingung dafür
erfüllen; der State-Patch darf dann nur den vorab bekannten Key setzen oder
löschen. Damit bleibt für den Reducer prüfbar, welche Fakten überhaupt Teil
der Spielmechanik sein dürfen.

Nicht vorab deklarierte Details bleiben zunächst Erzählung oder episodische
Erinnerung, aber kein harter Weltzustand. Wenn später dynamische Entitäten
oder offene Versprechen wichtig werden, ist das eine eigene Erweiterung: Sie
braucht einen begrenzten, schema-definierten Erzeugungsmechanismus und darf
nicht einfach beliebige Modell-IDs akzeptieren. Eine optionale
Start-Planungsphase kann die autorisierten IDs vor Spielbeginn automatisch
aus einer Campaign Bible erzeugen; sie erweitert die erlaubte Menge danach
nicht stillschweigend.

### State-Patches und deterministische Übernahme

Nach der sichtbaren Antwort erstellt das Secondary-LLM einen kleinen strukturierten **Vorschlag**, nicht die neue Wahrheit:

```yaml
base-revision: 17
advance-minutes: 5
operations:
  - { type: move-player, to: market }
  - { type: move-actor, actor: mira, to: warehouse }
  - { type: set-flag, flag: mira-promised-a-meeting }
```

Der Reducer übernimmt jede Operation nur, wenn IDs existieren, ein erlaubter Weg besteht, Besitz und Objektzustand passen, die Uhr gültig ist und die Basisrevision aktuell bleibt. Unbekannte IDs, unmögliche Wege und widersprüchliche Operationen werden verworfen.

Zusätzlich gilt eine Spielregel: Ein Patch schreibt nur Folgen fest, die die sichtbare Antwort klar als geschehen darstellt. „Der Spieler will zum Markt“ bewegt niemanden; erst „Nach dem Weg zum Markt ...“ kann eine Bewegung belegen. Im Zweifel bleibt der State unverändert — eine fehlende Änderung ist weniger schädlich als eine falsche Tatsache.

### Spielmechaniken mit sichtbarem Nutzen

| Mechanik | Autoritative Regel | Wirkung für den Spieler |
| --- | --- | --- |
| Räumliche Kontinuität | Spieler und Akteure haben genau einen Ort; Bewegung braucht Ausgang oder erlaubte Ausnahme. | Niemand taucht ohne Erklärung am falschen Ort auf; Reise und Verfolgung erhalten Gewicht. |
| Zeit und Druck | Wege und längere Handlungen erhöhen Zeit; Uhren lösen bei Schwellenwerten deklarierte Ereignisse aus. | Zögern hat nachvollziehbare Folgen statt bloß dekorativer Zeitangaben. |
| Gegenstände | Ein Item besitzt einen Ort/einen Besitzer und optional einen Zustand. | Finden, verlieren, verwenden und Versprechen mit Gegenständen bleiben bedeutsam. |
| NPC-Kontinuität | Ort und Status eines NPC sind autoritativ. | Gesprächston, Verfügbarkeit und Reaktionen bauen aufeinander auf. |
| Wissensgrenzen | Weltfakten und `player-knowledge` sind getrennt. | Der Spielleiter verrät keine Geheimnisse allein durch Hintergrundwissen. |
| Konsequenzketten | Bestätigte Flags und Uhren sind spätere Auslöser. | Handlungen werden wieder aufgegriffen statt nach einer Antwort zu verschwinden. |

Der State beantwortet nicht „darf der Spieler das?“, sondern stellt belastbare Ausgangslagen bereit: Wo ist die Figur, wer ist erreichbar, was ist vorhanden, was weiß sie und welche Konsequenzen laufen bereits. Das Primary-LLM kann weiterhin kreative Umwege und überraschende Lösungen beschreiben; der Reducer akzeptiert diese später nur mit einem erlaubten Übergang oder markiert sie für eine sanfte Reparatur im Folgekontext.

### Beispiel einer spielerischen Folge

Die Patrouillen-Uhr steht bei `2/4`, Mira ist am Kai und der Spieler besitzt den Lagerschlüssel. Der Spieler kann frei verhandeln, die Patrouille auskundschaften, Mira folgen oder zum Lager laufen. Jede klare Ortsbewegung oder längere Handlung erhöht die Zeit. Bei `4/4` erscheint die Patrouille; das ist keine Skriptsequenz, sondern eine neue Lage, auf die der Spieler wieder frei reagieren kann. Der State sorgt nur dafür, dass die Patrouille nicht zugleich fern ist, Mira nicht zugleich am Kai und im Lager spricht und der Schlüssel nicht plötzlich wieder am Boden liegt.

Das ist der Qualitätsgewinn: Die Welt merkt sich Folgen und übt Druck aus, ohne die kreative Antwort des Primary-LLM oder die Wahlfreiheit des Spielers zu ersetzen.

### Gedächtnis und Regie

Zusätzlich zum State gibt es zwei nicht-autoritative Ebenen:

- **Episodische Erinnerungen:** Sehr kurze, abgerufene Einträge wie ein Versprechen, eine Lüge oder eine beobachtete Schwäche. Nur akzeptierte, deduplizierte Einträge landen im Vector Store.
- **Regieimpulse:** Kandidaten für Spannung, Konsequenzen und die nächste sinnvolle Szene. Sie bleiben optional und dürfen nie Fakten oder Spielerfreiheit erzwingen.

Das heutige Runtime-Fragment ist ein früher Vertreter eines Regieimpulses. Seine semantische Suche und sein Einfügen bleiben kompatibel mit dem neuen Modell: Der State gibt harte Grenzen, Memory liefert relevante Vergangenheit, und ein Fragment bringt bei Bedarf kreative Richtung.

## Secondary-LLM-Aufgaben nach Phase

| Phase | Sinnvolle Arbeit | Umgang mit Ergebnis |
| --- | --- | --- |
| 1: Primary streamt | laufende Ereignisuhren prüfen, mögliche Folge-Impulse aus dem bisherigen State vorbereiten | rein spekulativ, darf über Phase 3 hinaus auslaufen |
| Übergang 1 zu 2 | letzte Aktion und vollständige Antwort in einen `statePatch`, Erinnerungen und Konflikthinweise überführen | schema-validieren; erst dann kanonisch übernehmen |
| 2: Nutzer tippt | Runtime-Fragment-/Memory-Kandidaten bewerten, Aktions-Intent grob schätzen, Regieoptionen vorbereiten | nur vor dem Commit-Zeitpunkt verwendbar |
| 3: abgesendet | keine neue Nebenarbeit starten | Bereits laufende Secondary-Arbeit kann auslaufen; Primary-Prefill und Turn starten sofort — außer eine geplante Primary-Kompression läuft noch, die zuerst fertiggestellt wird |

Der Aktions-Intent ist absichtlich grob, zum Beispiel `move`, `inspect`, `talk`, `use-item` oder `attack`. Er kann Retrieval vorbereiten, ersetzt aber nie den Prefill des tatsächlich abgesendeten Textes.

## Konfigurierbarkeit in der Frontmatter

Die Frontmatter soll Gameplay steuern, ohne zu einem zweiten Prompt-Skript zu werden. Neue Felder werden erst eingeführt, wenn die jeweilige Roadmap-Phase sie tatsächlich braucht. Geeignete spätere Optionen sind:

```yaml
world-state:
  locations: strict
  time: enabled
  inventory: enabled
memory:
  mode: episodic
  max-active: 3
events:
  clocks: enabled
gameplay:
  hint-density: medium
  continuity: strict
planning:
  enabled: true
```

`strict` bedeutet dabei: harte Zustandsübergänge werden deterministisch geprüft. Es bedeutet nicht, dass das Modell keine neuen Ideen oder Orte einführen darf; dafür muss es einen ausdrücklich erlaubten Übergang geben.

## Optionale Planungsphase vor Spielbeginn

Für umfangreichere Szenarien kann ein einmaliger, etwas langsamerer Start akzeptabel sein. Die Planungsphase erzeugt keine ausgeschriebene Handlung, sondern eine kompakte Campaign Bible:

- Ortsgraph mit Reisezeiten;
- Ziele, Wissen und Beziehungen wichtiger NPCs;
- Geheimnisse mit Bedingungen für Enthüllungen;
- Ereignisuhren und Konsequenzen bei Untätigkeit;
- gewünschter Ton, Pacing und Grenzen für Zufall.

Die Campaign Bible wird in denselben strukturierten Zustand überführt und steht damit dem deterministischen Reducer zur Verfügung. Sie ist ein Rahmen für freie Entscheidungen, kein vorab festgelegter Lösungsweg.

## Geplante Kontextkompression

Bei langen Spielen wird die vollständige Rohhistorie zu groß für einen
effizienten Prompt. Ein Sliding Window wäre zwar einfach, würde aber bei
jedem Turn einen anderen Prefix erzeugen und damit den Primary-Cache laufend
entwerten. Stattdessen verwendet das Spiel seltene, explizite
**Kontext-Epochen**.

### Checkpoint plus Roh-Tail

Ein Checkpoint-Turn wird über das Tokenbudget bestimmt, nicht starr über eine
Turnnummer. In einem typischen Beispiel erzeugt das Primary-LLM nach Turn 10
einen Recap der Turns 1–10. Dieser Recap bleibt zunächst nur ein abgeleitetes
Artefakt. Bis Turn 13 läuft das Spiel mit der unveränderten Rohhistorie und
dem bestehenden Cache weiter. Beim Wechsel wird daraus:

```text
Instruktion + Szenario + autoritativer State
+ Primary-Recap für Turns 1–10
+ Roh-Turns 11–13
+ neue Aktion
```

Der Wechsel bricht den Cache genau einmal. Anschließend wird der neue Prefix
noch während der Tippzeit gewärmt und kann wieder wie üblich prefetched
werden. Die vollständige Rohhistorie bleibt unverändert in IndexedDB; der
Recap ist nur eine versionierte Prompt-Repräsentation und kann bei Bedarf aus
dem Originalmaterial neu erstellt werden.

Der Recap enthält keine autoritativen Weltfakten, denn diese stehen weiterhin
im separaten State-Snapshot. Er komprimiert nur erzählerische Kontinuität:
gelöste Ereignisse, offene Fäden, Beziehungen, Spielerabsichten und Ton. Er
trägt mindestens `coversThroughTurn`, `stateRevision` und eine
Quell-Fingerabdruck, damit er nicht auf eine andere Session- oder
Historienversion angewendet wird.

### Timing und bewusst akzeptierter Preis

Variante 2 ist die gewählte Strategie: Der Primary-Recap startet planmäßig
nach seinem Checkpoint-Turn in Phase 2 und nutzt den noch passenden
Primary-Cache. Er wird nicht abgebrochen. Sendet der Nutzer ungewöhnlich
schnell, während der Recap noch generiert wird, wartet der folgende Turn bis
zum Ende dieser Wartungsarbeit. Danach kann der normale Turn den gemeinsamen
Prefix bis zum Checkpoint wiederverwenden.

Das ist eine bewusste Ausnahme von der sonstigen Phase-3-Priorität. Sie darf
nur selten auftreten und muss messbar bleiben: Recap-Dauer,
Absenden-bis-erstes-Token während der Wartung, Cache-Metriken vor und nach
dem Epochenwechsel sowie die Zahl betroffener Schnell-Sends. Der Vorteil ist
ein hochwertiger Recap aus dem vollständigen Originalkontext; ein
Secondary-Recap bleibt möglich als Vergleich oder Fallback, ist aber nicht
die bevorzugte Quelle.

Die Soft-Grenze muss so früh liegen, dass bis zur harten Kontextgrenze noch
mindestens ein vollständiger Turn möglich ist. Reicht ein Recap nicht
rechtzeitig aus, bleibt die alte Epoche aktiv; die nächste geplante Wartung
startet früher. Erst nahe der harten Grenze darf der Scheduler den
Epochenwechsel zwingend vorziehen.

## Roadmap nach Qualitätsgewinn und Gesamtaufwand

Jede Etappe ist für sich auslieferbar. Die Bewertung hat drei getrennte
Dimensionen: **Qualitätsgewinn** im Gameplay, **Implementierungsaufwand** und
**Performance-Preis** für Primary-Latenz, -Durchsatz oder Cache-Reuse. Eine
Mess- oder Infrastruktur-Etappe wird nicht künstlich als hoher
Qualitätsgewinn bewertet. Die Reihenfolge bevorzugt zunächst hohe sichtbare
Qualität bei niedrigem Gesamtaufwand; technische Voraussetzungen kommen nur
vorher, wenn sie das Phase-3-Risiko direkt begrenzen.

### 0. Mess- und Prioritätsgrundlage

**Qualitätsgewinn: keiner direkt. Implementierungsaufwand: mittel.
Performance-Preis: sehr niedrig.** Diese Etappe verbessert das sichtbare Gameplay
noch nicht, verhindert aber, dass eine spätere Verbesserung nur gefühlt
schnell wirkt oder unbemerkt Phase 3 verschlechtert.

- Primary-Metriken pro Turn erfassen: Absenden bis erstes Token, Prompt- und Generierungsrate sowie die backendseitig gemeldeten Reuse- und Evaluierungszähler.
- Secondary-Metriken getrennt erfassen: Aufgabe, Start-/Endstatus, Dauer, rechtzeitig/fallback und CPU-Zuordnung.
- Einen expliziten Prioritätsvertrag einführen: Ab Phase 3 startet keine neue Secondary-Arbeit; bereits laufende Jobs können auslaufen, aber kein Background-Job darf die Turn-Queue blockieren.
- Mit reproduzierbaren Szenarien mindestens Primary-allein, 4+2 und 4+4 Kerne vergleichen. Erfolg heißt: keine statistisch relevante Verschlechterung der Primary-Time-to-first-token und keine Verschlechterung der Cache-Metrik.

Diese Etappe verändert weder Prompt noch sichtbares Gameplay.

### 1. Nicht-blockierender Commit-Vertrag für optionale Nacharbeit

**Qualitätsgewinn: indirekt hoch. Implementierungsaufwand: mittel.
Performance-Preis: niedrig.** Diese Etappe bringt selbst noch
keine neue Spielmechanik, macht aber State, Erinnerungen und Regieimpulse
erst nutzbar, ohne einen schnell abgesendeten Turn an ihre Fertigstellung zu
binden.

- Nach einem sichtbaren Turn wird sofort ein kanonischer **Basis-Warmup**
  gesichert, der keine noch unentschiedene Nebenanalyse benötigt.
- Jeder optionale Secondary-Befund trägt eine Historien- und State-Version.
  Er darf den aktuellen Primary-Prompt nur ändern, wenn er vor dessen
  verbindlichem Commit fertig und validiert ist.
- Trifft er später ein, bleibt er als Kandidat für einen späteren sicheren
  Commit erhalten; er hält weder Slot noch Turn zurück und wird nicht
  rückwirkend in den bereits gewärmten Prefix geschrieben.
- Ein Regressionstest deckt den Grenzfall ab: Der Nutzer sendet unmittelbar
  nach dem Stream. Der Turn startet auf dem Basis-Prefix, ohne auf optionale
  Nacharbeit zu warten.

Der aktuelle fragmentbasierte Rerank wird an diesem Vertrag ausgerichtet;
erst danach lohnt es sich, seinen Modellanbieter zu wechseln.

### Experimentspur: Secondary-LLM als Shadow-Reranker

**Qualitätsgewinn: unbewiesen. Implementierungsaufwand: niedrig bis mittel.
Performance-Preis: niedrig, zu messen.** Der Rerank ist ein gutes,
geschlossenes Experiment für das kleine Modell, aber kein hoher
Gameplay-Nutzen an sich: Der bestehende Primary-Rerank liefert bereits eine
Entscheidung. Deshalb läuft er parallel zu Etappe 1, blockiert aber weder die
Roadmap noch wird er zum Architektur-Fundament.

- Das Secondary-Modell erhält eine kurze, feste Rerank-Anfrage und liefert
  ausschließlich einen Score in einem engen Schema.
- Für eine Beobachtungsphase entscheiden weiterhin die heutigen Regeln;
  Abweichungen, Latenzen und akzeptierte Kandidaten werden datensparsam
  gezählt.
- Nur wenn die Auswahlqualität mindestens gleichwertig ist und der
  nicht-blockierende Commit-Vertrag erhalten bleibt, kann der
  Secondary-Rerank den Primary-Rerank ersetzen.

### 2. Minimaler deterministischer Weltzustand

**Qualitätsgewinn: hoch. Implementierungsaufwand: mittel bis hoch.
Performance-Preis: niedrig bis mittel.** Dies liefert die sichtbarste
Kontinuitätsverbesserung und ist Grundlage für Erinnerungen und Ereignisse.
Der zusätzliche Secondary-Nachlauf und der kurze State-Snapshot sind der
bewusst akzeptierte, zu messende Performance-Preis.

- Ein kleines, versionsfähiges State-Schema einführen: Ort, Zeit, NPC-Anwesenheit, Inventar und bestätigte Flags.
- Der Secondary-Nachlauf erzeugt nach einem abgeschlossenen sichtbaren Turn einen begrenzten Patch; der Reducer prüft Schema, Referenzen und erlaubte Übergänge.
- Zunächst nur akzeptierte Patches persistieren und bei Wiederaufnahme rekonstruieren; der Browser bleibt Eigentümer der Sitzung und des Zustands.
- Zunächst State-Patches im Shadow-Modus gegen echte Turn-Verläufe bewerten.
  Erst danach einen sehr kurzen, bereits akzeptierten Snapshot als
  kanonischen Kontext vor dem nächsten Primary-Warmup einfügen. Ist der neue
  Patch beim Commit nicht bereit, bleibt der letzte akzeptierte Snapshot
  gültig.
- Konflikte, verworfene Patches und Snapshot-Größe messen, aber keinen Spielinhalt loggen.

Der erste Umfang bleibt absichtlich klein. Kampfwerte, komplexe Quest-Logik und prozedurale Weltsimulation gehören nicht in diese Etappe.

### 3. Episodische Erinnerungen mit Retrieval

**Qualitätsgewinn: mittel bis hoch. Implementierungsaufwand: mittel.
Performance-Preis: niedrig bis mittel.** Der State verhindert harte
Widersprüche; dieser Schritt lässt die Welt langfristig erinnernd wirken.
Sein Prompt- und Retrieval-Budget muss strikt begrenzt bleiben.

- Das Secondary-Modell schlägt höchstens wenige, kurze Erinnerungen aus dem bestätigten Turn vor.
- Ein deterministischer Filter entfernt Duplikate, Unsicherheiten, widersprüchliche Aussagen und Einträge ohne späteren Spielwert.
- Der bestehende Vector Store wird um diese akzeptierten Erinnerungen ergänzt.
- Bei Retrieval konkurrieren Erinnerungen mit Runtime-Fragmenten nicht um unkontrollierten Prompt-Platz: ein Budget wählt wenige, relevante Einträge.

Die Erinnerung ist kein Faktenspeicher. Harte Tatsachen bleiben allein im Weltzustand.

### 4. Geplante Primary-Kontextkompression

**Qualitätsgewinn: hoch in langen Spielen. Implementierungsaufwand: mittel.
Performance-Preis: selten, aber im Einzelfall hoch.** Diese Etappe erhält
Langzeitkontinuität und Cache-Effizienz, akzeptiert jedoch bewusst eine
gelegentliche Phase-3-Wartezeit für einen hochwertigen Primary-Recap.

- Tokenbudget, Soft- und Hard-Grenze auf Basis des tatsächlichen Prompt-
  Budgets einführen.
- Einen versionierten Primary-Recap als Checkpoint-Artefakt erzeugen und die
  vollständige Rohhistorie im Browser behalten.
- Nach der festgelegten Anzahl Roh-Turns den atomaren Epochenwechsel auf
  `State + Recap + Tail` ausführen und den neuen Prefix wärmen.
- Die Wartungswartezeit und Cache-Wiederverwendung gesondert messen.

### 5. Ereignisuhren und Regieimpulse

**Qualitätsgewinn: mittel. Implementierungsaufwand: mittel.
Performance-Preis: niedrig.** Sobald State und Memory zuverlässig sind,
können spielerische Konsequenzen die Welt aktiver machen.

- Frontmatter definiert einfache Ereignisuhren und ihre Auslöser.
- Der Reducer tickt sie nur anhand klarer State-Übergänge, nicht anhand freier Modellinterpretation.
- Das Secondary-Modell formuliert daraus optionale, kurze Regieimpulse.
- Retrieval und Rerank wählen höchstens einen passenden Impuls pro Turn; ohne passenden Impuls bleibt der Turn unverändert frei.

### 6. Start-Planung und erweiterte Frontmatter

**Qualitätsgewinn: hoch für kuratierte Szenarien. Implementierungsaufwand:
hoch. Performance-Preis: einmalig mittel bis hoch.** Diese Etappe lohnt sich
für große, kuratierte Szenarien, nicht zwingend für jeden Import. Ihr
einmaliger Startaufwand ist ausdrücklich Teil der Abwägung.

- Eine optionale Planungsphase erzeugt die Campaign Bible und validiert sie gegen das gleiche State-Schema.
- Frontmatter steuert nur die dafür nötigen Policies und Budgets.
- Der Nutzer sieht einen klaren Fortschritt und kann die Start-Planung überspringen; das Spiel bleibt danach auch ohne Plan voll funktionsfähig.

## Abnahmekriterien je Etappe

Jede Änderung muss drei Beweise liefern:

1. **Latenz:** Eine Turn-Auswertung zeigt Time-to-first-token des Primary-Modells mit und ohne Secondary-Arbeit; Phase 3 darf außerhalb einer explizit markierten Primary-Kompressionswartung nicht schlechter sein. Diese seltene Wartung wird separat gemessen.
2. **Cache:** Für neue, fortgesetzte und folgende Turns belegen die für die verwendete Backend-Version bestätigten Reuse- und Evaluierungszähler weiterhin Prefix-Reuse auf einem behaltenen Slot.
3. **Gameplay:** Testfälle beweisen mindestens einen gültigen Zustandsübergang, einen verworfenen Widerspruch und — ab Etappe 3 — eine später erneut passende Erinnerung.

## Bewusste Nicht-Ziele

- Kein Secondary-Text erscheint direkt im sichtbaren Transcript.
- Kein Secondary- oder sonstiger optionaler Hintergrundjob darf einen Turn verzögern, nur weil der Nutzer schnell absendet. Die dokumentierte Ausnahme ist die seltene, geplante Primary-Kontextkompression.
- Keine vollständige Chat-Historie wird für jede Nebenaufgabe an das Secondary-Modell kopiert; der kompakte State und die letzten relevanten Turn-Daten sind der Normalfall.
- Keine automatische Korrektur bereits gestreamter Prosa im UI.
- Keine Frontmatter-Sprache, die jede mögliche Mechanik modellieren soll.

## Nächste Entscheidung

Die sinnvollste erste Umsetzung ist Etappe 0, gefolgt vom nicht-blockierenden
Commit-Vertrag aus Etappe 1. Danach ist der minimale Weltzustand die erste
sichtbare Qualitätsverbesserung. Für lange Spiele folgen episodische
Erinnerungen und die geplante Primary-Kontextkompression; der
Secondary-Shadow-Reranker bleibt eine parallele Experimentspur, nicht der
nächste zwingende Produktbaustein.
