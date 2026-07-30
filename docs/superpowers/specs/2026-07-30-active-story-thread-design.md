# Erzählerischer Fokus im WorldState

## Ziel

Der persistierte WorldState erhält neben dem kanonischen Ort einen einzelnen
erzählerischen Fokus. Er hält für das Primary-Modell das Element sichtbar, auf
dem die Geschichte nach dem letzten Turn gerade ruht: etwa einen Ort, ein
Objekt, ein Rätsel, eine Gefahr, eine Beziehung oder ein Vorhaben. Er ist eine
weiche Erzählhilfe, keine zusätzliche harte Weltwahrheit.

## Datenvertrag

`WorldState` besitzt `location: String` und `narrativeFocus: String`, beide mit
leerem Standardwert. Das llama.cpp-Schema empfiehlt für einen neuen nichtleeren
Fokus höchstens 160 Zeichen; Kotlin übernimmt aber jede valide, vollständige
Modellantwort unabhängig von ihrer Länge. Beide Felder sind unabhängig: Eine
Handlung kann einen Fokus setzen oder fortführen, obwohl der Ort unbestimmt bleibt.
Der Browser persistiert sie wie den Ort und migriert den bisherigen JSON-Namen
`activeThread` und `unresolvedSituation` beim Lesen. Szenario-Frontmatter
initialisiert weiterhin nur `location` und darf keinen Fokus vorgeben.

Der Primary-Snapshot rendert den Ort unverändert als autoritativen Fakt und
den nichtleeren Fokus getrennt als `NARRATIVE FOCUS`. Der Warmup und der
nächste Turn erhalten denselben akzeptierten Snapshot, damit der Warmup ein
exakter Prefix des Turn-Prompts bleibt.

## Secondary-Auflösung

Nach dem sichtbaren Turn liefert die Ortsauflösung zuerst einen Zwischenstand;
auch bei leerem bisherigen Ort kehrt sie nicht vorzeitig aus dem Resolver
zurück. Anschließend folgt genau ein weiterer, kurzer Secondary-Aufruf mit dem
vorherigen Fokus, der letzten Spieleraktion und der letzten GM-Antwort. Seine
Schema-Antwort lautet ausschließlich `{"narrativeFocus":"..."}`; der leere
String bedeutet, dass kein erzählerischer Fokus besteht.

Der feste Prompt aktualisiert den Fokus retrospektiv aus vorherigem Fokus,
Spieleraktion und GM-Antwort. Er verlangt eine kurze Phrase für das eine jetzt
zentrale Story-Element; sie darf eine reine Erkundung ausdrücken und muss weder
Konflikt noch Aufgabe sein. Die 160 Zeichen begrenzen nur die gewünschte
Generierung, nicht die Kotlin-Übernahme. Bei Schema-, Transport- oder
Validierungsfehlern bleibt der vorherige Fokus erhalten. Ein erfolgreicher
Fokus-Call aktualisiert ihn auch dann, wenn die
Ortsauflösung keinen neuen Ort ergeben hat. Im lokalen TRACE-Modus werden die
vollständigen Secondary-Antworten für die Diagnose geloggt.

## Ablauf und Tests

Die beiden Aufrufe bleiben sequenziell: Ortsentscheidung und gegebenenfalls
Ortsextraktion, danach erzählerischer Fokus. Erst der daraus resultierende State
wird in SSE `done` und den anschließenden Warmup übernommen. Tests prüfen die
Schema-Grenze, Übernahme, Fehler-Fallback, Snapshot-Darstellung, Browser-
Serialisierung samt Altwert-Migration sowie die Cache-Prefix-Beziehung.
