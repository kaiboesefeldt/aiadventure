# Aktiver Handlungsfaden im WorldState

## Ziel

Der persistierte WorldState erhält neben dem kanonischen Ort einen einzelnen
aktiven Handlungsfaden. Er hält für das Primary-Modell den momentan
dominanten, noch ungelösten Auftrag, die Gefahr oder Verpflichtung sichtbar.
Er ist eine weiche Erzählhilfe, keine zusätzliche harte Weltwahrheit.

## Datenvertrag

`WorldState` besitzt `location: String` und `activeThread: String`, beide mit
leerem Standardwert. Ein nichtleerer Faden ist höchstens 96 Zeichen lang.
Beide Felder sind unabhängig: Eine Handlung kann einen Faden setzen oder
fortführen, obwohl der Ort unbestimmt bleibt.
Der Browser persistiert ihn wie den Ort; bestehende Sitzungen werden durch den
Defaultwert kompatibel gelesen. Szenario-Frontmatter initialisiert weiterhin
nur `location` und darf keinen Handlungsfaden vorgeben.

Der Primary-Snapshot rendert den Ort unverändert als autoritativen Fakt und
den nichtleeren Faden getrennt als `OPEN STORY THREAD`. Der Warmup und der
nächste Turn erhalten denselben akzeptierten Snapshot, damit der Warmup ein
exakter Prefix des Turn-Prompts bleibt.

## Secondary-Auflösung

Nach dem sichtbaren Turn liefert die Ortsauflösung zuerst einen Zwischenstand;
auch bei leerem bisherigen Ort kehrt sie nicht vorzeitig aus dem Resolver
zurück. Anschließend folgt genau ein weiterer, kurzer Secondary-Aufruf mit dem vorherigen
Handlungsfaden, der letzten Spieleraktion und der letzten GM-Antwort. Seine
Schema-Antwort lautet ausschließlich `{"activeThread":"..."}`; der leere
String bedeutet, dass kein dominanter offener Faden besteht.

Der feste Prompt weist das Modell an, nur einen bereits aus dem Eingang
belegten, ungelösten Auftrag, eine Gefahr oder Verpflichtung beizubehalten,
zu ersetzen oder zu leeren. Es darf keinen neuen Plot, keine Begründung und
keine Alternativen erzeugen. Der Server akzeptiert nur Strings bis 96 Zeichen.
Bei Schema-, Transport- oder Validierungsfehlern bleibt der vorherige Faden
erhalten. Ein erfolgreicher Faden-Call aktualisiert ihn auch dann, wenn die
Ortsauflösung keinen neuen Ort ergeben hat. Weder Antworttext noch Fadeninhalt
werden geloggt.

## Ablauf und Tests

Die beiden Aufrufe bleiben sequenziell: Ortsentscheidung und gegebenenfalls
Ortsextraktion, danach Handlungsfaden. Erst der daraus resultierende State
wird in SSE `done` und den anschließenden Warmup übernommen. Tests prüfen die
Schema-Grenze, Übernahme, Fehler-Fallback, Snapshot-Darstellung, Browser-
Serialisierung sowie die Cache-Prefix-Beziehung für State mit Faden.
