# Guide: Ein Spiel erstellen

Dieser Guide beschreibt das aktuell unterstützte Szenarioformat von AI Adventure.
Ein Spiel besteht aus einer Markdown-Datei mit optionalem YAML-Frontmatter.
Der Markdown-Text beschreibt Welt, Regeln und Spielleitung; das Frontmatter
konfiguriert Start, Orte, dauerhafte Flags, Ereignissignale und situationsabhängige
Runtime-Fragmente. Die Beispiele zeigen gültige Formen und ihre Grenzen.

## 1. Der schnellste Einstieg

Speichere diese Datei als `mein-spiel.md`, importiere sie in der Oberfläche,
öffne das Szenario und starte eine neue Sitzung:

```markdown
# Das verschwundene Licht

Du bist die Spielleitung eines geheimnisvollen Hafenabenteuers.
Die Spielfigur sucht nach der verschwundenen Leuchtturmwärterin.
Beginne am Kai in einer nebligen Nacht.
Antworte auf Deutsch in kurzen Abschnitten. Beschreibe konkrete Konsequenzen,
lasse die Entscheidungen der Spielfigur aber beim Menschen.
```

Frontmatter ist optional. Ohne Konfiguration gibt es keine deklarierten Orte,
Flags, Signale oder Runtime-Fragmente; der strukturierte Startort ist leer.
Die Anwendung erzeugt beim Spielstart automatisch eine kurze Einführung in der
Sprache des Szenarios. Der Mensch muss dafür keine erste Aktion eingeben.

Importiert werden nichtleere Dateien mit `.md` oder `.markdown` als Endung,
auch mit anderer Groß-/Kleinschreibung. Szenarien und Spielstände liegen im
IndexedDB-Speicher des verwendeten Browserprofils. Identischer Dateiname und
identischer Inhalt werden als dasselbe Szenario erkannt. Eine bearbeitete Datei
hat einen anderen Fingerabdruck und wird als neues Szenario importiert; sie
überschreibt nicht automatisch das bisherige Spiel.

## 2. Aufbau und vollständige Feldübersicht

```markdown
---
initial-situation:
  world-state:
    location: Kai
  setting: Du stehst nachts am Kai. Der Leuchtturm ist dunkel.
locations:
  Kai: Hafenplatz mit Blick auf den Leuchtturm.
  Leuchtturm: Alter Steinturm am Ende der Mole.
flags:
  turm-besucht: false
signals:
  turm-betreten:
    description: Die Figur hat in diesem Austausch den Leuchtturm tatsächlich betreten.
    condition: {turm-besucht: false}
    triggers:
      - set-flag: {turm-besucht: true}
runtime-fragments:
  "Die Figur untersucht den Leuchtturm von außen": >
    Beschreibe eine klemmende Tür und einen zweiten möglichen Zugang.
---
# Das verschwundene Licht

Du bist die Spielleitung. Erzähle ein rätselhaftes Hafenabenteuer auf Deutsch.
Die Wärterin ist verschwunden; im Turm liegt eine Nachricht von ihr.
Gib Hinweise schrittweise und akzeptiere plausible Lösungswege.
```

| Feld | Typ und Bedeutung | Optional / Vorgabe bei Weglassen |
| --- | --- | --- |
| `initial-situation` | Mapping mit `world-state` und/oder `setting` | Ja; keine zusätzliche Startsituation |
| `initial-situation.world-state` | Mapping mit genau `location` | Ja; Rückgriff auf oberstes `world-state` |
| `initial-situation.world-state.location` | Nichtleerer String, strukturierter Startort | Erforderlich, wenn dieses `world-state` angegeben wird |
| `initial-situation.setting` | Nichtleerer String, einmaliger Kontext für die Einführung | Ja |
| `world-state` | Ältere Startort-Schreibweise mit genau `location` | Ja; leerer Startort |
| `locations` | Mapping von kanonischen Ortsnamen auf Beschreibungen | Ja; kein Ortskatalog |
| `flags` | Mapping von Flag-Namen auf `true` oder `false` | Ja; keine Flags |
| `signals` | Mapping oder Liste benannter Signale | Ja; keine Ereignisprüfungen |
| `signals.<name>.description` | Nichtleerer String mit Ereignisbeschreibung | Ja; siehe Rückfallregeln unten |
| `signals.<name>.condition` | Nichtleeres Mapping mit deklarierten Flags auf boolesche Sollwerte und/oder `location` auf einen Ortsnamen (UND) | Ja; keine zusätzliche Flag-Schranke |
| `signals.<name>.triggers` | Nichtleere Liste von `set-flag`- und/oder `emit-text`-Aktionen | Pflicht je Signal |
| Signalaktion `set-flag` | Nichtleeres Mapping deklarierter Flags auf boolesche Zielwerte | Optional je Signal |
| Signalaktion `emit-text` | Nichtleerer fester Text für den verborgenen Kontext der nächsten Runde | Optional je Signal |
| `runtime-fragments` | Mapping mit benannten Fragmenten und optionalen direkten Regeln | Ja; keine Zusatzhinweise |
| Fragmentfeld `emit-text` | Nichtleerer Zusatztext, an Nachfahren vererbt | Ja |
| Fragmentfeld `signal` | Name eines deklarierten Signals, an Nachfahren vererbt | Ja |
| Fragmentfeld `condition` | Bedingungs-Mapping (Flags und/oder `location`, UND) oder Liste unabhängiger `then`-Zweige mit optionalem abschließendem `else` | Ja |

Die letzten drei Fragmentfelder haben eine besondere Kompatibilitätsregel:
Sie sind nur bei vorhandenem obersten `flags`- oder `signals`-Feld Metadaten,
auch direkt unter `runtime-fragments`. Ohne beide Felder sind sie normale
Fragmentnamen. Siehe Abschnitt 7.

Frontmatter muss unmittelbar am Dateianfang mit einer eigenen `---`-Zeile
beginnen und durch eine weitere eigene `---`-Zeile enden. Leerzeilen davor
oder ein fehlender Abschluss verhindern die Erkennung; der Text wird dann als
gewöhnliches Markdown behandelt. Zeilenenden mit LF oder CRLF sind unterstützt.

Der YAML-Block wird nicht unverändert an die Spielleitung geschickt. Die Anwendung
verarbeitet seine Felder getrennt. Der Markdown-Haupttext bildet den dauerhaften
Szenariokontext; der Ortskatalog wird zusätzlich in den Modellkontext aufgenommen.
Startsetting und aktueller World-State werden gesondert übergeben. Flags steuern
Bedingungen und Signalprüfungen; sie werden nicht als allgemeine Flag-Liste in den
Spielleitungs-Prompt geschrieben. Soll eine Flag-Änderung erzählerisch wirken,
formuliere dafür passende bedingte Fragmente oder eine `emit-text`-Signalaktion.

