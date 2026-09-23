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
  - Kai
  - Leuchtturm
  - Hafenkneipe
```

`world-state` ist der initiale Zustand einer neuen Sitzung. `locations` ist
optional; wenn vorhanden, ist es eine nichtleere Liste eindeutiger, nichtleerer
Strings. Ein gesetzter Startort muss Mitglied dieser Liste sein. Ohne Katalog
bleibt eine Ortsauflösung fail-closed unverändert.

Der Browser entfernt Frontmatter weiterhin aus dem Spielleiter-Prompt, reicht
den Katalog aber in Turn- und Prefill-Anfragen weiter. Der Katalog verändert
somit nicht den deterministischen Sprachprompt-Präfix oder dessen Cache.

## Auflösung

Nach dem sichtbaren Turn erzeugt der Primary-Server eine strukturierte Antwort
für den aktuellen Ort. Das Schema erlaubt nur Katalognamen und den bisherigen
Ort. Der Auftrag verlangt den Ort, an dem die Figur nach der Antwort tatsächlich
ist; bei verweigerter, geplanter oder unklarer Bewegung bleibt der bisherige Ort.
Kotlin übernimmt einen veränderten Namen nur, wenn er katalogisiert ist und als
vollständiger Textbestandteil in der Spielleiterantwort vorkommt.

Die freie `movement`- plus `location`-Kette entfällt. Narrative-Focus, der
retained Slot und der nachfolgende kanonische Warmup bleiben unverändert.

## Tests

Tests decken Katalog-Parsing, Startort-Mitgliedschaft, die Browser-zu-Server-
Weitergabe, das Orts-Enum, `Kai` nach `Leuchtturm` und die Beibehaltung bei
unbestätigten Ortsnennungen ab. Die Cache-Vertragstests bleiben erhalten.
