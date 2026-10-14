# Roadmap: o que fazer a seguir

Plano de implementação para quem continuar o projeto (pessoa ou agente como Codex/Claude). **Leia antes o
[CLAUDE.md](../CLAUDE.md)**: arquitetura, convenções, como compilar e como testar no emulador pela API.

Cada item traz: objetivo, comportamento, dados, arquivos, API, ferramentas da IA, rosto/animação e critérios de
aceite. A ordem sugerida está no fim.

## Regras gerais (valem para tudo)

- Código, identificadores e comentários em inglês; textos para o usuário em pt-BR.
- Toda configuração nova vai em `AppConfig.kt` com padrão sensato e aparece em `SettingsScreen` e na API (`/api/v1/config`).
- Toda capacidade que a IA possa usar vira uma `Tool` registrada em `AppGraph` (aparece também em `POST /api/v1/tools/{name}`).
- Falas espontâneas usam `Catchphrases.line(categoria, exemplos)` e uma descrição em `PhrasePool.CATEGORIES` (a IA gera variações).
- Animações novas são `Scene` em `face/LifeScenes.kt` (+ props + `Arms.pose`). Nunca mudar a cor do rosto por emoção.
- Room: ao criar tabela, subir `AppDatabase.version` e escrever `Migration` (nunca `fallbackToDestructiveMigration`; tem dados do usuário).
- "Pessoa" = `PersonEntity` (reconhecimento facial). Sempre que algo for "de alguém", guarde `personId` (nullable = casa toda).
- Multi-aparelho: dados compartilhados devem ser propagados pelo `Mesh` (como `shareMemory`/`sharePerson`).
- Documente rotas novas em `docs/API.md`. Rode `./gradlew assembleDebug testDebugUnitTest` e teste no emulador via API + screenshot.

---

## 0. Base comum: "quem está falando/presente"

Vários itens dependem de saber com quem o robô está lidando.

- Crie `core/PresenceTracker.kt` (singleton no `AppGraph`) com `currentPerson: StateFlow<PersonEntity?>` e
  `lastSeen(personId)`. Alimente com: `Greeter.onSighting` (rosto conhecido), reconhecimento de voz (item 13) e
  presença do `Mesh` (outros cômodos).
- `Brain.systemPrompt()`: incluir "Você está falando com {nome}" quando houver e as memórias **dessa pessoa** primeiro.
- `MemoryEntity`: adicionar coluna `personId: Long?` (migração). Ferramentas `remember`/`recall` passam a aceitar `person`.

Aceite: perguntar "do que eu gosto?" retorna memórias da pessoa reconhecida, não de outra.

---

## 1. Respostas em streaming + fala por frase ⭐

Objetivo: começar a falar em ~1–2 s em vez de 5–10 s.

- `LlmClient.stream(cfg, messages, tools): Flow<StreamEvent>` usando `"stream": true` (SSE `data: {...}`), juntando
  `delta.content` e `delta.tool_calls` (os tool calls chegam fragmentados por índice; acumular `arguments`).
  CLIProxyAPI suporta streaming no `/v1/chat/completions`.
- `Brain.askStreaming(...)`: mantém o loop de ferramentas; na rodada final emite texto em pedaços. A tag `[emotion]`
  vem no começo: extraia assim que fechar o `]`.
- `core/SentenceSplitter`: acumula texto e solta frases completas (`.`, `!`, `?`, `…`, quebra de linha; mínimo ~25
  caracteres; não cortar em "Sr.", números "3.5").
- `Speaker`: fila de frases (`Channel<String>`); sintetiza a próxima enquanto toca a atual (pré-busca) para não haver
  silêncio entre frases. OpenAI/Kokoro/ElevenLabs aceitam texto curto; Android TTS idem.
- `AssistantRuntime.chat`: usar o fluxo; o estado `SPEAKING` começa na primeira frase.
- Fallback: se o endpoint não suportar stream (erro), usar `complete()` atual.

Aceite: medir no log o tempo até o primeiro áudio < 2,5 s com `gpt-6.1-sol`; resposta longa sem pausas estranhas.

## 2. Interromper falando (barge-in) sem perder contexto

- Durante `SPEAKING`, manter um detector de voz leve: reaproveitar o loop de áudio do `WakeWordEngine` (ou um
  `AudioRecord` próprio com VAD de energia) **com eco cancelado**: usar `MediaRecorder.AudioSource.VOICE_COMMUNICATION`
  e `AcousticEchoCanceler` quando disponível; exigir fala sustentada > 400 ms e nível acima do próprio TTS.