## 3. Die Welt und Spielregeln im Markdown

Im Haupttext kannst du beliebige Markdown-Abschnitte verwenden, beispielsweise:

```markdown
# Das verschwundene Licht

## Rolle und Erzählstil
Du leitest ein spannendes Abenteuer auf Deutsch. Antworte knapp und anschaulich.
Beschreibe keine Entscheidungen oder Gedanken der Spielfigur ohne ihre Vorgabe.

## Welt und Personen
Nebelhafen lebt vom Seehandel. Die Hafenmeisterin möchte einen Skandal vermeiden.
Die Wärterin hat den Turm verlassen, um eine Schmugglergruppe zu verfolgen.

## Geheimnisse und Hinweise
Eine versteckte Nachricht führt zum Lagerschuppen. Das Hafenbuch enthält
eine falsche Abfahrtszeit. Beide Hinweise können unabhängig gefunden werden.

## Regeln und Ziel
Plausible Handlungen können gelingen. Gefährliche Versuche haben Konsequenzen.
Das Abenteuer endet, wenn die Wärterin gefunden und die Lichtsignale erklärt sind.
```

Diese Überschriften sind Schreibkonventionen, keine speziellen Engine-Felder.
Auch Inventar, Personenwerte, Kampfregeln, Rätsel, Zeitdruck, Dialoge und Enden
kannst du hier beschreiben. Sie sind dann erzählerische Regeln für das Modell.
Es gibt dafür derzeit keine eigenen strukturierten YAML-Systeme oder eine
garantierte regelbasierte Ausführung. Für binäre Fortschritte nutze Flags.

## 4. Startsituation und World-State

### Empfohlene Form

```yaml
initial-situation:
  world-state:
    location: Kai
  setting: |
    Du bist gerade in Nebelhafen angekommen.
    Die Hafenmeisterin bittet dich, nach der Wärterin zu suchen.
    Vor dir liegen die Mole und der dunkle Leuchtturm.
```

`setting` wird einmal beim Erzeugen der Einführung übergeben. Es ist keine
ständige Anweisung, jede spätere Szene wieder an den Start zu versetzen.
`world-state.location` initialisiert den strukturierten Aufenthaltsort.

Beide Unterfelder sind einzeln optional: Nur `setting`, nur `world-state` oder
das leere Mapping `initial-situation: {}` sind zulässig. Wenn du ein Unterfeld
angibst, muss sein Inhalt die jeweilige Form erfüllen.

### Weiterhin unterstützte Startort-Form

```yaml
world-state:
  location: Kai
```

Diese Form kann auch mit `initial-situation.setting` kombiniert werden.
Wenn beide Startort-Formen angegeben sind, gewinnt
`initial-situation.world-state.location`. Für neue Spiele verwende eine Form,
damit die Datei eindeutig bleibt.

Der aktuelle strukturierte World-State enthält `location` und den automatisch
ermittelten `narrativeFocus` als kurzen Satz zur gegenwärtigen Handlung.
Im Frontmatter darfst du für den World-State nur `location` setzen;
`narrativeFocus`, `entities`, `facts` und weitere Zustandsfelder sind dort ungültig.
Nach einem Austausch aktualisiert die Anwendung Ort und Erzählfokus anhand der
tatsächlich beschriebenen Ereignisse. Ungültige Ergebnisse ersetzen keinen
gültigen bisherigen Ort.

## 5. Locations: Orte definieren

```yaml
locations:
  Kai: Umschlagplatz mit Pollern und Blick auf die Mole.
  Leuchtturm: |
    Steinturm am Ende der Mole.
    Innen führen eine Wendeltreppe und eine Leiter zur Laternenkammer.
  Hafenkneipe: "Zum schiefen Mast: Gasthaus der Seeleute."
  Lagerschuppen: ""
  Steg:
```

Einfacher Text, mehrzeiliger Text, zitierter Text, leerer String und ausgelassene
Beschreibung (`null`) bleiben gültig. Die beiden letzten Formen erzeugen eine
leere Beschreibung. Für Betretungsaktionen kann ein Ort stattdessen ein Mapping
mit `description` und `triggers` verwenden:

```yaml
locations:
  Kai:
    description: Umschlagplatz am Hafen mit Blick auf die Mole.
    triggers:
      - set-flag: {hafen-betreten: true}
      - emit-text: Die Glocken am Kai läuten zur Begrüßung.
      - signals: [trifft-hafenmeisterin]
```

`triggers` verwendet dieselben `set-flag`- und `emit-text`-Aktionen wie ein
Signal. Die zusätzliche Aktion `signals` erwartet eine Liste deklarierter
Signalnamen. Beim Betreten werden Ortsaktionen in Reihenfolge ausgeführt;
genannte Signale gelten dabei als ausgelöst und führen ihre eigenen Aktionen
aus, ohne eine Ereignisprüfung durch das Modell. Das gilt auch für den
konfigurierten Startort. Bei späteren Zügen lösen nur tatsächliche Ortswechsel
die Aktionen aus; Warmup und Wiederaufnahme wiederholen sie nicht.

Ortswerte dürfen neben Text oder `null` nur dieses Mapping verwenden; Listen,
Zahlen und boolesche Werte sind ungültig. `triggers` muss, falls angegeben,
mindestens eine gültige Aktion enthalten.

Die Schlüssel sind die kanonischen Ortsnamen. Ortsnamen dürfen nicht leer sein
und nicht nur durch Groß-/Kleinschreibung voneinander abweichen. Ein angegebener
Katalog muss mindestens einen Ort enthalten; `locations: {}` ist ungültig.
Ein konfigurierter Startort muss bei vorhandenem Katalog exakt als Schlüssel
enthalten sein, einschließlich seiner Groß-/Kleinschreibung.

Mit Katalog kann der strukturierte Aufenthaltsort nur einer der deklarierten Orte
sein. Die Beschreibung hilft, Aufenthalte von Erwähnungen, Reiseabsichten und
gescheiterten Zugangsversuchen zu unterscheiden. Ohne Katalog wird kein neuer
strukturierter Ort durch diese Ortsanalyse angenommen; ein gesetzter Startort
bleibt dabei erhalten. Für verlässliche Ortswechsel deklariere alle spielbaren Orte.

