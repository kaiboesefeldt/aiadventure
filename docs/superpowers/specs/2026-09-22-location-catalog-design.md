# Kanonischer Ortskatalog

## Ziel

Ein Szenario benennt spielbare Orte in der YAML-Frontmatter. Die Ortsauflösung
speichert ausschließlich diese kanonischen Namen. Freie oder zusammengesetzte
Modellantworten wie `Turm Gegenstand` bleiben ohne Wirkung.

## Frontmatter

```yaml
world-state:
  location: Kai
locations:
  Kai: "Umschlagplatz am Hafen mit Pollern und Tauwerk."
  Leuchtturm: "Alter Steinturm am Ende der Mole."
  Hafenkneipe: "Gasthaus am Wasser, in dem Seeleute verkehren."
```

`world-state` ist der initiale Zustand einer neuen Sitzung. `locations` ist
optional; wenn vorhanden, ist es eine nichtleere Map eindeutiger, nichtleerer
Schlüssel auf String-Beschreibungen. Beschreibungen dürfen leer sein. Ein
gesetzter Startort muss Mitglied der Map-Schlüssel sein. Ohne Katalog bleibt
eine Ortsauflösung fail-closed unverändert.

Der Browser entfernt Frontmatter weiterhin aus dem Szenariotext und reicht den
Katalog in Turn-, Prefill- und Warmup-Anfragen weiter. Alle serverseitig
aufgebauten Prompts erhalten denselben stabil sortierten Katalog. Jeder Eintrag
enthält den Schlüssel und, falls nicht leer, seine Beschreibung. Das gilt für
Spielerturns, Warmups, Recaps, World-State-Auflösung und Fragmentbewertung.

## Auflösung

Nach dem sichtbaren Turn erzeugt der Primary-Server eine strukturierte Antwort
für den aktuellen Ort. Das Schema erlaubt nur Katalogschlüssel und den bisherigen
Ort. Der Auftrag verlangt den Ort, an dem die Figur nach der Antwort tatsächlich
ist; bei verweigerter, geplanter oder unklarer Bewegung bleibt der bisherige Ort.
Kotlin übernimmt einen veränderten Namen nur, wenn er ein Katalogschlüssel ist und als
vollständiger Textbestandteil in der Spielleiterantwort vorkommt.

Die freie `movement`- plus `location`-Kette entfällt. Narrative-Focus, der
retained Slot und der nachfolgende kanonische Warmup bleiben unverändert.

## Tests

Tests decken Katalog-Parsing, Startort-Mitgliedschaft, die Browser-zu-Server-
Weitergabe, das Orts-Enum, `Kai` nach `Leuchtturm` und die Beibehaltung bei
unbestätigten Ortsnennungen ab. Die Cache-Vertragstests bleiben erhalten.