- Ao detectar: `speaker.stop()`, guardar `InterruptedReply(fullText, spokenUpTo, emotion)` no `Brain`.
- Adicionar ao histórico a resposta **como foi dita até ali** + nota de sistema:
  `"[interrompido pelo usuário depois de: '...'; o resto planejado era: '...']"`. Assim a IA pode retomar
  ("como eu ia dizendo…") se fizer sentido.
- Rosto: reação rápida (olhos abrem, boca fecha, "hm?") e passa para `LISTENING`.

Aceite: falar por cima interrompe em < 0,5 s; perguntar "o que você ia dizer?" faz ele continuar de onde parou.

## 3. Timer visual com ansiedade ⭐

- Dados: `TimerEntity(id, label, endsAt, durationMs, personId?, room?, state)`; tabela nova (ou em memória + persistir).
- Ferramentas: `start_timer(label, minutes|seconds)`, `list_timers`, `cancel_timer`. API `GET/POST/DELETE /api/v1/timers`.
- Agendar com `AlarmManager` (como lembretes) para tocar mesmo com o app em segundo plano.
- Rosto: `face/TimerOverlay` desenhado no canto (ou ao lado do rosto): anel/contador grande estilo 7 segmentos
  (reaproveitar `drawDigitalClock`) com o rótulo ("arroz").
  - Ele **olha para o timer** de tempos em tempos (gaze para o canto) — mais vezes conforme o fim se aproxima.
  - Últimos 60 s: sobrancelhas preocupadas, roer "unhas" (mão na boca), batidinha de pé (bounce), suor.
  - Últimos 10 s: contagem em voz baixa/no balão ("10… 9…") e olhos arregalados.
  - Fim: cena `TIMER_DONE` (pula, aponta para o timer, alarme), fala "o {label} tá pronto!" no cômodo onde a
    pessoa estiver (`Mesh.sayInRoom` com `PresenceTracker`).
- Vários timers: mostrar o mais próximo, com os outros pequenos.

Aceite: "timer de 2 minutos pro chá" mostra o contador, ele fica ansioso no fim e avisa no cômodo certo.

## 4. Agenda interna ⭐

- Dados: `EventEntity(id, title, start, end?, allDay, location, notes, personId?, remindMinutesBefore, recurrence)`.
  Recorrência simples (`none|daily|weekly|monthly|yearly`) ou RRULE básica.
- Ferramentas: `add_event`, `list_events(from,to,person?)`, `update_event`, `delete_event`, `whats_next`.
  A IA resolve datas relativas ("sexta que vem às 15h"); passe a data atual no prompt (já existe).
- Lembrete automático X min antes, usando o sistema de lembretes do item 8 (com animação e pessoa).
- Integra com o resumo da manhã ("hoje você tem dentista às 15h") e com o `Greeter` (bordão "você tem 2 compromissos hoje").
- Tela: aba "Agenda" no app companheiro (item 7) e no painel web; no robô, visão do dia em overlay por comando ("mostra minha agenda").
- API: `GET/POST/PATCH/DELETE /api/v1/events`. Exportar `.ics` (opcional) para assinar no Google Agenda.

Aceite: marcar, listar, editar e ser lembrado por voz; eventos por pessoa.

## 5. Recados por pessoa ⭐

- Dados: `MessageEntity(id, toPersonId?, toRoom?, fromPersonId?, fromName, text, createdAt, deliverAfter?, deliveredAt?, channel)`.
- Ferramenta: `leave_message(to, text, when?)` ("quando a Ana chegar, avisa que o jantar tá no forno").
- Entrega: quando `PresenceTracker`/`Greeter` reconhecer a pessoa (em qualquer aparelho do mesh), o robô a chama
  pelo nome, mostra um envelope (cena `MESSAGE_DELIVERY`: segura uma cartinha com as mãos) e fala o recado.
  Marca `deliveredAt` e sincroniza pelo mesh para não entregar duas vezes. Opcional: confirmar ("entendi").
- Também recebidos pelo app companheiro (item 7): mensagem para "Ana" fica pendente até ela aparecer.

Aceite: recado deixado na sala é entregue na cozinha quando a Ana é reconhecida lá, uma única vez.

## 6. Interfone entre cômodos

- Já existe `say_in_room`. Acrescentar:
  - Ferramenta `broadcast(text)`: fala em todos os aparelhos ("chama o pessoal pra jantar").
  - `call_room(room)`: abre um canal de voz entre dois aparelhos (push-to-talk ou conversa). Implementação simples:
    áudio PCM 16 kHz por WebSocket (`/api/v1/mesh/audio`) com o segredo do mesh; ou WebRTC (lib
    `io.getstream:stream-webrtc-android`) para full-duplex.
  - Rosto: cena `INTERCOM` (fone de telefone, ondas sonoras) nos dois lados; o lado que recebe mostra quem chama.