Der Katalog definiert keine Wege, Nachbarschaft oder Zugangssperren. Beschreibe
diese im Markdown bzw. in Fragmenten. Ein gleichnamiger Fragmentknoten ist
auch nicht automatisch an einen Ort gebunden: Sein Name dient der semantischen
Auswahl, nicht einer exakten `location`-Bedingung.

## 6. Flags und Signale: dauerhafte Fortschritte

### Flags

```yaml
flags:
  turm-besucht: false
  nachricht-gefunden: false
  hafenmeisterin-misstrauisch: true
```

Flags sind benannte boolesche Werte pro Spielstand. Sie können mit `true` oder
`false` starten, durch Signale gesetzt oder zurückgesetzt werden und Bedingungen
für Fragmente oder Ereignisprüfungen bilden. Namen müssen nichtleer sein;
verwende eindeutige, gut lesbare Strings. `flags: {}` ist zulässig.
Strings wie `"false"`, Zahlen, `null` und `yes`/`no` sind keine gültigen Flag-Werte.

Eine neue Sitzung übernimmt die Startwerte. Eine fortgesetzte Sitzung verwendet
ihre gespeicherten Werte. Eine Aussage der Spielleitung allein beweist keine
Flag-Änderung: Maßgeblich ist der von der Engine zurückgegebene und gespeicherte
Flag-Zustand.

### Signale als Mapping

```yaml
signals:
  nachricht-entdeckt:
    description: >
      Die Figur hat in diesem Austausch die versteckte Nachricht tatsächlich
      gefunden oder gelesen. Bloßes Suchen oder die Absicht zu suchen zählt nicht.
    condition: {nachricht-gefunden: false}
    triggers:
      - set-flag:
          nachricht-gefunden: true
          hafenmeisterin-misstrauisch: true
      - set-flag:
          turm-besucht: true
```

Jedes Signal hat eine nichtleere `triggers`-Liste. Unterstützt werden `set-flag`
und `emit-text`; jeder Listeneintrag enthält genau einen dieser Aktionstypen.
Eine `set-flag`-Aktion darf mehrere bereits deklarierte Flags setzen; mehrere
Zuweisungen werden der Reihe nach angewendet. `false` setzt einen Zustand zurück.
Ein mehrfach gesetztes Flag erhält den letzten zugewiesenen Wert.

`description` beschreibt ein Ereignis im zuletzt abgeschlossenen Austausch.
Formuliere sie als überprüfbare Tatsache und grenze Absicht, Versuch und Erfolg
ab. Sie ist kein Befehl, das Ereignis herbeizuerzählen. Die zusätzliche `condition`
schränkt die Prüfung auf deklarierte Flag-Sollwerte und/oder den aktuellen Ort ein. Mehrere Einträge
im selben Mapping müssen alle erfüllt sein (UND), etwa
`condition: {location: Leuchtturm, nachricht-gefunden: true, turm-besucht: false}`.
Für `location` gelten die Namens- und Katalogregeln aus Abschnitt 7 D.
Ohne `condition` entfällt diese Schranke.

### Signalaktionen mit `emit-text`

```yaml
flags: {nachricht-gefunden: false}
signals:
  nachricht-entdeckt:
    description: Die Figur hat die Nachricht der Wärterin tatsächlich gefunden.
    condition: {nachricht-gefunden: false}
    triggers:
      - set-flag: {nachricht-gefunden: true}
      - emit-text: >
          Die Nachricht nennt die Abfahrt zur dritten Glocke.
          Nutze diesen Hinweis in der nächsten Antwort.
```

`emit-text` übernimmt einen festen, nichtleeren Text in den verborgenen
Laufzeitkontext für die nächste Runde. Er wird nicht unmittelbar als sichtbare
Antwort ausgegeben und verändert die bereits abgeschlossene Antwort nicht.
Anders als Runtime-Fragmente durchlaufen diese Texte keine semantische
Fragmentauswahl: Ein erfolgreich geprüftes Signal gibt seine Texte aus.

Ein Signal darf ausschließlich `emit-text`-Aktionen enthalten und benötigt dann
keine `flags`-Deklaration:

```yaml
signals:
  glocke-gehoert:
    description: Die Figur hat in diesem Austausch die Hafenglocke gehört.
    triggers:
      - emit-text: Erinnere in der nächsten Antwort an die bevorstehende Abfahrt.
```

Texte erfolgreicher Signale werden in Signal- und Triggerreihenfolge gesammelt.
Danach folgt gegebenenfalls das ausgewählte, weiterhin berechtigte
Runtime-Fragment. Leerzeilen trennen die Texte in einem gemeinsamen Kontexteintrag.
Dieser wird mit dem Spielstand gespeichert und bleibt beim Neuladen erhalten,
ohne dadurch doppelt eingefügt zu werden.

Textausgebende Signale können bei späteren passenden Ereignissen erneut auslösen,
auch wenn ihre Flag-Zuweisungen nichts mehr ändern würden. Soll ein Ereignis nur
einmal auslösen, kombiniere wie im ersten Beispiel eine Bedingung auf `false`
mit einer Zuweisung auf `true`.

### Signale als geordnete Liste

```yaml
signals:
  - turm-betreten:
      description: Die Figur hat den Turm tatsächlich betreten.
      triggers:
        - set-flag: {turm-besucht: true}
  - nachricht-entdeckt:
      description: Die Figur hat die versteckte Nachricht tatsächlich gefunden.
      triggers:
        - set-flag: {nachricht-gefunden: true}
```

Jeder Listeneintrag enthält genau ein benanntes Signal. Signalnamen müssen
nichtleer und eindeutig sein. Die Liste macht die Reihenfolge ausdrücklich
sichtbar. Bei Mappings mit gewöhnlichen Textnamen gilt die Deklarationsreihenfolge;
vermeide rein numerische Signalnamen, da JavaScript numerische Objektschlüssel
anders sortieren kann. Leere Signal-Mappings und leere Signallisten sind zulässig.

### Ablauf und Konflikte

Nach der sichtbaren Antwort, der World-State-/Fokus-Aktualisierung und der
Fragmentprüfung werden die relevanten ereignisbasierten Signale nacheinander
vom Modell geprüft. Direkte Runtime-Regeln werden bereits vor der Embedding-Suche
ausgewählt. Ihre Signalaktionen werden anschließend zusammen mit den übrigen
Signalaktionen angewendet, benötigen aber keine Modellprüfung (Abschnitt 7 G).
Die Prüfung verlangt genau `Y` oder `N`; nur ein exaktes `Y` löst das Signal aus.
Eine fehlgeschlagene oder ungültige Prüfung setzt dessen Flags nicht und gibt
keine Signaltexte aus.

