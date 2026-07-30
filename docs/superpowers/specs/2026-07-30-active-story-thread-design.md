# Ungelöste Erzählsituation im WorldState

## Ziel

Der persistierte WorldState erhält neben dem kanonischen Ort einen einzelnen
ungelöste Erzählsituation. Sie hält für das Primary-Modell den momentan
dominanten, noch ungelösten Auftrag, die Gefahr oder Verpflichtung sichtbar.
Er ist eine weiche Erzählhilfe, keine zusätzliche harte Weltwahrheit.

## Datenvertrag

`WorldState` besitzt `location: String` und `unresolvedSituation: String`, beide mit
leerem Standardwert. Das llama.cpp-Schema empfiehlt für eine neue nichtleere
Situation höchstens 160 Zeichen; Kotlin übernimmt aber jede valide,
vollständige Modellantwort unabhängig von ihrer Länge.
Beide Felder sind unabhängig: Eine Handlung kann eine Situation setzen oder
fortführen, obwohl der Ort unbestimmt bleibt.
Der Browser persistiert sie wie den Ort und migriert den bisherigen JSON-Namen
`activeThread` beim Lesen. Szenario-Frontmatter initialisiert weiterhin nur
`location` und darf keine Erzählsituation vorgeben.

Der Primary-Snapshot rendert den Ort unverändert als autoritativen Fakt und
die nichtleere Situation getrennt als `UNRESOLVED STORY SITUATION`. Der Warmup und der
nächste Turn erhalten denselben akzeptierten Snapshot, damit der Warmup ein
exakter Prefix des Turn-Prompts bleibt.

## Secondary-Auflösung

Nach dem sichtbaren Turn liefert die Ortsauflösung zuerst einen Zwischenstand;
auch bei leerem bisherigen Ort kehrt sie nicht vorzeitig aus dem Resolver
zurück. Anschließend folgt genau ein weiterer, kurzer Secondary-Aufruf mit dem vorherigen
Erzählsituation, der letzten Spieleraktion und der letzten GM-Antwort. Seine
Schema-Antwort lautet ausschließlich `{"unresolvedSituation":"..."}`; der
leere String bedeutet, dass keine dominante offene Situation besteht.

Der feste Prompt verlangt ausschließlich einen vollständigen Satz mit höchstens
20 Wörtern über den einzelnen offenen Auftrag, die Gefahr oder Verpflichtung.
Er ignoriert Fragen und Optionen und darf sie weder kopieren noch auswählen;
außerdem verbietet er Personen-, Orts-, Atmosphären- und Hintergrundbeschreibungen.
Die 160 Zeichen begrenzen nur die gewünschte Generierung, nicht die Kotlin-Übernahme. Bei Schema-, Transport- oder
Validierungsfehlern bleibt
die vorherige Situation erhalten. Ein erfolgreicher Situations-Call aktualisiert
sie auch dann, wenn die
Ortsauflösung keinen neuen Ort ergeben hat. Im lokalen TRACE-Modus werden die
vollständigen Secondary-Antworten für die Diagnose geloggt.

## Ablauf und Tests

Die beiden Aufrufe bleiben sequenziell: Ortsentscheidung und gegebenenfalls
Ortsextraktion, danach Erzählsituation. Erst der daraus resultierende State
wird in SSE `done` und den anschließenden Warmup übernommen. Tests prüfen die
Schema-Grenze, Übernahme, Fehler-Fallback, Snapshot-Darstellung, Browser-
Serialisierung samt Altwert-Migration sowie die Cache-Prefix-Beziehung.