- Respeitar "não perturbe" por cômodo (config `device.doNotDisturb` + horário).

Aceite: "fala com o quarto" abre o canal e as duas pessoas conversam.

## 7. App companheiro (nosso "Telegram") ⭐

Objetivo: conversar com o robô de qualquer lugar, ver/editar listas, agenda, recados, lembretes e configurações.

- Novo módulo Android `:companion` (Compose, mesmo tema neon). Reaproveitar `config`, modelos `@Serializable`.
- Pareamento por QR (mesmo fluxo do mesh, com papel `companion`): o robô gera um token de dispositivo
  (`DeviceToken(id, name, personId, scopes)`), não o token mestre. Guardar tokens em tabela; revogáveis na tela.
- Conectividade fora de casa: recomendar **Tailscale** (sem servidor próprio, sem abrir porta). Alternativas: relay
  WebSocket próprio ou ntfy auto-hospedado para push. Não expor a porta na internet.
- Tempo real: endpoint `GET /api/v1/ws` (WebSocket, Ktor `websockets`) para chat e eventos (já existe SSE em `/events`).
- Telas:
  1. **Chat** com o robô (texto e áudio). Mensagens do robô também chegam aqui (ex.: "o timer acabou").
     Comandos: "fala na sala: …" usa `say_in_room`.
  2. **Listas** (compras, tarefas, personalizadas): `ListEntity` + `ListItemEntity(text, done, qty, addedBy, personId?)`.
     Ferramentas da IA: `list_add`, `list_remove`, `list_check`, `list_show`. Por voz: "coloca leite na lista de compras".
  3. **Agenda** (item 4), **Recados** (item 5), **Lembretes** (item 8), **Timers**.
  4. **Rosto ao vivo** (opcional): renderizar o mesmo `RobotFace` a partir do estado via WebSocket.
  5. **Configurações**: as mesmas da tela do robô, via `/api/v1/config`.
- Notificações: serviço em primeiro plano opcional mantendo o WebSocket (sem Firebase, que exigiria segredos no repo).
- O painel web (`api/WebPanel.kt`) pode virar uma PWA com as mesmas telas como alternativa leve.

Aceite: de fora de casa (via Tailscale), mandar "avisa que vou atrasar" faz o robô falar em casa; listas sincronizadas.

## 8. Lembretes personalizados com animação e pessoa ⭐

Evoluir `ReminderEntity`:

- Novas colunas: `type` (`generic|medicine|water|food|exercise|custom`), `personId?`, `requireConfirm`,
  `nagEveryMinutes`, `escalateToPersonId?`, `escalateAfterMinutes`, `animation` (chave de cena), `lastAckAt`.
- Entrega **para a pessoa**: tocar no aparelho do cômodo onde ela está (`PresenceTracker`/`Mesh`); se a pessoa não
  for vista, tocar em todos ou esperar até ela aparecer (configurável).
- Confirmação: "já tomei", toque no rosto, botão no app companheiro. Sem confirmação → insiste a cada N min;
  depois avisa outra pessoa (cuidador) pelo app.
