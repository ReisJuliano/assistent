# Falar enquanto pensa (streaming) e ser interrompido falando (barge-in)

Plano para quem for implementar (pessoa ou agente como o Codex). **Leia antes o [CLAUDE.md](../CLAUDE.md)**
(arquitetura, convenções, como testar no emulador pela API) e as regras gerais do [ROADMAP.md](ROADMAP.md).

## Estado (2026-10-14)

Implementado e medido no emulador: fila com síntese adiantada (`SpeechPipeline`, buracos de 0–4 ms em 25 frases),
`heardText()` pela posição real de reprodução, cancelamento do stream da IA no `/stop`, continuação "o que você ia
falar?" a partir do ponto ouvido (inclusive quando a geração já tinha terminado), interrupção `name` por gramática
Vosk com eco cancelado que ignora as palavras que o próprio robô está falando. Primeiro áudio ~2–2,5 s após o
primeiro token (o primeiro pedaço agora sai na primeira vírgula depois de 24 caracteres).
Falta: medir a interrupção por voz real num celular (o emulador usa o microfone do PC) e o modo `voice` com rejeição
de eco por correlação.

## Objetivo

1. **Responder enquanto escreve:** a primeira frase da resposta começa a ser falada assim que o modelo a termina,
   enquanto o resto ainda está sendo gerado. Sem buracos entre as frases.
2. **Ser interrompido falando:** se a pessoa fala por cima ("Nexo, para", "espera", ou só começa a falar), o robô
   cala na hora, presta atenção e responde ao que foi dito. Ele **nunca** pode se interromper com a própria voz,
   com música ou com a TV.

As falas de espera que o dono escreveu (`AssistantRuntime.WAITING_LINES`, categoria `thinking_wait`) continuam
existindo. Com streaming elas vão aparecer menos, e isso está ok: só entram quando o primeiro áudio da resposta
demorar.

## O que já existe (e o que está errado)

| Peça | Onde | Situação |
|---|---|---|
| Streaming do LLM | `LlmClient.stream`, `ResponsesClient.stream` (provedor `chatgpt`), `Brain.askStreaming` + `EmotionGate` (tira o `[emoção]` do começo) | Funciona nos dois provedores. Manter. |
| Corte em frases | `core/SentenceSplitter.kt` (mín. 25 caracteres, só fim de frase) | Primeira fala demora: espera um ponto final. |
| Falar durante o stream | `AssistantRuntime.chat()`: `speaker.speak(sentence)` **dentro** do callback `onText` | **Serial:** enquanto sintetiza e toca a frase 1, o stream do LLM fica parado (backpressure no callback). A frase 2 só começa a ser sintetizada quando a 1 acaba de tocar, o que gera buracos de 0,3 a 1 s com a voz do Android. |
| Falas de espera | `AssistantRuntime.setActivity` (`waitingLineJob`) | Seguram o `Speaker` (mutex) até terminar. A resposta espera. Ok, mas não há regra de "no máximo 1 por resposta". |
| Interrupção | `voice/BargeInDetector.kt` (energia em `VOICE_COMMUNICATION` + AEC, 450 ms acima do limiar) e `startBargeIn()` | Só energia. Com o alto-falante alto e o AEC fraco (comum em celular barato), a própria voz ou música dispara. As primeiras palavras da pessoa se perdem: o detector "gasta" o áudio e o reconhecimento começa depois. |
| Parar | `interruptSpeaking()` (+ `POST /api/v1/stop`) | Para o player, mas o **stream do LLM continua** e ferramentas ainda podem rodar. `interruptRequested` só é checado entre frases. |
| Contexto da interrupção | `Brain.noteInterrupted` | Recebe `spokenReply` = frases *mandadas* para o TTS, não as *ouvidas*. Tem texto com encoding quebrado (`usuÃ¡rio`): corrigir para `usuário`. |
| Escuta depois | loop `converse()` | Depois de uma interrupção, só volta a ouvir se `wake.followUpSeconds > 0` e não houver música. Precisa ouvir sempre, uma vez. |

## Desenho alvo

### 1. `SpeechPipeline` (novo, `core/SpeechPipeline.kt`)