Die Flag-Bedingungen der ereignisbasierten Signalprüfungen verwenden den
Flag-Schnappschuss **zu Beginn des Spielzugs**. Ortsbedingungen verwenden dagegen
den akzeptierten World-State nach der sichtbaren Antwort und ihrer
World-State-Auswertung. Erst nach den Prüfungen werden die Zuweisungen
erfolgreicher Signale in Signalreihenfolge angewendet. Es gibt keine Kettenreaktion innerhalb
desselben Zugs: Ein Signal, das `turm-besucht: true` setzt, aktiviert dadurch keine
vorher ineligible Prüfung mit dieser Bedingung im selben Zug.

Ein Signal ohne `emit-text` wird übersprungen, wenn alle seine effektiven
Flag-Zielwerte bereits erreicht sind. Signale mit `emit-text` bleiben unter
ihren jeweiligen Bedingungen prüfberechtigt, auch ohne Flag-Änderung.
Konfliktbeispiel: Beginnt `alarm` mit `false`, wird ein Signal,
das ausschließlich `alarm: false` setzen würde, gar nicht geprüft, auch wenn ein
anderes Signal in diesem Zug `alarm: true` setzen könnte. Sind dagegen zwei
konfligierende Signale beide prüfberechtigt und erfolgreich, gewinnt bei der
Anwendung die spätere Zuweisung. Vermeide solche Konflikte im Spieldesign.

### Signale ohne `description`: kompatible Varianten

Ohne explizite Beschreibung wird die Ereignisfrage so gebildet:

1. Gibt es Fragmente mit einem Verweis `signal: <name>`, dienen deren vollständige
   Schlüsselpfade als Ereignisbeschreibungen. Ihre geerbten Flag- und Ortsbedingungen werden
   mit der Signalbedingung kombiniert.
2. Mehrere passende Pfade desselben Signals bilden Alternativen: Ein tatsächlich
   eingetretenes Ereignis genügt. Gleichwertige Beschreibungen werden dedupliziert.
3. Ohne Fragmentverweis wird der Signalname selbst zur Ereignisbeschreibung,
   sofern das Signal nicht ausschließlich von direkten Regeln referenziert wird.
   Ein solches Signal ohne `description` wartet auf seine direkte Regel und wird
   nicht zusätzlich als unabhängiges Ereignis geprüft.

Mit expliziter `description` gelten ausschließlich diese Beschreibung und die
Signalbedingung; Bedingungen der referenzierenden Fragmente schränken die
Signalprüfung dann nicht ein. Für neue Spiele sind explizite Beschreibungen die
klarste Form. Ein Signal benötigt kein Runtime-Fragment.

## 7. Runtime-Fragmente: situationsabhängige Hinweise

Runtime-Fragmente liefern der Spielleitung zusätzliche, im normalen Transcript
verborgene Hinweise. Benannte Schlüssel beschreiben die Situation; ihre Textwerte
beschreiben, wie die Spielleitung darauf reagieren soll. Daneben können direkte
Regeln anhand von Flags und/oder dem aktuellen Ort Text ausgeben und Signale auslösen (Abschnitt G).

Nach einem Austausch werden zuerst direkte Regeln ausgewertet. Anschließend
sucht die Anwendung unter den durch Bedingungen freigegebenen benannten Fragmenten
semantisch einen Kandidaten anhand des aktuellen Erzählfokus bzw. des letzten
sichtbaren Austauschs und des aktuellen Orts. Eine weitere Modellprüfung kann
den Kandidaten ablehnen. Pro Auswahl wird höchstens ein Fragment übernommen.
Ein übernommener Hinweis kommt in den Kontext für folgende Antworten; er verändert
nicht rückwirkend die bereits sichtbare Antwort. Die Auswahl erfolgt vor der
abschließenden Signalprüfung und berücksichtigt deren neue Flag-Werte in dieser
Auswahl noch nicht. Benannte Fragmente sind deshalb keine synchronen Sofort-Trigger.

### A. Direkte Textblätter

```yaml
runtime-fragments:
  "Die Figur untersucht die Turmtür": >
    Beschreibe Rost am Schloss und frische Kratzspuren am Türrahmen.
  "Die Figur befragt die Hafenmeisterin": |
    Sie weicht Fragen zur letzten Abfahrt aus.
    Gib einen überprüfbaren Widerspruch, aber verrate das Geheimnis noch nicht.
```

Einfache, zitierte und mehrzeilige nichtleere Strings sind zulässig.
`>` faltet YAML-Zeilen zu Fließtext; `|` erhält Zeilenumbrüche. Diese YAML-Formen
kannst du auch für Ortsbeschreibungen, `setting`, `description` und `emit-text` nutzen.

### B. Beliebig verschachtelte Gruppen

```yaml
runtime-fragments:
  "Die Figur erkundet den Leuchtturm":
    "Die Figur untersucht die Treppe":
      "Die Figur sucht unter den Stufen": >
        Lass sie eine lose Stufe mit einem eingeritzten Zeichen entdecken.
    "Die Figur betrachtet die Lampe": >
      Beschreibe frisches Öl und eine verstellte Abdeckung.
```

Nur Blätter werden zu Fragmenten. Der vollständige Schlüsselpfad bildet den
Suchtext, hier etwa „Die Figur erkundet den Leuchtturm“, „Die Figur untersucht
die Treppe“, „Die Figur sucht unter den Stufen“, durch Zeilenumbrüche verbunden.
Gruppenüberschriften sind Teil des Suchkontexts, kein automatisch ausgegebener Text.

### C. `emit-text` und geerbter Text

Die folgenden Metadaten-Beispiele setzen ein oberstes `flags`- oder
`signals`-Feld voraus. `flags: {}` reicht, wenn keine Flags benötigt werden.

```yaml
flags: {}
runtime-fragments:
  "Die Figur erkundet den Leuchtturm":
    emit-text: Halte Wind, Feuchtigkeit und die Enge des Turms präsent.
    "Die Figur untersucht die Lampe":
      emit-text: Die Abdeckung wurde kürzlich verstellt.
    "Die Figur sucht unter der Treppe": Ein Brief steckt hinter einer losen Stufe.
```

Jedes Blatt bekommt den Text seiner Vorfahren plus seinen eigenen Text in
Reihenfolge von außen nach innen, durch Zeilenumbrüche verbunden.
Eine Gruppe mit Kindern wird nicht zusätzlich als eigenes Fragment angelegt.
Ein Knoten mit nur `emit-text` ist selbst ein gültiges Blatt.

### D. Flag- und Ortsbedingungen mit UND

