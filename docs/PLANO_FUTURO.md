# Plano Futuro — visão, voz ao vivo, memória e vida interna

Plano de desenvolvimento de 8 recursos novos. Complementa [ROADMAP.md](ROADMAP.md) (itens antigos) e
[CLAUDE.md](../CLAUDE.md) (arquitetura e convenções — **leia antes**). Todo item segue as regras gerais do
roadmap: código/identificadores em inglês, textos em pt-BR, config em `AppConfig.kt` + `SettingsScreen`,
capacidades viram `Tool` em `AppGraph`, rotas novas documentadas em `docs/API.md`, testes no emulador via API.

Estado do código verificado em 09/11/2026: Room versão 12, `ConfigStore.CURRENT_VERSION = 8`,
`ChatMessage.imageBase64` já existe (visão single-image no `LlmClient`), `AssistantRuntime.cameraFrameSource`
já entrega frame JPEG da câmera, `AssistantRuntime.chat` já anexa imagem quando bate `VISUAL_KEYWORDS`.

---

## 0. Base compartilhada (implementar antes/ junto)

### 0.1 Desenho de bitmap no rosto (G1) — pré-requisito de "Sonhos desenhados"

Hoje `RobotFace` desenha só `Path` vetorial. Para mostrar uma imagem (sonho, foto de alerta, resultado de
visão) é preciso um canal de bitmap.

- `core/AssistantState.kt`: campo novo `faceImage: ImageBitmap? = null` (+ `faceImageAlpha: Float = 0f`).
  Nunca colocar bitmap grande aqui sem decodificar previamente em tamanho alvo (ver 7).
- `face/RobotFace.kt`: em `drawFace`, depois de `drawSceneProps`, se `state.faceImage != null`, desenhar com
  `DrawScope.drawImage` dentro da rotação do tilt (o mesmo `rotate` dos props), com moldura simples de linhas
  (`stroke`) no estilo neon e fade controlado por `faceImageAlpha`.
- `AssistantRuntime`: helpers `showFaceImage(bitmap: ImageBitmap?, fadeMs: Long = 600)` e
  `clearFaceImage()`. Nada de bitmaps maiores que 1024 px: decodificar com `BitmapFactory.Options.inSampleSize`
  e converter com `asImageBitmap()`.
- Custo baixo, beneficia sonhos (7), alertas com imagem (`AlertEntity.imageUrl` já existe) e futuro preview de
  `look`.

### 0.2 Config de IA do Google (G2) — pré-requisito de `look`, Live, embeddings

Um único grupo de config para chave/modelos do Google, reaproveitado por 3 recursos:

- `config/AppConfig.kt`: grupo novo
  ```kotlin
  @Serializable
  data class GoogleConfig(
      /** Gemini API key (free tier). Usada por visão, embeddings e modo Live. */
      val apiKey: String = "",
      /** Modelo de visão pelo endpoint OpenAI-compat do Gemini. */
      val visionModel: String = "gemini-2.5-flash",
      /** Modelo de embeddings. */
      val embeddingModel: String = "text-embedding-004",
      /** Modelo do modo Live (áudio nativo). */
      val liveModel: String = "gemini-2.5-flash-native-audio",
  )
  ```
  Campo `google: GoogleConfig = GoogleConfig()` no `AppConfig` raiz. Chave fica em config, mas NUNCA sai no
  mesh: adicionar `"google"` ao conjunto excluído em `Mesh.shared` (`core/Mesh.kt:283`) — cada aparelho tem a
  própria (ou nenhuma; recursos que a usam degradam graciosamente, ver item 1).
- `SettingsScreen`: seção "Google IA" em **Cérebro** (`Field` secret para a chave, `Field` para modelos).
- Comportamento sem chave: `look` usa o LLM principal (já funciona hoje), Live desabilitado, embeddings caem
  para busca por palavra-chave (estados explicados nas seções de cada recurso).

### 0.3 Padrão de trabalho "no ritmo do repositório"

Para cada recurso: uma entidade/tabela nova = `AppDatabase.version + 1` com `Migration` dedicada (nunca
agregar duas features na mesma migração); ferramenta nova registrada em `AppGraph`; fala espontânea =
categoria em `PhrasePool.CATEGORIES` + `Catchphrases.line(...)`; evento novo = `EventDispatcher.emit` +
documentar; teste unitário JVM para toda matemática/JSON (mistura de buscas, RPC, decaimento de humor,
agregação do resumo); rota nova documentada em `docs/API.md` no formato de tabelas existente.

