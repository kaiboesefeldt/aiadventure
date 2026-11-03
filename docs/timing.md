# Turn timing

This conceptual sketch explores how work can overlap with the player's reading and typing to reduce the time to first token (TTFT) after a turn is submitted. The table and sequence diagram describe the original design ideas, including vector-store hint retrieval and reranking; they are not a specification of the current implementation or measured timings.

## Timing overview

Time progresses down the table. The typing phase is a potential window for background work, but its duration is unknown. Work remaining after submission directly affects TTFT.

| Phase | User | Primary LLM | Secondary LLM | Other | Comment |
| --- | --- | --- | --- | --- | --- |
| … | | | | | |
| LLM text generation | Read streamed LLM output | Generate answer to previous user response | | | Generation must be at least as fast as the user's reading speed. |
| User typing text | Typing | | | Get hint from vector store | This is the “green area” for work without perceived performance impact, although its duration is unknown. |
| User typing text | Typing | Check whether hint is applicable | | | |
| User typing text | Typing | Cache hint text, if applicable | | | |
| User typing text | Typing | Incrementally cache partial user text | | | |
| User sending text | | Cache remaining user text | | | Everything in this phase is critical for TTFT. |
| LLM text generation | Read streamed LLM output | Generate answer to previous user response | | | Generation must be at least as fast as the user's reading speed. |
| … | | | | | |

## Sequence diagram

The diagram sketches initialization, the first turn, and subsequent turns with hint retrieval, reranking, incremental caching, and streamed responses.

```plantuml
@startuml
'https://plantuml.com/sequence-diagram

autonumber

actor User as u
participant Controller as c
participant "Primary LLM" as pLLM
participant "Vector Store" as v

== Initialization ==

c -> pLLM: System Prompt (Global)
activate pLLM
activate c
c -> pLLM: System Prompt (Game Instructions)
c -> pLLM: Initial Game Prompt
pLLM --> c: Initial Scene Description
deactivate pLLM

== Game ==

group First User Input (Game Loop 0)
loop User is typing and has not yet sent the message
u -> c: typing
activate u
c -> pLLM: caching(user input text so far)
end

u -> c: finish turn: //tp_0// = full initial turn prompt text
deactivate u

c -> pLLM: //tp_0//
activate pLLM
loop Receive reply tokens
pLLM --> c: turn reply token
end
pLLM --> c: //tr_0// = full turn reply text
deactivate pLLM

end

group Game Loop //n// in 1..∞

loop User is typing and has not yet sent the message
u -> c: typing
activate u

group Hint Retrieval
c -> v: retrieve(//tp_(n-1)+tr(n-1)//)
activate v
v --> c: Hint //h_n//
deactivate v
end
u -> c: typing

group Hint Re-Ranking
c -> pLLM: rerank(//h_n.key//)
activate pLLM
pLLM --> c: //hint_score_n//
deactivate pLLM
end
u -> c: typing

opt //hint_score_n < 5//
c -> c: //h_n = null// (discard hint)
end

loop User continues to type
u -> c: typing
c -> pLLM: caching(//h_n.fraction// + user input text so far)
end
end

u -> c: finish turn: //tp_n// = full turn prompt text
deactivate u

c -> pLLM: //h_n.fraction + tp_n//
activate pLLM
loop Receive reply tokens
pLLM --> c: turn reply token
end
pLLM --> c: //tr_n// = full turn reply text
deactivate pLLM

end

@enduml
```