```yaml
flags: {nachricht-gefunden: false}
runtime-fragments:
  "Die Figur untersucht den Brief":
    condition: {nachricht-gefunden: true}
    emit-text: Erinnere an die bereits entzifferte Abfahrtszeit.
```

Die Bedingung filtert die Verfügbarkeit des Fragments. Sie setzt keine Flags.
Sie enthält deklarierte Flags mit `true` oder `false` und/oder `location` mit einem
Ortsnamen, etwa `condition: {location: Leuchtturm, nachricht-gefunden: true}`.
Alle Einträge müssen
erfüllt sein (UND). Nachfahren erben die Bedingungen ihrer Vorfahren; auch
zwischen verschachtelten Gruppen gilt UND.
Widersprüchliche Bedingungen erzeugen ein unerreichbares Fragment.

`location` vergleicht den aktuellen Wert von `world-state.location` exakt,
inklusive Groß-/Kleinschreibung. Der Name muss nichtleer sein und bei einem
nichtleeren `locations`-Katalog dort deklariert sein; ohne Katalog ist jeder
nichtleere Ortsname erlaubt. Eine Ortsbedingung benötigt kein gleichnamiges Flag.
Der Werttyp unterscheidet die beiden Formen: `condition: {location: Leuchtturm}`
prüft den Ort, während `condition: {location: true}` bzw. `false` weiterhin ein
unter `flags` deklariertes boolesches Flag namens `location` prüft. Ohne diese
Flag-Deklaration ist die boolesche Form ungültig.
Ortsbedingungen gelten überall, wo Bedingungen möglich sind: in Signalen, direkten Regeln,
benannten Fragmenten, geerbten Bedingungen und `then`-Zweigen. `else` greift nur,
wenn keiner der vorausgehenden Geschwisterzweige mit seinen gesamten Flag- und
Ortsbedingungen passt. Die Bedingungen werden gemeinsam am jeweiligen
Auswertungspunkt geprüft; für Signalprüfungen gelten die in Abschnitt 6
beschriebenen Zustände. Eine Ortsbedingung löst selbst keinen Ortswechsel aus.

Für ausschließlich ortsabhängige Fragmentregeln ohne Signale aktiviert ein
oberstes `flags: {}` die Metadaten-Syntax (siehe Abschnitt H):

```yaml
flags: {}
runtime-fragments:
  condition:
    - location: Leuchtturm
      then: {emit-text: Beschreibe den Wind im Turm.}
    - else: {emit-text: Die Küste bleibt in der Ferne.}
```

### E. Bedingte Zweige mit `then`

```yaml
flags: {nachricht-gefunden: false, turm-besucht: false}
runtime-fragments:
  "Die Figur fragt nach der Wärterin":
    emit-text: Die Hafenmeisterin antwortet vorsichtig.
    condition:
      - nachricht-gefunden: false
        then:
          "Die Figur fragt ohne Kenntnis des Briefs": >
            Verweise auf einen möglichen Hinweis im Leuchtturm.
      - nachricht-gefunden: true
        then:
          "Die Figur fragt mit Kenntnis des Briefs":
            condition: {turm-besucht: true}
            emit-text: Lass die Hafenmeisterin die genannte Abfahrtszeit erklären.
```

`condition` als Liste benötigt mindestens einen bedingten Zweig. Jeder solche
Zweig enthält mindestens eine Flag- oder Ortsbedingung
(UND) und genau ein `then`. `then` ist ein nichtleeres Mapping und darf direkt
`emit-text`, `signal`, eine weitere `condition` sowie benannte Kinder enthalten.
Verschachtelung ist auch ohne zusätzlichen Situationsnamen möglich.
Ein einzelner Text oder eine Liste als `then` ist ungültig.

Alle passenden Zweige gelten unabhängig voneinander: Bei mehreren erfüllten
Bedingungen werden alle entsprechenden `then`-Inhalte berücksichtigt. Ein
optionaler letzter Eintrag `- else: ...` greift ausschließlich dann, wenn kein
bedingter Geschwisterzweig passt. `else` enthält wie `then` ein nichtleeres
Mapping; es hat weder eigene Bedingungseinträge noch ein zusätzliches `then`.

```yaml
flags: {nachricht-gefunden: false, turm-besucht: false}
runtime-fragments:
  "Die Figur fragt nach weiteren Spuren":
    condition:
      - nachricht-gefunden: true
        turm-besucht: true
        then:
          "Die Figur verbindet Nachricht und Turmspuren":
            emit-text: Verknüpfe die Nachricht mit den Spuren im Turm.
      - turm-besucht: true
        then:
          "Die Figur erinnert sich an die Treppe":
            emit-text: Erinnere an die lose Treppenstufe.
      - else:
          "Die Figur fragt ohne passenden Turmhinweis":
            emit-text: Verweise zunächst auf den Leuchtturm.
```

Sind beide Flags `true`, passen die ersten beiden Zweige; `else` entfällt.
Ist nur `turm-besucht` wahr, passt der zweite Zweig. Ist `turm-besucht` falsch,
passt keiner und `else` gilt. Bei diesem benannten Fragment bleiben die
Zweiginhalte Kandidaten für die semantische Auswahl; sie werden dadurch nicht
alle automatisch ausgegeben.

Bedingungen oberhalb der Liste bleiben bestehen. Zusätzliche gewöhnliche Kinder
am Elternknoten erben dessen Kontext, aber keine Bedingung eines Geschwisterzweigs.
Gemeinsamen Zusatztext setzt du per `emit-text` am Elternknoten.

Die Zweigmarker `condition`, `then`, `else` und Bedingungseinträge werden nicht Teil
des Suchpfads. Benannte Blätter benötigen deshalb unterschiedliche vollständige
Suchpfade, auch bei gegensätzlichen Bedingungen. Die Beispiele verwenden
unterschiedliche Blattnamen. Direkte Regelaktionen ohne Suchpfad sind hiervon
nicht betroffen.

### F. `signal` am Blatt oder an einer Gruppe

```yaml
flags: {turm-besucht: false}
signals:
  turm-betreten:
    triggers:
      - set-flag: {turm-besucht: true}
runtime-fragments:
  "Die Figur hat den Leuchtturm tatsächlich betreten":
    signal: turm-betreten
    emit-text: Beschreibe die enge Wendeltreppe.
```

`signal` ist genau ein nichtleerer Name eines deklarierten Signals. An einer
Gruppe wird der Verweis an alle Blattfragmente vererbt; mehrere Verweise können
durch Verschachtelung zusammenkommen. Eine Liste von Signalnamen im Feld
`signal` wird nicht unterstützt.

