# llama.cpp Structured Output

## Regel: Kein kleines `n_predict` neben einer endlichen JSON-Grammatik

Für kurze, schema-gebundene llama.cpp-Completion-Aufgaben wird kein eigenes
kleines `n_predict` gesendet. Das JSON-Schema wird von llama.cpp in eine
Grammatik übersetzt und begrenzt die Ausgabe bereits, beispielsweise durch
`enum`, `const`, `minLength` und `maxLength`.

Ein zusätzliches Token-Limit kann die Ausgabe vor der schließenden JSON-Klammer
abschneiden. Der Server sieht dann ungültiges JSON und verwirft das Ergebnis,
obwohl die Grammatik die fachliche Antwort eigentlich begrenzt hätte. Das ist
bei kleinen Secondary-Modellen besonders wahrscheinlich, wenn sie viele Tokens
bis zum Erreichen einer zulässigen Grammatikalternative erzeugen.

Wenn eine Output-Grenze nötig ist, gehört sie ins Schema. Beispiel für einen
optional zu leerenden Textwert:

```json
{
  "anyOf": [
    { "const": "" },
    { "type": "string", "minLength": 3, "maxLength": 96 }
  ]
}
```

Vor einer neuen Schema-Konstruktion muss ein direkter Smoke-Test gegen den
lokalen llama-server bestätigen, dass er die JSON-Schema-Funktionen in die
gewünschte Grammatik übersetzt. Serverseitige semantische Validierung bleibt
trotz Schema erforderlich.
