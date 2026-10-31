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
| `signals.<name>.condition` | Mapping mit genau einem Flag und booleschem Sollwert | Ja; keine zusätzliche Flag-Schranke |
| `signals.<name>.triggers` | Nichtleere Liste von `set-flag`-Aktionen | Pflicht je Signal |
| `runtime-fragments` | Mapping mit Textblättern oder verschachtelten Gruppen | Ja; keine Zusatzhinweise |
| Fragmentfeld `emit-text` | Nichtleerer Zusatztext, an Nachfahren vererbt | Ja |
| Fragmentfeld `signal` | Name eines deklarierten Signals, an Nachfahren vererbt | Ja |
| Fragmentfeld `condition` | Einzelne Flag-Bedingung oder Liste von `then`-Zweigen | Ja |

Die letzten drei Fragmentfelder haben eine besondere Kompatibilitätsregel:
Sie sind nur bei vorhandenem obersten `flags`- oder `signals`-Feld Metadaten.
Ohne beide Felder sind sie normale Fragmentnamen. Siehe Abschnitt 7.

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
formuliere dafür passende bedingte Fragmente.

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

Alle fünf Formen sind gültig: einfacher Text, mehrzeiliger Text, zitierter Text,
leerer String und ausgelassene Beschreibung (`null`). Die beiden letzten Formen
erzeugen eine leere Beschreibung. Ortswerte dürfen keine Listen, Objekte, Zahlen
oder booleschen Werte sein.

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

Jedes Signal hat eine nichtleere `triggers`-Liste. Der einzige unterstützte
Aktionstyp ist `set-flag`. Eine Aktion darf mehrere bereits deklarierte Flags
setzen; mehrere Aktionen werden der Reihe nach angewendet. `false` setzt einen
Zustand zurück. Ein mehrfach gesetztes Flag erhält den letzten zugewiesenen Wert.

`description` beschreibt ein Ereignis im zuletzt abgeschlossenen Austausch.
Formuliere sie als überprüfbare Tatsache und grenze Absicht, Versuch und Erfolg
ab. Sie ist kein Befehl, das Ereignis herbeizuerzählen. Die zusätzliche `condition`
schränkt die Prüfung auf genau einen deklarierten Flag-Sollwert ein.
Ohne `condition` entfällt diese Schranke.

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
Fragmentprüfung werden die relevanten Signale nacheinander vom Modell geprüft.
Die Prüfung verlangt genau `Y` oder `N`; nur ein exaktes `Y` löst das Signal aus.
Eine fehlgeschlagene oder ungültige Prüfung setzt dessen Flags nicht.

Alle Flag-Bedingungen der Signalprüfungen beziehen sich auf die Werte **zu Beginn
des Spielzugs**. Erst nach den Prüfungen werden die Zuweisungen erfolgreicher
Signale in Signalreihenfolge angewendet. Es gibt keine Kettenreaktion innerhalb
desselben Zugs: Ein Signal, das `turm-besucht: true` setzt, aktiviert dadurch keine
vorher ineligible Prüfung mit dieser Bedingung im selben Zug.

Ein Signal wird übersprungen, wenn alle seine effektiven Zielwerte bereits
erreicht sind. Konfliktbeispiel: Beginnt `alarm` mit `false`, wird ein Signal,
das ausschließlich `alarm: false` setzen würde, gar nicht geprüft, auch wenn ein
anderes Signal in diesem Zug `alarm: true` setzen könnte. Sind dagegen zwei
konfligierende Signale beide prüfberechtigt und erfolgreich, gewinnt bei der
Anwendung die spätere Zuweisung. Vermeide solche Konflikte im Spieldesign.

### Signale ohne `description`: kompatible Varianten

Ohne explizite Beschreibung wird die Ereignisfrage so gebildet:

1. Gibt es Fragmente mit einem Verweis `signal: <name>`, dienen deren vollständige
   Schlüsselpfade als Ereignisbeschreibungen. Ihre geerbten Flag-Bedingungen werden
   mit der Signalbedingung kombiniert.