Auch ein Knoten mit ausschließlich `signal` und optionaler einfacher Bedingung
ist gültig: Er definiert einen Ereignispfad ohne eigenen Zusatztext.
**Die Auswahl eines benannten Fragments löst das Signal nicht automatisch aus.**
Der Verweis dient der Rückfall-Ereignisbeschreibung von Signalen ohne
`description`; tatsächliches Eintreten wird separat geprüft. Direkte Regeln
ohne Suchpfad unterscheiden sich hiervon, siehe den folgenden Abschnitt.

### G. Direkte Regeln ohne semantische Suche

Direkt unter `runtime-fragments` darf ebenfalls `condition` stehen. Ein `then`
kann sofort Text ausgeben oder ein deklariertes Signal auslösen, ohne einen
Situationsnamen, Suchtext oder eine Modellprüfung zu benötigen:

```yaml
flags: {nachricht-gefunden: false, turm-besucht: false, spur-erklaert: false}
signals:
  spur-bereit:
    triggers:
      - set-flag: {spur-erklaert: true}
      - emit-text: Die nächste Spur führt zum Zeugen in der Hafenkneipe.
runtime-fragments:
  condition:
    - nachricht-gefunden: true
      turm-besucht: true
      spur-erklaert: false
      then:
        emit-text: Die Figur kann jetzt Nachricht und Turmspuren verbinden.
        signal: spur-bereit
    - nachricht-gefunden: true
      then:
        condition:
          - turm-besucht: false
            then:
              emit-text: Die Nachricht liefert einen Anlass, den Turm zu besuchen.
          - else:
              emit-text: Halte den Zusammenhang zwischen Nachricht und Turm präsent.
    - else:
        emit-text: Die Nachricht der Wärterin ist noch nicht bekannt.
  "Die Figur fragt den Seemann nach der Abfahrt": >
    Er hat zur dritten Glocke ein kleines Boot an der Mole gesehen.
```

Die Regeln werden vor der Embedding-Suche anhand eines gemeinsamen
Schnappschusses von Flags und aktuellem Ort ausgewertet; auch die Berechtigung zur Fragmentauswahl
verwendet diese Ausgangswerte. Alle passenden Zweige gelten, auch in
verschachtelten Listen. Hier greifen bei drei passenden Voraussetzungen beide
äußeren `then`-Zweige; der zweite enthält wiederum eine eigene Entscheidung.
Direkte Aktionen benötigen keinen passenden Erzählfokus und keine
Relevanzbestätigung durch das Modell. Der benannte Seemann-Hinweis bleibt ein
gewöhnlicher Kandidat für die semantische Auswahl.

Ein direktes `signal` führt die deklarierten Signalaktionen aus; für diese
Auslösung wird keine Ereignisfrage an das Modell gestellt. Eine eigene
`condition` der Signaldefinition muss ebenfalls im Ausgangszustand erfüllt sein.
Verwende die Flag- und Ortsbedingungen der Regel und des Signals, um die Voraussetzungen
eindeutig festzulegen. Ein ausschließlich direkt referenziertes Signal ohne
`description` wird nicht zusätzlich unabhängig als Ereignis geprüft.
Es gibt keine Kettenreaktion zwischen Regeln desselben Durchlaufs: Ein dabei
neu gesetztes Flag macht einen vorher unpassenden Zweig erst bei einer
späteren Auswertung passend.

Direkte Texte werden bei jedem passenden Durchlauf erneut ausgegeben.
Im Beispiel verhindert `spur-erklaert: false` zusammen mit der gleichnamigen
Flag-Zuweisung die Wiederholung des ersten Zweigs. Der zweite Zweig hat keinen
solchen Schutz und bleibt wiederholbar. Alle Hinweise gelten für folgende
Antworten; sie ändern keine bereits sichtbare Erzählung. Im verborgenen Kontext
stehen zuerst direkte Regeltexte in Deklarations-/Durchlaufreihenfolge, dann
Texte ausgelöster Signale in Signal- und Triggerreihenfolge und zuletzt das
semantisch ausgewählte Fragment. Dieses Fragment wird nur übernommen, wenn
es auch nach den Flag-Zuweisungen noch berechtigt ist.

Auch `then` mit benannten Kindern ist erlaubt. Diese Kinder bleiben
retrievalbasierte Fragmente und erben die Flag- und Ortsbedingungen der Regel. Direkte
`emit-text`- und `signal`-Aktionen ohne benannten Pfad werden dagegen dort
verarbeitet und nicht nochmals an die benannten Kinder vererbt. Innerhalb
benannter Fragmentgruppen gilt weiterhin die Text- und Signalvererbung aus
Abschnitt C und F. Ein direktes `emit-text` ohne benannten Pfad ist eine
Regelaktion; ein `emit-text` unter einem benannten Fragment ist dessen
Auswahlkontext.

### H. Kompatibilitätsregel für Metadaten-Namen

```yaml
runtime-fragments:
  Leuchtturm:
    condition: Die Tür ist verschlossen.
    emit-text: Die Stufen sind nass.
    signal: Eine Glocke ertönt.
```

Ohne oberstes `flags` oder `signals` sind das drei gewöhnliche Textblätter
mit den Pfaden `Leuchtturm / condition`, `Leuchtturm / emit-text` und
`Leuchtturm / signal`. Sobald eines dieser obersten Felder existiert,
werden diese Namen als Metadaten interpretiert, auch direkt auf der obersten
Ebene unter `runtime-fragments`. Andere Schlüssel bleiben Fragmentnamen.
Verwende für neue Spiele aussagekräftige Situationsnamen und deklariere
`flags: {}`, wenn du Metadaten ohne tatsächliche Flags nutzen möchtest.

### I. Leere und ungültige Fragmente

`runtime-fragments: {}` ist zulässig. Jede sonstige Gruppe braucht mindestens
ein gültiges Blatt oder passende eigene Metadaten. Ungültig sind insbesondere:

- Leere Texte, Zahlen, boolesche Werte und Listen als Fragmentwerte.
- Leere Untergruppen und Knoten mit ausschließlich einer einfachen Bedingung.
- Unbekannte Flag- oder Signalverweise.
- Leere Bedingungs-Mappings, unbekannte Flags und nichtboolesche Flag-Sollwerte.
- Leere oder nichttextuelle Ortsbedingungen sowie unbekannte Orte bei vorhandenem `locations`-Katalog.
- Leere Zweiglisten oder leere `then`-/`else`-Mappings.
- Bedingte Zweige ohne Bedingungen oder ohne `then`.
- Mehrere `else`-Einträge, `else` vor dem Listenende oder ein `else` ohne vorherigen bedingten Zweig.
- Zusätzliche Felder neben `else` oder unzulässige Felder statt Flag- oder Ortseinträgen in einem bedingten Zweig.
- Doppelte vollständige Suchpfade benannter Blätter, auch zwischen bedingten Zweigen.