- Animações (cenas novas): `DRINK_WATER` (copo d'água, goles, "ahh"), `TAKE_PILL` (comprimido + copo),
  `EAT_FOOD` (prato/talher), `STRETCH_BREAK` (já existe `STRETCH`). O robô faz junto com você.
- Ferramenta: `create_reminder` ganha `type`, `person`, `confirm`, `repeat`. Ex.: "me lembra de tomar o remédio da
  pressão às 8h todo dia e confirma comigo".
- Histórico de adesão (tomou/não tomou) consultável: "tomei o remédio hoje?".

Aceite: lembrete de remédio para "Diogo" toca onde o Diogo está, com a animação, insiste até confirmar e registra.

## 9. Relatório de sono

- Sinais disponíveis: última interação/rosto visto à noite, hora em que a casa fica quieta (nível de áudio baixo e
  sem movimento na câmera), primeira detecção de manhã, aparelho do quarto (cômodo "Quarto").
- `SleepTracker`: estima `bedtime`/`wakeTime` por pessoa (rosto visto por último/primeiro) ou da casa; salva
  `SleepEntity(date, personId?, bedtime, wakeTime, interruptions)`.
- Resumo de manhã ("você dormiu umas 7 horas") e semanal no app; ferramenta `sleep_report(person?, days)`.
- Deixar claro que é estimativa (sem sensores médicos) e permitir desligar.

## 10. Quizzes e jogos

- Ferramenta/modo `start_quiz(topic?, difficulty?)`: a IA gera perguntas (JSON com alternativas e resposta) em lote;
  o robô pergunta por voz, mostra as alternativas no balão (A/B/C/D), ouve a resposta, comemora (`JOY`) ou fica
  triste de brincadeira, mantém placar por pessoa (`QuizScoreEntity`).
- Outros: adivinhe o número, "verdade ou mentira", charadas, "pedra, papel e tesoura" com a câmera (ML Kit pose/hands
  para o gesto da mão).
- Cena `QUIZ_HOST` (microfone de apresentador, confetes no acerto).

## 11. Spotify

- Opção A (recomendada): **Spotify Web API** com OAuth PKCE (sem client secret no app). Usuário cria o próprio
  Client ID no painel do Spotify e informa em Configurações (nada fica no repositório). Scopes:
  `user-read-playback-state user-modify-playback-state user-read-currently-playing`.
  Ferramentas: `music_play(query|playlist)`, `music_pause`, `music_next`, `music_volume`, `music_now_playing`.
  Toca no aparelho escolhido (Spotify Connect: celular, caixa de som, PC).
- Opção B: Spotify App Remote SDK (controla o app instalado no mesmo celular).
- Quando estiver tocando, o robô já dança (`Interactions` detecta `isMusicActive`); mostrar nome da música no balão.

## 12. Memória automática

- Após cada conversa (ou a cada N turnos), em segundo plano: chamada à IA com o trecho recente pedindo JSON
  `[{fact, person, category, confidence}]` só com fatos duráveis (gostos, datas, nomes, rotinas, saúde que a pessoa
  contou), excluindo o que é passageiro.
- Deduplicar contra memórias existentes (busca + similaridade de texto; ou embeddings) e atualizar em vez de duplicar.
- Guardar com `personId` e `source = "auto"`; tela/rota para revisar e apagar (`/api/v1/memories?source=auto`).
- Respeitar privacidade: opção para desligar e nunca guardar senhas/documentos.

## 13. Reconhecimento de quem fala (voz)

- Modelo de embedding de locutor on-device: **sherpa-onnx** (`com.k2fsa.sherpa.onnx`, AAR via releases do GitHub)
  com modelo 3D-Speaker/WeSpeaker (~25 MB, baixado na hora como o Vosk). Alternativa: TFLite equivalente.
- Precisa do áudio bruto da fala: hoje o STT do Android não entrega o áudio. Caminho: usar o gravador próprio com VAD
  (já existe em `Listener.recordUtterance`) e mandar o áudio para STT (Whisper API, ou STT local sherpa-onnx), ou
  no Android 13+ usar `RecognizerIntent.EXTRA_AUDIO_SOURCE` alimentando o mesmo áudio.
- Cadastro: na tela de Pessoas, "gravar voz" (3 frases de ~4 s) junto do rosto. Identificação por similaridade de
  cosseno com limiar configurável; combinar com o rosto quando os dois existirem.
- Resultado alimenta `PresenceTracker.currentPerson` (item 0).

## 14. Módulo futuro: avisos da casa / câmera IP

- `POST /api/v1/alerts {source, kind, text, imageUrl?, room?}` para campainha, sensores, Frigate/Home Assistant.
- O robô avisa no cômodo onde há gente; pode mostrar a imagem (snapshot) num quadro ao lado do rosto.
- Câmera IP (RTSP) via Frigate ou go2rtc fora do app; o app só recebe eventos. Integração com Home Assistant nativa
  (listar entidades e controlar por voz) entra aqui também.

---

## Ordem sugerida

1. Base de presença (0) → streaming (1) → barge-in (2)
2. Timer visual (3) → lembretes com pessoa e animação (8) → agenda (4)
3. Recados (5) → interfone (6)
4. App companheiro (7) com chat e listas
5. Memória automática (12) → voz (13)
6. Quizzes (10), Spotify (11), sono (9)
7. Módulo de casa (14)

## Estado atual (para contexto)

Já funciona: rosto com física de molas, 5 estilos de olho, sobrancelhas, braços, ~40 cenas de vida/reação/clima,
relógio, balão com fonte retrô, frases geradas pela IA, palavra de ativação offline (Vosk), STT/TTS plugáveis,
CLIProxyAPI (ChatGPT OAuth), ferramentas (web, clima, memória, lembretes, dispositivo, webhooks, mesh),
reconhecimento facial com cumprimentos, mesh por QR, API local + painel web.

Pendente de validar em aparelho real: mesh com 2+ celulares, reconhecimento facial, detecção de aceno e música
ambiente, sensores (sacudir/carregador), imitação de objetos pela câmera.
