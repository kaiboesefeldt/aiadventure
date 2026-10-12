---
initial-situation:
  world-state:
    location: Kai
  setting: |
    Du bist eine frisch eingetroffene Abenteurerin oder ein frisch eingetroffener
    Abenteurer im Hafen von Nebelhafen. Der alte Leuchtturm am Ende der Mole ist
    seit drei Nächten dunkel, und die Hafenmeisterin behauptet, dass dort oben
    etwas Unruhiges gesehen wurde.

    - Es ist später Abend, Nebel zieht vom Wasser heran.
    - Ein schmaler Kai führt zum Leuchtturm.
    - In der Hafenkneipe kursieren Gerüchte über ein geheimes Signallicht.
    - Du befindest dich am Kai von Nebelhafen.
    - Die Hafenmeisterin spricht mit dir und berichtet, dass der alte Leuchtturm
      seit drei Nächten dunkel ist. Sie hat dir eine kleine Belohnung
      versprochen, wenn du herausfindest, was den Leuchtturm stört.

locations:
  Kai: "Umschlagplatz am Nebelhafen mit Pollern, Tauwerk und Blick auf die Mole."
  Leuchtturm: "Alter Steinturm am Ende der Mole; seit drei Nächten ohne Signallicht; mit Seilen abgesperrt."
  Hafenkneipe: "Name: \"Zum schiefen Mast\". Raues Gasthaus am Wasser, in dem Seeleute Gerüchte austauschen."
  Schuppen der Hafenmeisterin: "Abgelegener Lagerschuppen nahe dem Kai, verwaltet von der Hafenmeisterin."
  Steg: "Schmaler, nasser, glitschiger Holzsteg über dunklem Hafenwasser."
flags:
  war-im-leuchtturm: false
  hat-hinweis: false
  hafenmeisterin-misstrauisch: false
signals:
  betritt-leuchtturm:
    description: >
      Die Figur hat den Leuchtturm in diesem Austausch tatsächlich betreten; Absicht, erfolgloser Versuch und Außenansicht zählen nicht.
    condition: {war-im-leuchtturm: false}
    triggers:
      - set-flag:
          war-im-leuchtturm: true
  entdeckt-hinweis:
    description: >
      Die Figur hat in diesem Austausch einen konkreten Hinweis zum Hafenfall gefunden oder erkannt; bloßes Suchen zählt nicht.
    condition: {hat-hinweis: false}
    triggers:
      - set-flag:
          hat-hinweis: true
  verunsichert-hafenmeisterin:
    description: >
      Die Hafenmeisterin ist in diesem Austausch durch das Verhalten der Figur tatsächlich misstrauisch geworden; ein neutrales Gespräch zählt nicht.
    condition: {hafenmeisterin-misstrauisch: false}
    triggers:
      - set-flag:
          hafenmeisterin-misstrauisch: true
  beruhigt-hafenmeisterin:
    description: >
      Die zuvor misstrauische Hafenmeisterin hat sich in diesem Austausch durch das Verhalten der Figur tatsächlich beruhigt; ein erfolgloser Versuch zählt nicht.
    condition: {hafenmeisterin-misstrauisch: true}
    triggers:
      - set-flag:
          hafenmeisterin-misstrauisch: false