---

## 1. Ferramenta `look` — ver de verdade com IA de visão

### Objetivo

Responder perguntas sobre o que a câmera vê com descrição rica (VLM), formalizando o que hoje só acontece
com palavras-chave. Funciona com o cérebro principal (se multimodal) ou com a chave Gemini grátis.

### Como funciona

- "Nexo, o que é isso?" / "olha aqui" / `look` via tool-calling → captura o frame atual
  (`runtime.cameraFrameSource`), reduz para ~768 px JPEG (~100 KB) e envia pergunta + imagem a um VLM.
- A Tool devolve a descrição como texto ao modelo, que responde com a voz/emoção dele — o `look` é só
  "olhos", a fala continua sendo do cérebro.
- Se a imagem estiver escura demais (já existe checagem de `cameraBrightness < 28f`), devolver
  `"LOW_LIGHT"` e o modelo pede luz (reaproveita `requestLightAssist()`).

### Design técnico

- **Arquivo novo** `tools/LookTool.kt`:
  ```kotlin
  class LookTool(runtime: AssistantRuntime, config: ConfigStore, llm: LlmClient) : Tool {
      override val name = "look"
      override val description = "Describe or read what the camera currently sees. Use for " +
          "'o que é isso', ler texto/etiqueta, descrever pessoas, objetos ou a sala."
      override val activity = Activity.SEARCHING
      override val parameters = params {
          string("question", "What to look for or describe; empty = full description", required = false)
      }
  }
  ```
  `execute`: pega frame → base64 → se `google.apiKey` configurada, chama
  `https://generativelanguage.googleapis.com/v1beta/openai/chat/completions` com `ChatMessage(role="user",
  content=pergunta, imageBase64=frame)` (o `LlmClient` já serializa content parts); senão, usa o
  `LlmConfig` principal se o modelo suportar visão. Timeout curto (8 s) e falha vira texto de erro honesto
  para o modelo (nunca sucesso fabricado — regra do `Operations`).
- **`AppGraph`**: registrar `LookTool` em `tools` e expor `runtime.cameraFrameSource` (já conectado).
- **`AssistantRuntime.chat`**: manter o atalho por `VISUAL_KEYWORDS`, mas delegar à mesma função de captura
  (extrair `frameBase64()` para não duplicar redução/encoding).
- **Extra de acessibilidade**: config `google.autoDescribe` (default off) — a cada N minutos com rosto
  desconhecido na frente, o robô descreve a cena num balão (reaproveita `Greeter`).
- **API**: `POST /api/v1/look {question}` → `{description}` (mesma lógica, sem LLM de fala).
- **Evento**: `look_used` com pergunta/resposta truncadas (útil para o diário, item 4).
- **Custo**: free tier do Gemini (visão) ou 0 (cérebro principal). Nada é gravado — frame em memória.

### Critérios de aceite

- "o que você está vendo?" descreve a sala com detalhes que o EfficientDet não dá (cores, texto, marca);
- "que remédio é esse?" lê a caixa (OCR pelo VLM) sem chave extra;
- sem `google.apiKey` e com cérebro multimodal, continua funcionando igual a hoje;
- sem câmera (permissão negada), a tool retorna erro claro e o robo pede luz/permissão, nunca inventa.

---

## 2. Modo "Live" — conversa full-duplex por streaming de áudio

### Objetivo

Uma conversa contínua e natural (sem wake word por turno, com interrupção instantânea e latência de
interlocutor real) usando a **Gemini Live API** no free tier. É um *modo* alternativo, não substitui o
cérebro padrão.

### Como funciona

- Usuário diz "modo ao vivo" (ou painel/API): o robô entra em `Activity.LISTENING` contínuo; a sessão
  dura até "sair do modo", silêncio longo configurável ou perda de rede.
- Durante a sessão, o áudio do microfone vai ao Gemini por WebSocket; o áudio de resposta toca direto no
  `PcmPlayer` (passando pelo `RobotVoice` se `tts.robotVoice` ativo); **function calling do Live é
  mapeado para o `ToolRegistry`**, então o robô continua ligando a luz, criando lembretes e tocando música
  dentro do modo Live.