Eine besondere Objektform `{fragment: Text}` gibt es derzeit nicht. Als
verschachteltes Mapping kann sie zwar geparst werden, aber `fragment` wird dann
ein gewöhnlicher zusätzlicher Suchpfad-Abschnitt. Verwende direkte Textblätter
oder `emit-text`, wenn du den Text eines benannten Fragments angeben möchtest.

## 8. Komplettes Spiel zum Kopieren

Speichere den gesamten folgenden Block als `das-verschwundene-licht.md`:

```markdown
---
initial-situation:
  world-state:
    location: Kai
  setting: |
    Du stehst am Kai von Nebelhafen. Nebel verhüllt die Mole.
    Die Hafenmeisterin bittet dich, nach der verschwundenen Wärterin zu suchen.
locations:
  Kai: Hafenplatz mit Zugang zu Mole und Hafenkneipe.
  Leuchtturm: Steinturm mit Treppe und Laternenkammer am Ende der Mole.
  Hafenkneipe: Gasthaus, in dem Seeleute die nächtlichen Abfahrten beobachten.
flags:
  turm-besucht: false
  nachricht-gefunden: false
  hafenmeisterin-misstrauisch: false
  spur-erklaert: false
signals:
  turm-betreten:
    description: >
      Die Figur hat in diesem Austausch den Leuchtturm tatsächlich betreten.
      Eine Absicht, Außenansicht oder ein gescheiterter Versuch genügt nicht.
    condition: {turm-besucht: false}
    triggers:
      - set-flag: {turm-besucht: true}
  nachricht-entdeckt:
    description: >
      Die Figur hat in diesem Austausch die Nachricht der Wärterin tatsächlich
      gefunden oder gelesen. Bloßes Suchen genügt nicht.
    condition: {nachricht-gefunden: false}
    triggers:
      - set-flag: {nachricht-gefunden: true}
      - emit-text: >
          Die Figur kennt jetzt die Nachricht der Wärterin.
          Verknüpfe ihre nächsten Nachfragen mit der Abfahrt zur dritten Glocke.
  misstrauen-geweckt:
    description: Die Hafenmeisterin ist durch das Verhalten der Figur tatsächlich misstrauisch geworden.
    condition: {hafenmeisterin-misstrauisch: false}
    triggers:
      - set-flag: {hafenmeisterin-misstrauisch: true}
  misstrauen-abgebaut:
    description: Die zuvor misstrauische Hafenmeisterin hat sich durch das Verhalten der Figur tatsächlich beruhigt.
    condition: {hafenmeisterin-misstrauisch: true}
    triggers:
      - set-flag: {hafenmeisterin-misstrauisch: false}
  spur-bereit:
    triggers:
      - set-flag: {spur-erklaert: true}
      - emit-text: Verknüpfe die bekannte Abfahrtszeit mit dem Zeugen in der Hafenkneipe.
runtime-fragments:
  condition:
    - nachricht-gefunden: true
      turm-besucht: true
      spur-erklaert: false
      then:
        signal: spur-bereit
    - else:
        emit-text: Gib weitere Hinweise passend zum bisherigen Wissensstand.
  "Die Figur sucht einen Zugang zum Leuchtturm": >
    Die Tür klemmt. Nenne Rost am Schloss und einen erreichbaren Seiteneingang.
  "Die Figur untersucht das Innere des Leuchtturms":
    emit-text: Halte Wind, feuchten Stein und das Knarren der Treppe präsent.
    condition:
      - nachricht-gefunden: false
        then:
          "Die Figur sucht unter der Treppe nach Hinweisen": >
            Lass sie hinter einer losen Stufe die Nachricht der Wärterin finden.
      - nachricht-gefunden: true
        then:
          "Die Figur verfolgt die Spur aus der gefundenen Nachricht": >
            Verknüpfe die erwähnte Abfahrtszeit mit einem Zeugen in der Hafenkneipe.
  "Die Figur spricht mit der Hafenmeisterin":
    condition:
      - hafenmeisterin-misstrauisch: false
        then:
          "Die Hafenmeisterin ist gesprächsbereit": Sie beantwortet sachliche Fragen offen.
      - hafenmeisterin-misstrauisch: true
        then:
          "Die Hafenmeisterin ist misstrauisch": >
            Lass sie zurückhaltend antworten; eine glaubhafte Entschuldigung kann helfen.
---
# Das verschwundene Licht

## Spielleitung
Leite ein geheimnisvolles Hafenabenteuer auf Deutsch.
Antworte knapp, beschreibe konkrete Folgen und akzeptiere plausible Lösungswege.
Entscheidungen der Spielfigur trifft der Mensch.

## Welt und Geheimnis
Der Leuchtturm von Nebelhafen ist seit drei Nächten dunkel.
Die Wärterin hat eine Schmugglergruppe verfolgt und sich versteckt.
Ihre Nachricht unter der Treppe lautet: „Fragt im schiefen Mast nach der
Abfahrt zur dritten Glocke.“ Ein Seemann in der Hafenkneipe kennt ihr Versteck.
Die Hafenmeisterin möchte helfen, fürchtet aber einen Skandal.

## Verlauf und Ende
Gib Hinweise schrittweise. Eine passende Suche kann den Brief auch ohne
genau vorgegebene Formulierung finden. Die Figur darf kreative Zugänge nutzen.
Beende das Abenteuer, wenn die Wärterin gefunden und das dunkle Licht erklärt ist.
```

Dieses Beispiel kombiniert strukturierte Ortswechsel, einmalige Fortschritte,
einen reversiblen sozialen Zustand, eigenständige Ereignisprüfungen und eine
einmalige direkte Regel mit mehreren UND-Voraussetzungen sowie ein `else` als
Fallback. Dazu kommen geerbte und bedingte Hinweise. Das Signal `spur-bereit`
wird durch die direkte Regel ausgelöst; die übrigen Signale prüfen Ereignisse. Das Ende bleibt eine erzählerische Regel;
ein eigenes Engine-Feld `ending` oder ein automatischer Abschluss-Trigger existiert nicht.

## 9. Grenzen des Formats und häufige Fehler