2. Mehrere passende Pfade desselben Signals bilden Alternativen: Ein tatsächlich
   eingetretenes Ereignis genügt. Gleichwertige Beschreibungen werden dedupliziert.
3. Ohne Fragmentverweis wird der Signalname selbst zur Ereignisbeschreibung.

Mit expliziter `description` gelten ausschließlich diese Beschreibung und die
Signalbedingung; Bedingungen der referenzierenden Fragmente schränken die
Signalprüfung dann nicht ein. Für neue Spiele sind explizite Beschreibungen die
klarste Form. Ein Signal benötigt kein Runtime-Fragment.

## 7. Runtime-Fragmente: situationsabhängige Hinweise

Runtime-Fragmente liefern der Spielleitung zusätzliche, im normalen Transcript
verborgene Hinweise. Ihre Schlüssel beschreiben die Situation; ihre Textwerte
beschreiben, wie die Spielleitung darauf reagieren soll.

Nach einem Austausch sucht die Anwendung unter den flagberechtigten Fragmenten
semantisch einen Kandidaten anhand des aktuellen Erzählfokus bzw. des letzten
sichtbaren Austauschs und des aktuellen Orts. Eine weitere Modellprüfung kann
den Kandidaten ablehnen. Pro Auswahl wird höchstens ein Fragment übernommen.
Ein übernommener Hinweis kommt in den Kontext für folgende Antworten; er verändert
nicht rückwirkend die bereits sichtbare Antwort. Die Auswahl erfolgt vor der
abschließenden Signalprüfung und berücksichtigt deren neue Flag-Werte in dieser
Auswahl noch nicht. Fragmente sind deshalb keine synchronen Sofort-Trigger.

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

### D. Einfache Flag-Bedingung

```yaml
flags: {nachricht-gefunden: false}
runtime-fragments:
  "Die Figur untersucht den Brief":
    condition: {nachricht-gefunden: true}
    emit-text: Erinnere an die bereits entzifferte Abfahrtszeit.
```

Die Bedingung filtert die Verfügbarkeit des Fragments. Sie setzt keine Flags.
Sie darf genau ein deklariertes Flag mit `true` oder `false` enthalten.
Nachfahren erben die Bedingung. Für mehrere gleichzeitig nötige Flags kannst
du Gruppen verschachteln; alle geerbten Bedingungen müssen erfüllt sein (UND).
Widersprüchliche Bedingungen erzeugen ein unerreichbares Fragment.

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

`condition` als Liste benötigt mindestens einen Zweig. Jeder Zweig enthält
genau einen Flag-Sollwert und `then`. `then` ist ein nichtleeres Mapping von
Fragmentnamen zu Texten oder weiteren Gruppen. Verschachtelte Bedingungen,
Metadaten und weitere Zweiglisten funktionieren innerhalb dieser Kinder ebenso.
`then` ist kein einzelner Text und keine Liste.

Zweige sind unabhängige Varianten, keine `if/else`-Kette: Mehrere Zweige können
gleichzeitig verfügbar sein. Bedingungen oberhalb des Zweigs bleiben bestehen.
Ein Knoten kann zusätzlich gewöhnliche Kinder enthalten; diese erben den
Elternkontext, aber keine Bedingung eines Geschwisterzweigs. Einen gemeinsamen
Zusatztext setzt du per `emit-text` am Elternknoten.

Die Zweigmarker `condition` und `then` sowie Flag-Namen werden nicht Teil des
Suchpfads. Deshalb brauchen Zweige unterschiedliche vollständige Blattpfade.
Zweimal derselbe Pfad wird auch bei gegensätzlichen Bedingungen abgelehnt;
das Beispiel verwendet bewusst unterschiedliche Blattnamen.

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
**Die Auswahl eines Fragments löst das Signal nicht automatisch aus.** Der
Verweis dient heute vor allem der Rückfall-Ereignisbeschreibung von Signalen
ohne `description`; tatsächliches Eintreten wird separat geprüft.