- O rosto acompanha: boca pelo nível do áudio de saída (mesmo caminho do `PcmPlayer.level`), emoção pela
  tag textual que o modelo manda junto (pedir no system instruction do Live: "comece frases com [emotion]").

### Design técnico

- **Arquivos novos** `live/LiveSession.kt` e `live/LiveManager.kt`:
  - `LiveSession`: OkHttp WebSocket contra
    `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguagemodel.v1beta.GenerativeService.BidiGenerateContent?key=…`.
    Mensagens JSON: `setup` (modelo, `responseModalities: [AUDIO]`, voz, `systemInstruction` reaproveitando
    o `Brain.personaPrompt()` e o catálogo de tools em formato `functionDeclarations`), `realtimeInput`
    (chunks PCM 16 kHz mono, base64) e recebimento de `serverContent.modelTurn` (áudio 24 kHz →
    `PcmPlayer`) + `toolCall` (nome/args) + `turnComplete`. **Verificar o shape exato da mensagem na doc
    oficial no dia da implementação** (a API muda versão com frequência).
  - `LiveManager`: máquina de estados (`Idle → Connecting → Live → Closing`), dono do `AudioRecord`
    compartilhado — **reaproveitar o loop do `WakeWordEngine`**: adicionar um `onPcm` extra em vez de abrir
    um segundo microfone (mesmo padrão do feed do `SoundEars`).
- **Integração no `AssistantService`**: `LiveManager.mode: StateFlow<LiveState>`; o `voiceLoop` checa antes
  de processar wake phrase — em `Live`, o comando "sair do modo ao vivo" encerra; wake word comum fica
  desligada durante a sessão (o Gemini cuida do turn-taking e do barge-in nativo).
- **Ferramentas**: `LiveManager` cria `ToolSpec`-like `functionDeclarations` a partir de
  `ToolRegistry.specs()` (excluir tools cujos efeitos colidem com o modo, ex.: `tv_control` com confirmação
  visual complexa pode continuar — nada impede). Execução idêntica ao `Brain`: `tools.run(tool, args)` →
  resposta volta como `toolResponse` na sessão. Operações aparecem nos chips (`Operations.track`) de graça.
- **Rosto**: cena opcional `LIVE` (`Scene.LIVE("live", 3600f, Kind.AWAKE, "Modo ao vivo")` com olhos
  levemente maiores e brilho pulsante no BPM da voz); `Life.frame` branch + peso manual (não entra no
  `pickAwake` — só durante o modo).
- **Config**: dentro de `GoogleConfig` + `live.enabled: Boolean = false` no raiz? Não — preferir grupo
  `live: LiveConfig(enabled=false, maxDurationMinutes=30, silenceTimeoutSeconds=45)`. Per-device? **Não
  existe motivo para diferir entre aparelhos**, mas a sessão é local: deixar fora do mesh seria
  inconsistente; manter compartilhado (só a chave é local).
- **API**: `POST /api/v1/live {enable: true|false}` e `GET /api/v1/live` (estado); evento `live_started` /
  `live_ended`.
- **Frases**: categoria `live_intro` ("tô no modo papo furado, pode interromper!") e `live_bye`.
- **Custo**: free tier do Gemini (com limites de sessão/dia; respeitar e mostrar erro honesto de cota —
  reaproveitar o padrão `LlmQuota`).
- **Privacidade**: mesma postura do resto do app — o áudio vai ao provedor durante o modo, e o modo é
  opt-in por conversa (nunca inicia sozinho).

### Critérios de aceite

- Latência percebida de resposta < 1,5 s em rede local boa; interromper falando funciona sem comando;
- "que hora é todas as ferramentas" — pelo menos 3 tools reais executadas dentro de uma sessão Live
  (lembrete, clima, luz) com chips visíveis;
- perda de Wi-Fi encerra a sessão com fala ("a conexão caiu, tô de volta ao normal") e o app volta ao
  wake word sem restart do service;
- sem `google.apiKey`, o comando responde "preciso de chave do Google nas configurações".

---

## 3. Memória semântica (RAG) — lembrar por significado

### Objetivo

"Como era o nome daquela tia que odeia gatos?" hoje exige batimento de palavra. Com embeddings, o recall
encontra por sentido. Complementa (não substitui) a busca por palavra-chave.

### Design técnico