| Wunsch oder Schreibweise | Aktuell unterstützter Weg / Grenze |
| --- | --- |
| Mehrere Voraussetzungen gleichzeitig | Mehrere Flag- und/oder Ortseinträge im `condition`-Mapping oder im bedingten Listeneintrag vor `then` gelten als UND; auch geerbte Bedingungen müssen erfüllt sein |
| Alternativen | Alle passenden `condition`-/`then`-Zweige gelten; optionales abschließendes `else` nur ohne Treffer; Ereignisalternativen als Text in einer Signalbeschreibung |
| `and`, `or`, `not` als YAML-Operatoren | Keine solchen Operatoren; UND durch mehrere Bedingungen, Alternativen durch Zweige, Flag-Negation durch `false`, Orts-Fallbacks durch Zweige/`else` |
| Flag umschalten oder zählen | Nur feste `set-flag`-Zuweisungen; für Rücksetzen eigenes Signal, keine `toggle`-/Zähleraktion |
| Gegenstand vergeben, teleportieren, Dialog öffnen | Kein solcher strukturierter Trigger; erzählerisch beschreiben |
| Ortsbedingung wie `condition: {location: Kai}` | Exakter Vergleich mit dem aktuellen `world-state.location`; auch mit Flag-Bedingungen kombinierbar |
| Hinweise anhand von Flags und/oder Ort ausführen | Direkte Regeln unter `runtime-fragments.condition`; benannte Fragmente bleiben semantisch ausgewählte Kandidaten |
| Inventar, Personen, Fakten als `world-state`-Felder | Im Frontmatter ungültig; im Markdown beschreiben, binäre Fortschritte als Flags |
| Signal mit leerem `triggers` | Ungültig; mindestens eine `set-flag`- oder `emit-text`-Aktion erforderlich |
| `set-flag: {}` | Ungültig; eine `set-flag`-Aktion muss mindestens ein deklariertes Flag zuweisen |
| `description`, `setting` oder `emit-text` als leerer Text | Ungültig, wenn angegeben; weglassen, wenn nicht benötigt |
| Unbekannte oberste YAML-Felder wie `title` oder `inventory` | Der Parser ignoriert sie; sie konfigurieren kein Spielsystem |

Innerhalb von `initial-situation`, `world-state` und Signaldefinitionen werden
unbekannte Felder abgelehnt. In Fragmentgruppen werden andere Schlüssel hingegen
als Kinder interpretiert. Ein Tippfehler kann dort einen neuen Suchpfad erzeugen.
YAML-Einrückung ist deshalb Teil des Formats: Verwende Leerzeichen und halte
die gezeigte Hierarchie ein. Namen und Texte mit Doppelpunkten oder anderen
YAML-Sonderzeichen solltest du zitieren. Doppelte Mapping-Schlüssel vermeiden.

## 10. Das eigene Spiel prüfen

1. Importiere die Datei. Importfehler betreffen oft YAML-Typen, unbekannte
   Verweise, doppelte Fragmentpfade oder einen Startort außerhalb des Katalogs.
2. Starte eine neue Sitzung und prüfe Einführung, Startort und Startflags.
3. Teste erfolgreiche Ereignisse mit verschiedenen natürlichen Formulierungen.
   Prüfe auch Absicht, gescheiterten Versuch und bloße Erwähnung als Gegenfälle.
4. Prüfe Ortswechsel und Flag-Zustände im Debug-Panel. Bei Signalen sind die
   `signal_check`-Ergebnisse, ausgelöste Signale und tatsächlich angewendete
   Flag-Zuweisungen aussagekräftiger als eine passende Erzählung allein.
   Eigene Karten zeigen jedes erkannte Signal, jede Flag-Zuweisung mit altem
   und zugewiesenem Wert sowie jeden emittierten Text mit seiner Herkunft.
   Auch erneute Zuweisungen desselben Werts bleiben sichtbar. Textkarten
   unterscheiden Signaltexte, direkte Flag-Regeln und ausgewählte Runtime-Fragmente.
5. Prüfe bedingte Fragmente vor und nach einer Zustandsänderung. Beachte ihre
   Auswahl für folgende Antworten und die fehlende Signalkaskade im selben Zug.
   Prüfe direkte Regeln ohne passenden Erzählfokus, mehrere gleichzeitig passende
   Zweige und `else` ausschließlich ohne passenden Geschwisterzweig.
   Prüfe bei `emit-text`-Signalen den verborgenen Kontext der nächsten Runde
   und bei einmaligen Ereignissen, dass Bedingung und Flag-Zuweisung eine
   Wiederholung verhindern.
6. Lade die Seite neu und setze den Spielstand fort. Orte, Flags und Verlauf
   sollen erhalten bleiben. Teste anschließend auch eine frische Sitzung.

Eine gültige Datei garantiert keine perfekte Ereigniserkennung durch jedes
lokale Modell. Klare, breit formulierte Ereignisbeschreibungen und gut
unterscheidbare Suchpfade machen das Spiel leichter prüfbar.

Weitere Vorlagen:

- [Komplexes Leuchtturm-Spiel](../examples/test-game.md) mit verschachtelten
  Fragmenten und Signalverweisen ohne explizite Ereignisbeschreibungen.
- [Signal-Test-Spiel](../examples/signal-test-game.md) mit eigenständigen Signalen.
- [Manuelle Signal-Prüfanleitung](../examples/signal-test-game-testing.md).

## 11. Grundlage dieser Referenz

Die beschriebenen Varianten stammen aus dem aktuellen Parser und der Laufzeit,
nicht aus geplanten Erweiterungen:

- [Szenario-Parser](../web/src/jsMain/kotlin/de/aidadventure/web/persistence/ScenarioFrontmatter.kt)
  und [Parser-Tests](../web/src/jsTest/kotlin/de/aidadventure/web/persistence/ScenarioFrontmatterTest.kt).
- [Dateiimport](../web/src/jsMain/kotlin/de/aidadventure/web/persistence/ScenarioImport.kt).
- [Signalprüfung](../server/src/main/kotlin/de/aidadventure/server/runtimefragments/SignalChecks.kt)
  und [Bedingungsfilter und Flag-Zuweisungen](../server/src/main/kotlin/de/aidadventure/server/runtimefragments/RuntimeSignalState.kt).
- [Fragmentauswahl](../server/src/main/kotlin/de/aidadventure/server/runtimefragments/RuntimeFragmentResolver.kt),
  [World-State-Auswertung](../server/src/main/kotlin/de/aidadventure/server/worldstate/WorldStateResolver.kt)
  und [Prompt-Aufbau](../server/src/main/kotlin/de/aidadventure/server/game/PromptFactory.kt).