### G. Kompatibilitätsregel für Metadaten-Namen

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
werden diese Namen innerhalb von Fragmentknoten als Metadaten interpretiert.
Auf der obersten Ebene unter `runtime-fragments` bleiben Schlüssel Fragmentnamen.
Verwende für neue Spiele aussagekräftige Situationsnamen und deklariere
`flags: {}`, wenn du Metadaten ohne tatsächliche Flags nutzen möchtest.

### H. Leere und ungültige Fragmente

`runtime-fragments: {}` ist zulässig. Jede sonstige Gruppe braucht mindestens
ein gültiges Blatt oder passende eigene Metadaten. Ungültig sind insbesondere:

- Leere Texte, Zahlen, boolesche Werte und Listen als Fragmentwerte.
- Leere Untergruppen und Knoten mit ausschließlich einer einfachen Bedingung.
- Unbekannte Flag- oder Signalverweise.
- Mehrere Flags in derselben einfachen `condition`.
- Leere Zweiglisten, leere `then`-Mappings und zusätzliche Felder im Zweig.
- Doppelte vollständige Blattpfade zwischen bedingten Zweigen.

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
runtime-fragments:
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
einen reversiblen sozialen Zustand, eigenständige Ereignisprüfungen sowie
geerbte und bedingte Hinweise. Das Ende bleibt eine erzählerische Regel;
ein eigenes Engine-Feld `ending` oder ein automatischer Abschluss-Trigger existiert nicht.

## 9. Grenzen des Formats und häufige Fehler

| Wunsch oder Schreibweise | Aktuell unterstützter Weg / Grenze |
| --- | --- |
| Mehrere Voraussetzungen gleichzeitig | Verschachtelte Fragmentgruppen mit einfachen Flag-Bedingungen; pro Signal nur eine Flag-Bedingung |
| Alternativen | Mehrere `condition`-/`then`-Zweige für Fragmente; Ereignisalternativen als Text in einer Signalbeschreibung |
| `else`, `and`, `or`, `not` als YAML-Operatoren | Keine solchen Engine-Operatoren; boolesche Sollwerte und Verschachtelung nutzen |
| Flag umschalten oder zählen | Nur feste `set-flag`-Zuweisungen; für Rücksetzen eigenes Signal, keine `toggle`-/Zähleraktion |
| Gegenstand vergeben, teleportieren, Dialog öffnen | Kein solcher strukturierter Trigger; erzählerisch beschreiben |
| Ortsbedingung wie `condition: {location: Kai}` | Ungültig, sofern `location` kein deklariertes boolesches Flag ist; Situation im Suchtext beschreiben |
| Fragment immer oder alle passenden Fragmente ausführen | Nicht garantiert; semantische Kandidatenauswahl und Relevanzprüfung |
| Inventar, Personen, Fakten als `world-state`-Felder | Im Frontmatter ungültig; im Markdown beschreiben, binäre Fortschritte als Flags |
| Signal mit leerem `triggers` oder `set-flag: {}` | Ungültig; mindestens eine Zuweisung erforderlich |
| `description` oder `setting` als leerer Text | Ungültig, wenn angegeben; weglassen, wenn nicht benötigt |
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
5. Prüfe bedingte Fragmente vor und nach einer Zustandsänderung. Beachte ihre
   Auswahl für folgende Antworten und die fehlende Signalkaskade im selben Zug.
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
  und [Flag-Filter und Zuweisungen](../server/src/main/kotlin/de/aidadventure/server/runtimefragments/RuntimeSignalState.kt).
- [Fragmentauswahl](../server/src/main/kotlin/de/aidadventure/server/runtimefragments/RuntimeFragmentResolver.kt),
  [World-State-Auswertung](../server/src/main/kotlin/de/aidadventure/server/worldstate/WorldStateResolver.kt)
  und [Prompt-Aufbau](../server/src/main/kotlin/de/aidadventure/server/game/PromptFactory.kt).