- **Dados** (`data/AppDatabase.kt`, versão 12 → **13**):
  ```kotlin
  @Entity(tableName = "memory_embeddings")
  data class MemoryEmbeddingEntity(
      @PrimaryKey val memoryId: Long,
      val dim: Int,               // sanity check (troca de modelo invalida)
      val model: String,          // ex.: "text-embedding-004"
      val vector: ByteArray,      // FloatArray serializado little-endian
  )
  ```
  `MemoryEmbeddingDao`: `insert`, `get`, `all()`, `delete(memoryId)`, `deleteAll(model)`.
  `MemoryEntity` não muda.
- **Arquivo novo** `brain/EmbeddingClient.kt`: cliente OpenAI-compatible `POST {url}/embeddings`
  (`url`, `model`, `key` configuráveis). Default quando `google.apiKey` existe:
  `https://generativelanguage.googleapis.com/v1beta/openai` + `text-embedding-004` (free tier). Batch de
  até 32 textos por chamada. Sem chave/sem rede → `null` (feature degrada).
- **`MemoryRepository`** (extensão, não quebra assinatura):
  - hook no `add()`: enfileirar embedding (corotina no `AppGraph.scope`, retry leve; memória fica usável
    antes do vetor chegar);
  - `suspend fun searchHybrid(query, personId?, limit): List<MemoryEntity>`: roda `search()` (palavra) e
    busca vetorial (cosseno sobre todos os embeddings do escopo — personal primeiro) e **mescla por ranking**
    (score intercalado, teste unitário); guardarResultado com `embedding` ausente = só palavra;
  - backfill: no boot, enfileirar memórias sem vetor (com rate limit ~1 req/2 s até completar).
- **Config** (`memory` já existe): `memory.semantic: String = "auto"` (`off|auto|only` — `auto` = usa
  embeddings se houver chave, `only` = só vetorial, `off` = como hoje). Modelos/url entram via
  `GoogleConfig`.
- **Mesh**: memórias continuam sendo compartilhadas por conteúdo (`shareMemory`); embeddings são
  **reconstruídos localmente** no aparelho que recebeu (evita enviar bytes a mais e permite modelos
  diferentes por aparelho).
- **Tools afetadas**: `remember`/`recall` passam a usar `searchHybrid` internamente (nada muda para o
  modelo). Prompt do cérebro continua com `recentForPrompt`.
- **API**: `GET /api/v1/memories/search?q=` (retorna hits + modo usado), `POST /api/v1/memories/embed
  {rebuild: true}` para reprocessar (troca de modelo).
- **Testes**: mescla híbrida, cosseno, degradação sem tabela.

### Critérios de aceite

- Consulta por significado ("aparelho pra dor de cabeça" acha memória "comprei ibuprofeno…") que a busca
  atual não acha;
- sem chave, tudo continua funcionando exatamente como hoje (zero regressão);
- 2.000 memórias: busca vetorial completa < 50 ms (brute force em Kotlin dá conta; sem dependência nova).

---

## 4. Diário interno — o robô tem vida interior

### Objetivo

Todas as noites o robô escreve uma entrada de diário sobre o dia, com a voz dele. Pode ser perguntado:
"no que você andou pensando?" / "como foi seu dia?".

### Design técnico

- **Dados** (versão 12 → **14** — uma migração por feature; ajustar numeração na ordem real de merge):
  ```kotlin
  @Entity(tableName = "diary")
  data class DiaryEntity(
      @PrimaryKey(autoGenerate = true) val id: Long = 0,
      /** ISO local date yyyy-MM-dd (dia sendo narrado). */
      val date: String,
      val personId: Long? = null,   // null = diário da casa
      val text: String,
      /** Humor do dia segundo o próprio robô (ex.: "animado", "calmo"). */
      val mood: String = "",
      val createdAt: Long = System.currentTimeMillis(),
  )
  ```
  `DiaryDao`: insert/update/get by date/recent(days)/delete.