runtime-fragments:
  "Die Figur steht am Leuchtturm":
    "Die Figur untersucht den dunklen Leuchtturm von außen und sucht einen Zugang": >
      Lass die Erkundung eine greifbare Unregelmäßigkeit offenbaren, die zum Betreten oder zu einer vorsichtigen Entscheidung einlädt.
    "Die Figur steht vor der versperrten Tür und versucht Schloss, Kette oder Spuren zu deuten": >
      Mache deutlich, dass der Zugang kürzlich benutzt wurde, ohne die Lösung preiszugeben; biete einen glaubwürdigen nächsten Ansatz an.
    "Die Figur bewegt sich über die Mole zum Leuchtturm und ist Wind und Wasser ausgesetzt": >
      Betone die Abgeschiedenheit des Weges und platziere eine kleine Beobachtung, die zeigt, dass jemand diesen Weg kürzlich kannte.
    "Die Figur entdeckt Wasser, Zugluft oder einen niedrigen Durchgang unter dem Leuchtturm": >
      Deute eine verborgene Verbindung zum Hafen an und beschreibe die Risiken eines Vordringens in die Enge.
    "Die Figur fragt nach Schlüsseln, Zugängen oder wer den Leuchtturm betreten darf": >
      Streue einen Widerspruch zwischen offizieller Zuständigkeit und tatsächlicher Gelegenheit ein.
  "Die Figur betritt tatsächlich den Leuchtturm oder befindet sich in seinem Inneren":
    emit-text: >
      Die Figur ist im Leuchtturm. Beschreibe den feuchten Steinboden, die enge
      Wendeltreppe und die dunkle Laternenkammer. Der Zugang kann durch die Tür
      oder einen anderen plausiblen Weg erfolgen. Bloßes Annähern oder Betrachten
      von außen zählt nicht als Betreten.
  "Die Figur entdeckt, untersucht oder liest einen Hinweis zum Hafenfall":
    emit-text: >
      Gib einen greifbaren Hinweis auf das ausgefallene Signallicht oder
      verdächtige Vorgänge im Hafen, etwa eine Notiz, eine auffällige Spur,
      eine Markierung oder eine Zeugenaussage. Die Figur soll daraus einen
      einfachen nächsten Schritt ableiten können. Eine allgemeine Suche ohne
      Fund zählt noch nicht als entdeckter Hinweis.
  "Die Figur erkundet das Innere des Leuchtturms":
    "Die Figur schaut sich im Leuchtturm um": >
      Beschreibe die feuchten Wände, die Treppe und die Laternenkammer und
      biete einen einfachen Ansatz zur weiteren Erkundung.
    "Die Figur entdeckt oder untersucht einen Hinweis im Leuchtturm":
      emit-text: >
        Die Figur erkennt eine auffällige Spur, Notiz oder Markierung und
        erhält einen einfachen Ansatz, sie weiter zu verfolgen.
    "Die Figur untersucht die Treppe, die Laternenkammer oder die Lampe": >
      Halte die Gefahr des nassen Gemäuers präsent. Zeige ein auffälliges Detail
      wie frisches Öl, Ruß, eine veränderte Abdeckung oder eingeritzte Zeichen,
      das auf eine kürzliche Manipulation hindeutet und weiter untersucht werden kann.
    "Die Figur sucht unter der Treppe, an den Wänden oder in einem Durchgang": >
      Zeige eine verborgene Klappe, eine Notiz oder Spuren einer kürzlichen
      Benutzung. Biete eine Wahl zwischen vorsichtiger Untersuchung und weiterem Vordringen.
  "Die Figur erkundet die Mole":
    "Die Figur folgt nassen Spuren im Turm oder auf der Mole": >
      Führe die Spur zu einem neuen Ort oder Detail und lass offen, ob sie von Helfern, Schmugglern oder einer falschen Fährte stammt.
    "Die Figur hört eine Schauergeschichte über Glocken, Wasser oder die Mole": >
      Verbinde den Aberglauben mit einem realen, überprüfbaren Detail, damit die Geschichte spielbar bleibt.
  "Die Figur beobachtet Signale am Hafen":
    "Die Figur achtet auf Nebelhorn, Glocken oder wiederkehrende Geräusche im Hafen": >
      Lass das Geräusch einen Rhythmus oder Zeitpunkt erkennen, der für die nächste Handlung nützlich sein könnte.
    "Die Figur sieht Lichtsignale draußen auf dem Wasser oder vom Turm aus": >
      Behandle die Lichtfolge als absichtliche Kommunikation und gib der Figur eine konkrete Möglichkeit, Muster, Empfänger oder Zeitpunkt zu verfolgen.
    "Die Figur versucht ein Blinkmuster zu zählen, zu erwidern oder zu entschlüsseln": >
      Lass das Muster eine vorsichtige Reaktion auslösen, aber halte seine genaue Bedeutung zunächst mehrdeutig.
  "Die Figur untersucht Hafen und Ladung":
    "Die Figur untersucht Kisten, Taue oder frische Ladung am Kai": >
      Lege eine kleine Unstimmigkeit in Verpackung oder Herkunft offen, die einen Zusammenhang zum Leuchtturm nahelegt.
    "Die Figur beobachtet ein unbeleuchtetes Boot oder eine Bewegung auf dem dunklen Wasser": >
      Lass die Szene Dringlichkeit erzeugen; die Figur soll entscheiden können, ob sie verfolgt, verborgen bleibt oder Hilfe holt.
    "Die Figur vergleicht Kreidezeichen, Kerben oder Markierungen an verschiedenen Orten": >
      Bestätige eine Verbindung zwischen zwei Orten und weise auf einen dritten Ort hin, der als Nächstes überprüft werden sollte.
    "Die Figur fragt nach Gezeiten, Uhrzeiten oder dem Ablauf der Nacht": >
      Gib einen zeitgebundenen Hinweis, der die Handlung vorantreibt und eine bevorstehende Gelegenheit ankündigt.
    "Die Figur wartet auf eine verdächtige Bewegung am Wasser": >
        Im kurzen Stillwasser gleitet ein unbeleuchtetes Boot an der Mole vorbei.
        Die Figur kann es verfolgen, unbemerkt beobachten oder die Hafenwache holen.
    "Die Figur öffnet Fässer oder sucht zwischen harmlosen Waren nach Verstecktem": >
      Lass sie etwas Verdächtiges finden, das Fragen aufwirft, aber keinen vollständigen Beweis liefert.
    "Die Figur bemerkt einen Beobachter am Kai oder fühlt sich verfolgt": >
      Lass die beobachtende Person auf die Nachforschungen reagieren und eröffne eine stille, spannungsvolle Möglichkeit zur Konfrontation oder Verfolgung.
    "Die Figur erkundigt sich nach alten Schulden, Händlern oder Verbindungen zu den äußeren Inseln": >
      Verknüpfe eine persönliche Verpflichtung mit dem Hafenfall, ohne die Person vorschnell festzulegen.
    "Die Figur prüft Gerüchte über zusätzliche Boote im Nebel oder fehlende Einträge im Hafenbuch": >
      Lass die Zahlen nicht ganz aufgehen und gib eine Möglichkeit, die nächste Ankunft oder Abfahrt zu überwachen.
  "Die Figur durchsucht das Büro der Hafenmeisterin oder fragt nach dem Schiffsregister": >
    Zeige eine Lücke in den Unterlagen, die zu einer bestimmten Nacht, Ladung oder Person weiterführt.
  "Die Figur durchsucht das Büro der Hafenmeisterin":
    "Die Figur findet oder bemerkt einen versiegelten Brief": >
      Behandle den Brief als heiklen Hinweis: Sein Absender, Zeitpunkt oder Siegel soll eine neue Frage eröffnen, nicht die ganze Antwort liefern.
  "Die Figur hört, wie die Hafenmeisterin die Nachtwache oder Besatzung beschuldigt": >
    Lass die Anschuldigung auffällig bequem wirken und biete einen Ansatz, die Behauptung unabhängig zu prüfen.
  "Die Figur spricht mit der Hafenmeisterin":
    "Die Figur spricht die Belohnung oder widersprüchlichen Aussagen an": >
      Lass sie ausweichen oder zu schnell helfen wollen; biete eine kleine Reaktion, die Misstrauen begründen kann.
    "Die Figur erwähnt Rat, Ruf des Hafens oder einen möglichen Skandal": >
      Lass ihre Sorge spürbar werden, ohne sie zur Schuldigen zu erklären; ihre Prioritäten sollen die Figur zum Nachfragen reizen.
    "Die Figur legt eine Gezeitentabelle oder einen nächtlichen Zeitpunkt vor": >
      Lass eine unwillkürliche Reaktion erkennen, die auf Wissen über den nächsten kritischen Zeitpunkt hindeutet.
    "Die Figur will die Sache öffentlich machen oder weitere Leute einweihen": >
      Lass die Hafenmeisterin auf Diskretion drängen und mach sichtbar, welchen Preis die Figur für Offenheit oder Heimlichkeit zahlen könnte.
    "Die Figur rekonstruiert den ersten Alarm oder hakt bei Details der Geschichte nach": >
      Lass sich ein Detail der Erzählung verschieben und führe zu einem überprüfbaren Widerspruch.
  "Die Figur spricht die Hafenmeisterin skeptisch an, widerspricht ihr oder beschuldigt sie":
    emit-text: >
      Die Hafenmeisterin reagiert misstrauisch und zurückhaltend. Lass sie nach
      dem Grund für den Vorwurf fragen. Ein bestimmter Fund oder Beweis ist
      dafür nicht nötig; ein sachliches Gespräch allein löst kein Misstrauen aus.
  "Die Figur beruhigt die Hafenmeisterin, entschuldigt sich oder sichert ihr freundlich Hilfe zu":
    emit-text: >
      Die Hafenmeisterin entspannt sich und wird wieder gesprächsbereit.
      Ihre Beruhigung hängt vom versöhnlichen Verhalten der Figur ab,
      nicht von einer bestimmten Lichtkennung oder einem besonderen Beweis.
  "Die Figur sucht Hinweise in der Hafenkneipe":
    "Die Figur fragt nach seltsamen Geräuschen, Bojen oder nächtlichen Signalen": >
      Lass ein Gerücht ein Muster liefern, das zur Beobachtung am Wasser oder zum Leuchtturm zurückführt.
    "Die Figur erkundigt sich nach einem fremden Boten, Besucher oder einer Person am Turm": >
      Gib eine prägnante, aber unvollständige Beschreibung und einen Ort, an dem die Person zuletzt gesehen wurde.
    "Die Figur spricht mit Dockarbeitern über Kontrollen, Zoll oder nächtliche Fracht": >
      Lass eine auffällige Veränderung im üblichen Ablauf ans Licht kommen und weise auf einen möglichen Nutznießer hin.
    "Die Figur beobachtet einen Streit zwischen Kapitänen oder fragt nach gefährlichen Fahrten": >
      Lass beide Seiten ein Detail bestätigen, das auf absichtliche Irreführung im Nebel hindeutet.
    "Die Figur sucht nach heimlichen Zahlungen oder auffälligen Gästen": >
      Streue einen Hinweis auf eine Geldübergabe ein und gib der Figur eine konkrete Spur, ohne den Empfänger zu benennen.
    "Die Figur fragt nach einem auffälligen Kleidungsstück oder einer wiedererkennbaren Person": >
      Lass mehrere Zeugen ein kleines gemeinsames Merkmal erinnern, das zu einer neuen Befragung oder Beobachtung führt.
    "Die Figur erkundigt sich nach dem leeren Zimmer im Gasthaus oder nach nächtlichen Gästen": >
      Mache die Abwesenheit verdächtig und verknüpfe das Zimmer mit einem Zeitpunkt, zu dem jemand ungesehen kommen könnte.
  "Die Figur recherchiert die Geschichte des Leuchtturms":
    "Die Figur fragt nach seinem Verschwinden": >
      Lass eine alte Anschuldigung mit dem aktuellen Fall korrespondieren und zeige, wo ein übersehener Nachlass oder Zeuge zu finden sein könnte.
---
# Signal-Test: Der Leuchtturm von Nebelhafen

## Deine Rolle

Du hast die Rolle einer knappen, reaktionsschnellen Spielleitung und setzt
das Abenteuer anhand des Szenarios und des bisherigen Gesprächs fort.

## Wichtige Hinweise

- Das Spiel soll klein, direkt und gut testbar sein.
- Antworte als Spielleitung in klaren, kurzen Abschnitten.
- Beschreibe Konsequenzen knapp, aber erfinde genug Details, damit das Spiel
  voranschreitet.
- Bleibe bei einer stimmungsvollen, leicht rätselhaften Hafenatmosphäre.