Produtor/consumidor com três etapas independentes:

```
LLM stream ──► splitter ──► Channel<String> ──► sintetizador (até 2 frases adiantadas) ──► player contínuo
                                                                                              │
                                                     heardText() ◄── posição de reprodução ───┘
```

- `submit(text)`: não bloqueia o stream do LLM (o callback `onText` só faz `send`).
- **Sintetizar adiantado:** separar `Speaker` em `synthesize(text): AudioClip` (PCM + sample rate) e tocar. Para a voz
  do Android: `synthesizeToFile` por frase. OpenAI/ElevenLabs: HTTP com o áudio vindo em streaming. O fallback para a
  voz do sistema continua.
- **Player contínuo:** um `AudioTrack` aberto durante toda a resposta (`PcmPlayer`), recebendo os clips em sequência.
  Precisam continuar funcionando: lip-sync (`player.level`), voz robótica (`RobotVoice`, aplicada por clip, com estado
  contínuo) e a "boca" do rosto.
- `heardText()`: o texto efetivamente tocado (frases completas + a fração da frase atual pela posição de reprodução).
- `cancel()`: para o som em menos de 150 ms, esvazia as filas e devolve `heardText()`.
- Eventos SSE: `speech_start`, `speech_end`, `interrupted` (com o texto ouvido). Log de latência:
  `first_token_ms`, `first_audio_ms`.

### 2. Splitter mais esperto

- **Primeiro pedaço:** pode sair em fronteira de oração (`,` `;` `:` ` — `) a partir de ~40 caracteres, para começar a
  falar cedo.
- **Demais pedaços:** fim de frase (regras atuais de abreviação e decimais).
- Pedaço com mais de ~220 caracteres sem pontuação é cortado no último espaço.
- Nunca cortar dentro de números, horas ("7:30"), siglas ou URLs (a IA não deve falar URL, mas o modelo erra).

### 3. Runtime

- `chat()` liga o stream ao pipeline e o rosto vai para `SPEAKING` **quando o primeiro áudio toca**, não quando chega
  o primeiro texto.
- **Falas de espera:**
  - só começam se não houver áudio em 1,7 s (`THINKING`) ou 1,3 s (`SEARCHING`);
  - no máximo 1 por resposta, mais 1 extra se uma ferramenta demorar mais de 6 s;
  - se a primeira frase da resposta ficar pronta antes, a fala de espera pendente é cancelada;
  - se ela já estiver tocando, termina e a resposta entra logo depois, sem sobreposição.
- Texto que o modelo escreve *antes* de chamar uma ferramenta ("deixa eu pesquisar…") pode ser falado. É natural.

### 4. Interrupção

Nova configuração `voice.bargeIn` (aba Voz), com 3 modos:

| Modo | Como detecta | Robustez |
|---|---|---|
| `off` | — | — |
| `name` (**padrão**) | Vosk rodando no microfone durante a fala, com gramática = frases de ativação + palavras de parar ("para", "pera", "espera", "chega", "silêncio", "{nome} para") | Muito robusto: a própria voz quase nunca contém o nome e uma ordem. **Ignorar** o reconhecimento se a frase que está tocando agora contém a mesma palavra. |
| `voice` | VAD + YAMNet "speech" (já existe em `SoundEars`) + rejeição de eco: correlacionar o envelope do microfone com o `player.level` (atraso de 50 a 300 ms). Só interrompe com ≥ 400 ms de fala **não correlacionada** com a saída. | Mais natural, mais sujeito a erro. Desligar sozinho com música tocando (`Interactions.musicPlaying`). |

Ao interromper:

1. `pipeline.cancel()` imediato. **Cancelar a coroutine do `askStreaming`** (o stream do LLM para).
   - Ferramenta já em execução termina, mas não se fala o resultado.
   - Nenhuma ferramenta nova roda depois do cancelamento.
   - O `Brain.lock` precisa ser liberado no cancelamento (teste isso).