- **Arquivo novo** `core/DiaryManager.kt`:
  - `fun start()` — agenda checagem no tick do `LifeDirector` (mesmo padrão de `lastDiaryDay`: campo
    privado + gatilho `LocalTime.now()`), não no `LifeDirector` em si (evitar inflar o diretor);
  - `suspend fun writeDay(date: LocalDate)`: reúne contexto barato — eventos do dia
    (`CalendarRepository.between`), lembretes disparados (`ReminderScheduler.list(false)` filtrando
    `firstFiredAt`), recados entregues (`MessageManager.pending` + entregues), memórias `source="auto"`
    do dia, clima dominante (`state.ambient`), resumo de sono (`SleepTracker.report(1)`), alertas recentes
    (`AlertManager.recent`), contadores do `HouseMood` (item 6) — e faz **uma chamada LLM separada**
    (`LlmClient.complete`, sem histórico, sem tools) com prompt pedindo 1 parágrafo em 1ª pessoa,
    pt-BR, no tom da `personality`, + `[mood:palavra]` no fim;
  - `var shareDiary: Boolean` — via mesh, mesmo padrão `shareMemory` (novo kind `"diary"` na rota genérica
    `post("/{kind}")` — nada de rota nova no protocolo);
  - tool `read_diary(days = 3)` para o cérebro responder "como foi seu dia?";
  - de manhã (primeira interação do dia), 1x, bolha/fala curta de "pensamento ao acordar" tirada do diário
    de ontem — categoria nova `diary_morning` no `PhrasePool` (o texto do diário vai como contexto na
    geração do lote).
- **Config** (`LifeConfig`): `life.diary: Boolean = true`, `life.diaryHour: Int = 23`,
  `life.diaryShare: Boolean = false`.
- **API**: `GET /api/v1/diary?days=7`, `POST /api/v1/diary` (nota manual assinada pelo robô), `DELETE /api/v1/diary/{id}`.
- **Evento**: `diary_written`.
- **Privacidade**: o diário resume só o que os outros subsistemas já registraram; toggle único para
  desligar; deletar entrada apaga em todos os aparelhos (mesh).

### Critérios de aceite

- Depois das 23h de um dia com lembretes/clima/conversa, existe entrada coerente em 1ª pessoa;
- "no que você andou pensando?" lê a entrada do dia via tool (sem inventar);
- dia morto (nada aconteceu) gera entrada curta e verdadeira ("hoje a casa ficou quieta…");
- funciona sem rede? **Não** — diário depende do LLM; sem rede, marca `pending` e escreve no dia seguinte.

---

## 5. Resumo do dia — "o que tenho hoje?"

### Objetivo

Perguntar "o que tenho hoje?" (ou "resumo") e ouvir tudo que está pendente: agenda, lembretes, timers,
recados, listas, sono, clima — na hora, ou de manhã automaticamente.

### Design técnico

- **Arquivo novo** `tools/DailyBriefTool.kt` + `core/DailyBrief.kt` (lógica pura, testável):
  ```kotlin
  data class Brief(
      val events: List<EventEntity>,      // hoje (ou dia pedido)
      val reminders: List<ReminderEntity>,// ativas com triggerAt <= fim do dia
      val timers: List<TimerEntity>,      // ativos
      val messages: List<MessageEntity>,  // pendentes para currentPerson + casa
      val lists: Map<String, List<ListItemEntity>>, // itens não marcados
      val sleep: String?,                 // report(1) do SleepTracker
      val weather: String?,               // resumo cacheado do ProactiveCache
      val alerts: List<AlertEntity>,      // recentes (24 h)
  )
  ```
  - `suspend fun build(person: PersonEntity?, day: LocalDate): Brief` — agrega das fontes acima;
  - `fun toJson(brief): JsonObject` (para a tool) e `fun toSpoken(brief): String` (frase pronta em pt-BR,
    com regras: nada pendente → "seu dia tá livre"; agrupar por manhã/tarde; recados citam quem mandou);
  - a **tool** `daily_brief(days_ahead = 0)` devolve JSON; o cérebro narra no tom dele. A versão falada
    pronta serve para o modo sem LLM (reserva).
- **Rotina da manhã** (config `LifeConfig`): `life.briefing: String = "off"` (`off|voice|bubble`),
  `life.briefingHour: Int = 8` — no primeiro `onSeen`/primeira interação após o horário (nunca falar para
  sala vazia — regra existente), `runtime.showBubble/toSay` do `toSpoken`. Registrar no `LifeDirector.tick`
  o flag `briefedForDay`.
- **Greeter**: `GreetContext` ganha `brief: Brief?` — bordões de saudação podem citar o próximo
  compromisso ("você tem dentista às 3!"). Implementar via `cat(...)` com categoria nova `brief_greet`.
- **API**: `GET /api/v1/brief?days=0` → JSON do Brief + `spoken`.
- **Testes**: agregação com fuso, lembretes `daily` contando como hoje, ordem cronológica, frase vazia.

### Critérios de aceite

- "o que tenho hoje?" lista compromissos + lembretes + recados pendentes sem perguntas extras;
- "e amanhã?" reusa a tool com `days_ahead=1`;
- briefing da manhã fala **uma vez por dia**, só com gente na frente, e não dispara se o aparelho acordou
  depois das 12h (config) para não atrapalhar.

---

## 6. Humor da casa — o robô sente o ambiente

### Objetivo

A partir dos sinais que o app já produz (YAMNet, interações, conversas, clima, hora), manter um
"clima da casa" contínuo que influencia as falas espontâneas, a emoção do robô e o prompt do cérebro.

### Design técnico

- **Arquivo novo** `core/HouseMood.kt` (sem Android deps no núcleo — testável):
  - entrada: `onSound(kind, score)` (chamar de `AssistentApp` junto de `interactions.onSound`),
    `onInteraction(kind)` (wave/pet/giggle — Interactions já tem os pontos), `onUserEmotionTag(tag)`
    (Brain já emite), `onConversationTurn()`, relógio e `Ambient` do clima;
  - núcleo: janelas com **decaimento exponencial** por categoria — `speechRate`, `laughterRate`,
    `tensionRate` (baby_cry, thunder, discussão = turnos rápidos altos), `livelyRate` (música, applaudos),
    `quietMinutes` (sem fala/som); computa `valence`/`arousal` derivados e um rótulo:
    `alegre | agitada | animada | calma | quieta | sonolenta | tensa | triste`;
  - `val mood: StateFlow<Mood>` com `Mood(label, valence, arousal, since)`; mudança de rótulo estável
    (histerese: 5 min no novo rótulo) → `EventDispatcher.emit("house_mood", …)`.