2. `brain.noteInterrupted(textoCompleto, pipeline.heardText(), emoção)`. A memória "o que você ia falar?" já usa isso.
3. Rosto: `SURPRISED` rápido e depois `LISTENING`.
4. O que a pessoa falou:
   - **só uma palavra de parar:** fica quieto (IDLE);
   - **nome:** escuta o comando;
   - **modo `voice`:** guardar os últimos ~2 s do microfone num ring buffer. Com STT Whisper (`stt.provider = openai`),
     transcrever a partir desse buffer para não perder o começo. Com o reconhecedor do Android isso não é possível:
     começar a escutar na hora. Aceitar que a pessoa às vezes repete, e documentar isso.
5. Depois de uma interrupção **sempre** escutar uma vez, mesmo com `followUpSeconds = 0` (mas não com música).

Microfone: durante a fala, o loop do wake word **não** está rodando (o serviço está dentro de `converse`). Então quem
abre o microfone é o detector de interrupção, com `VOICE_COMMUNICATION` + `AcousticEchoCanceler` + `NoiseSuppressor`
quando disponíveis. Fechar sempre no `finally`.

### 5. Configuração, API e UI

- `AppConfig`:
  - `voice.bargeIn = "name"`, `voice.bargeInStopWords` (lista editável);
  - `tts.prefetch = 2`;
  - `stt.keepOnsetMs = 2000`.
- Aba **Voz**: segmentado "Interromper: Desligado / Pelo nome / Qualquer voz" + dica explicando.
- API:
  - `POST /api/v1/stop` continua;
  - novo `GET /api/v1/speech` (o que está tocando, a fila, texto ouvido);
  - eventos SSE novos documentados em `docs/API.md`.

## Critérios de aceite

- [ ] Primeiro áudio ≤ 1,5 s depois do primeiro token do LLM (voz do Android no emulador), medido no log de latência.
- [ ] Nenhum buraco > 300 ms entre frases de uma resposta longa ("me explica como funciona um motor a combustão").
- [ ] `POST /api/v1/stop` no meio de uma resposta:
  - [ ] silêncio em < 200 ms;
  - [ ] o stream do LLM é cancelado (log), sem ferramenta nova;
  - [ ] "o que você ia falar?" continua do ponto ouvido.
- [ ] Modo `name`: 20 respostas longas seguidas sem nenhuma autointerrupção; "Nexo, para" para em ≥ 9 de 10 tentativas.
- [ ] Com música tocando no ambiente: zero interrupções falsas no modo `name`.
- [ ] Falas de espera: só aparecem quando a primeira frase demora; nunca sobrepõem a resposta; no máximo 1 por resposta
      (+1 com ferramenta lenta).
- [ ] Voz robótica, lip-sync e legendas continuam funcionando com o pipeline.
- [ ] Testes de unidade:
  - [ ] splitter (primeiro pedaço por vírgula, sem cortar "7:30", "Sr.", "3.5");
  - [ ] `SpeechPipeline` com sintetizador falso: ordem, prefetch, `cancel()` devolve o texto ouvido;
  - [ ] função de correlação de eco (sinal igual atrasado = eco; fala diferente = não eco).
- [ ] `./gradlew assembleDebug testDebugUnitTest` verde, teste no emulador via API + screenshot, `CLAUDE.md` e
      `docs/API.md` atualizados.

## Armadilhas conhecidas

- `Speaker.speak` usa um mutex. Com o pipeline, só o pipeline deve tocar a resposta. Falas espontâneas (balões,
  lembretes) entram na mesma fila **entre** respostas, nunca no meio.
- `PcmPlayer.effect` cria um efeito por fala. Com o player contínuo, manter o estado do `RobotVoice` entre os clips
  da mesma resposta (senão dá estalo).
- A voz do Android toca no stream de mídia, e `Interactions` ignora "música no celular" enquanto ele fala
  (`lastSpoke`). Não quebrar isso.
- `noteInterrupted` adiciona uma mensagem `system` no meio do histórico. O adaptador do provedor `chatgpt`
  (`ResponsesClient.buildBody`) converte em mensagem `developer`. Ok, mas não duplicar a cada interrupção.
- Emulador: o microfone é o do PC. Para testar a autointerrupção, use respostas longas com o volume alto e não fale
  nada.