- **Integração**:
  - `Brain.systemPrompt()`: linha "Clima da casa agora: {rótulo} (últimos {n} min)" — o cérebro adapta
    respostas ("a casa tá animada, quer pôr uma música mais animada ainda?");
  - `EmotionEngine`: bias suave — casa animada soma em `robotValence`/`robotArousal`; casa tensa sobe
    `robotAnxiety` (cuidado: só bias pequeno, o neuro-server/PC continua dono quando conectado);
  - frases espontâneas: categoria nova `house_mood` (exemplos por rótulo, ex.: "a casa tá com energia
    boa hoje, né?") puxada a cada 1–3 h pelo `LifeDirector` (usar `chance()`), nunca à noite;
  - `Mesh`: broadcast do rótulo (kind `"mood"`) — o cérebro pode saber "na cozinha: calma, na sala: animada".
- **Config** (`LifeConfig`): `life.houseMood: Boolean = true`.
- **API**: `GET /api/v1/house-mood` → rótulo + métricas brutas.
- **Testes**: decaimento, histerese, rótulos por combinação de taxas, mapeamento kind→canal.

### Critérios de aceite

- Ambiente com risadas/música por 20 min vira "animada/alegre" e o robô comenta no máximo 1x;
- casa quieta por horas → "quieta", sem falas espontâneas à noite;
- choro de bebê recente → rótulo "tensa" e o robô **não** faz piada (regra no prompt: humor tenso =
  nunca humorístico — integrar ao `Brain.humorInstruction`).

---

## 7. Sonhos desenhados 🎨 — o robô pinta o sonho

### Objetivo

De vez em quando, dormindo, além do balão de sonho, o robô "pinta" o sonho: uma imagem gerada aparece
desvanecida ao lado do rosto, como uma pintura da imaginação dele.

### Design técnico

- **Cena** (`face/LifeScenes.kt`): `Scene.DREAM_PAINTING("dream_painting", 20f, Kind.DREAM, "Sonho pintado")`
  → branch em `Life.frame` (param alvo: olhos quase fechados olhando para o quadro, sorriso leve) + props:
  cavalete/tela com moldura de linhas (a imagem em si vem do bitmap, item 0.1) + pose de braços segurando
  pincel. Entra no sorteio do `dreamScene()` com peso baixo e só se `life.dreamImages != "off"`.
- **Fluxo** (`core/LifeDirector` + `AssistentApp` wiring):
  1. `dreamScene()` sorteia `DREAM_PAINTING`;
  2. `onDreamBubble` existente ganha par: `DreamSpec(phrase, imagePrompt)` — o `Brain.dreamPhrase` passa a
     pedir JSON `{"phrase": "...", "imagePrompt": "..."}` (o prompt de imagem em inglês, estilo "childlike
     neon line-art, black background", **sem nomes de pessoas/reais** — sanitizar removendo nomes
     cadastrados);
  3. `DreamImageClient` (arquivo novo `brain/DreamImageClient.kt`) busca a imagem:
     - `life.dreamImages = "pollinations"` (default): GET
       `https://image.pollinations.ai/prompt/{urlencoded}?width=640&height=640&nologo=true&seed={aleatório}`
       — grátis, sem chave, retorna JPEG;
     - `life.dreamImages = "sd"`: POST `{life.sdUrl}/sdapi/v1/txt2img` (Automatic1111 local no PC — grátis,
       privado), `base64` na resposta;
     - cache LRU em `filesDir/dreams/` (máx. 12 arquivos, ~640 px, decodificado com `inSampleSize`);
  4. ao chegar a imagem (timeout 25 s; falha = sonho só com balão, sem erro visível),
     `runtime.showFaceImage(...)` com fade longo e a cena mantém o quadro até o fim.
- **Config** (`LifeConfig`): `life.dreamImages: String = "off"` (`off|pollinations|sd`), `life.sdUrl: String`.
  Default `off` = comportamento de hoje (privacidade por padrão; `pollinations` envia só o prompt
  sanitizado, nunca áudio/rosto).
- **Frases**: ao acordar depois de sonho pintado, 1 chance de comentar "pintei um sonho hoje… quer ver?"
  (categoria `dream_painted`).
- **API**: `GET /api/v1/dreams` (lista cacheadas), `POST /api/v1/dream {prompt}` para testar no painel.
- **Privacidade**: nunca enviar nome de pessoas cadastradas no prompt (lista de nomes do `PersonDao` vira
  blocklist no sanitizador — teste unitário).

### Critérios de aceite

- Dormindo, com `dreamImages=pollinations`, a cada sonho sorteado o quadro aparece com fade e some no fim
  da cena, sem travar o rosto (decode em `Dispatchers.IO`);
- sem rede: sonho continua só com balão (nunca erro no meio do sono);
- sanitizador nunca deixa passar nome cadastrado (teste com 3 nomes);

---

## 8. Cliente MCP — um tool novo abre centenas de integrações

### Objetivo

Falar o protocolo **MCP (Model Context Protocol)** com servidores na rede (Home Assistant, servidores no
PC via proxy, etc.). Cada tool exposta pelo servidor MCP vira uma `Tool` nativa do robô — sem código por
integração.

### Design técnico

- **Config**:
  ```kotlin
  @Serializable
  data class McpServerConfig(
      val name: String,          // prefixo das tools, ex.: "casa"
      val url: String,           // ex.: http://192.168.1.20:8123/mcp_streamable (HA) ou proxy no PC
      val token: String = "",    // Bearer opcional
      val enabled: Boolean = true,
  )
  @Serializable
  data class McpConfig(val servers: List<McpServerConfig> = emptyList())
  ```
  Grupo `mcp: McpConfig = McpConfig()` no raiz (compartilhado via mesh como `webhooks` — é configuração da
  casa). **Nunca** stdio direto no Android: servidores stdio ficam para trás de um proxy HTTP no PC
  (`mcp-proxy`/`supergateway`, free) ou no `neuro-server` (adicionar modo proxy MCP lá é opcional).
- **Arquivo novo** `tools/McpClient.kt` (JSON-RPC 2.0 sobre Streamable HTTP, OkHttp + kotlinx.serialization,
  sem SDK externo):
  - `initialize` → `tools/list` (paginação ignorada: `cursor` suportado simples) → cache de
    `McpTool(name, description, schema: JsonObject)`;
  - `tools/call {name, arguments}` com timeout (30 s) e mapeamento de `isError`;
  - parsing tolerante: `content[]` do MCP pode ser `text` ou `resource` — concatenar textos como resultado
    para o modelo (mesmo contrato das tools nativas).
- **Arquivo novo** `tools/McpManager.kt`: observa `config.config` (debounce 1,5 s, igual ao Mesh), conecta
  servidores `enabled`, e para cada tool registra
  `tools.register(McpDynamicTool(server, mcpTool))` — nome `mcp_{server}_{tool}` sanitizado (`[a-z0-9_]`,
  máx. 60), descrição da tool original + prefixo "via MCP server {name}". Remover registrações quando o
  servidor sai do config (`ToolRegistry` precisa expor `unregister(prefix)` — pequena extensão).
- **Segurança**: token por servidor; erro de auth não derruba os outros servidores; NUNCA registrar tools
  MCP com nome colidindo com nativa (prefixo resolve); logs sem token.
- **Operations**: chips automáticos (já vêm de graça via `ToolRegistry.run`).
- **API**: `GET /api/v1/mcp` (servidores + tools descobertas + estado), `POST /api/v1/mcp/refresh`.
- **Ferramenta de diagnóstico** `mcp_status` (para o cérebro responder "a integração da casa tá fora").
- **Testes**: fixtures JSON-RPC (initialize/tools/list/tools/call/isError), sanitização de nomes,
  re-registro em mudança de config (fake Clock/OkHttp).

### Critérios de aceite

- Apontar para um servidor MCP de teste (ex.: `everything` server via proxy HTTP no PC): tools aparecem no
  `GET /api/v1/mcp`, o cérebro lista e executa uma ("dá oi pela tool de echo") com chip e resultado real;
- servidor cai → tool responde erro textual ao modelo, resto do robô ilesso;
- Home Assistant com `ha-mcp`/`mcp-server-home-assistant` (proxy no PC): acender luz por voz funciona
  (critério final, marcar como teste de campo).

---

## Ordem de implementação e dependências

```
0.1 bitmap no rosto ──┐
0.2 config Google ────┤
                      ▼
1. look  (menor esforço, maior uau imediato)
2. humor da casa  (só integra o que já existe)
3. diário  (usa LLM atual; sem deps)
4. resumo do dia  (usa itens já prontos; inclui hook do Greeter)
5. memória semântica  (RAG; migração própria)
6. sonhos desenhados  (precisa 0.1; rede externa opcional)
7. MCP  (independente; pode subir na fila se o usuário pedir)
8. modo Live  (maior risco/escopo; por último, precisa 0.2)
```

Cada bloco termina com: `./gradlew assembleDebug testDebugUnitTest` + teste no emulador via API + screenshot
+ atualização de `docs/API.md`, `ESTADO_ATUAL.md` e `README.md`.

## Custos (tudo em free tier / grátis)

| Recurso | Dependência externa | Custo |
|---|---|---|
| look | Gemini free tier **ou** cérebro principal | R$ 0 |
| Live | Gemini Live free tier | R$ 0 (com limites de uso) |
| Memória semântica | Gemini embeddings free tier **ou** endpoint próprio | R$ 0 |
| Diário | LLM já configurado | R$ 0 |
| Resumo do dia | nenhum | R$ 0 |
| Humor da casa | nenhum (local) | R$ 0 |
| Sonhos desenhados | Pollinations (sem chave) ou SD local no PC | R$ 0 |
| MCP | servidores self-hosted | R$ 0 |

## Riscos e notas transversais

- **APIs do Google mudam**: fixar shapes em um único lugar (`GoogleConfig` + clientes dedicados) e
  re-verificar a doc do Live/embeddings no dia da implementação.
- **Quota free tier**: `look` e Live devem tratar 429 com frase honesta e backoff (padrão `LlmQuota`).
- **Privacidade**: nenhum recurso novo manda imagem/áudio para fora sem opt-in (`look` manda frame ao
  provedor configurado — é o mesmo dado que o chat já manda; `dreamImages` manda só prompt sanitizado;
  Live é modo explícito). Documentar cada fluxo no README.
- **Mesh**: tudo o que é "da casa" (diário, humor, brief) propaga pelo protocolo genérico `post("/{kind}")`;
  nada disso pode quebrar aparelhos com config antiga (defaults + ignorar desconhecidos já garantem).
- **Emulador**: Live e câmera real são limitados — testar Live com áudio de arquivo (WAV → frames) e
  `look` com upload de imagem pela API (`/vision/detect` já tem o padrão de upload).
