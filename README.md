# Assistent

Um robô assistente open source para Android: um rosto animado (olhos e boca) em tela preta que conversa por voz, pesquisa na web, informa o clima, lembra das coisas, cria lembretes e alarmes e aciona qualquer coisa via webhooks. Todas as funções podem ser configuradas por uma API local.

> Nome provisório. O nome do assistente (padrão **Nexo**) muda nas configurações, e as frases de ativação acompanham.

## Recursos

| | |
|---|---|
| **Rosto** | Olhos ciano e boca, desenhados em tempo real com física de molas (60 fps). 10 emoções (neutro, feliz, alegria, triste, surpreso, dúvida, bravo, amor, pensativo, sonolento) e 6 estados (ocioso, ouvindo, pensando, pesquisando, falando, dormindo). Pisca, olha ao redor, a boca acompanha a voz, solta Zzz quando dorme. Cor, tamanho, formato, brilho e velocidade da animação são configuráveis. Funciona na vertical e na horizontal. |
| **Cérebro** | Qualquer endpoint compatível com OpenAI. O padrão é o [CLIProxyAPI](https://github.com/router-for-me/CLIProxyAPI) com login do ChatGPT (OAuth), que também aceita Gemini e Claude. Usa tool calling e escolhe uma emoção para cada resposta. |
| **Ativação por voz** | Offline (Vosk) e sem palavra fixa. Qualquer frase da lista serve (“ei Nexo”, “fala Nexo”, “acorda Nexo”…), com reconhecimento aproximado e variações do nome. Também acorda do modo sono. Um toque no rosto também ativa. |
| **Voz** | TTS do sistema, OpenAI (`gpt-4o-mini-tts`), qualquer servidor compatível (ex.: **Kokoro**, local e gratuito) ou ElevenLabs. Se a voz remota falhar, usa a do sistema. STT pelo reconhecedor do Android ou por uma API Whisper. |
| **Ferramentas** | Pesquisa na web (DuckDuckGo sem chave, SearXNG, Brave, Tavily), leitura de páginas, clima (Open-Meteo, sem chave), memória de longo prazo, lembretes e alarmes (com repetição), controle do celular (lanterna, volume, abrir apps e URLs) e **webhooks**: cada ação que você cadastra vira uma ferramenta da IA (Home Assistant, Node-RED, n8n, Shelly…). |
| **Visão** | Câmera frontal com ML Kit, processada no aparelho: os olhos seguem você e o robô sorri de volta. Nada é gravado. |
| **Vida própria** | Quando ninguém conversa com ele, faz coisas sozinho, variando com o horário e o clima: joga videogame com a linguinha pra fora, lê, estuda de óculos, passeia com o cachorro, ouve música, toma café, malha, medita, tira selfie, vê filme com pipoca, olha estrelas, boceja, espirra… Dormindo, sonha (namoradinha, pizza, carneirinhos) e fala dormindo. Acorda com movimento: leva susto, fica zonzo ou espia e volta a dormir. |
| **Bracinhos** | Braços gordinhos em forma de gota que seguram os objetos e fazem gestos (acenar, mão no queixo, dar de ombros). Podem ser desligados. |
| **Clima de verdade** | Chove ou neva na tela conforme o tempo na sua cidade, tem relâmpago na tempestade, ele sua no calor e treme com fumacinha no frio. |
| **Reconhecimento facial** | Cadastra rostos (MobileFaceNet, no aparelho). Quando alguém passa, puxa assunto num balãozinho com bordões que dependem da hora, do dia, do clima, de feriados, de aniversários e de há quanto tempo a pessoa sumiu. Com desconhecidos, puxa assunto sem nome. Imita você pela câmera: inclina a cabeça, pisca de volta, coloca o fone se você estiver de fone… |
| **Frases vivas** | Todas as falas espontâneas têm exemplos fixos, e a IA gera lotes novos que ficam salvos e são sorteados. Isso inclui curiosidades. |
| **Tom das respostas** | Natural, alegre, descontraído, ríspido, formal, sarcástico, fofo, dramático ou personalizado, com tamanho curto, médio ou detalhado. |
| **Vários aparelhos** | Pareamento por QR code: todos os aparelhos são o mesmo robô (configurações, rostos e memórias compartilhados). Cada um sabe o cômodo onde está e quem está onde, os lembretes tocam onde você está e dá pra pedir "avisa na cozinha que o jantar tá pronto". |
| **Relógio** | Digital retrô de 7 segmentos (ou analógico), num canto da tela. |
| **API local** | REST + SSE no celular (porta 8765) com token, mais um painel web em `http://IP-DO-CELULAR:8765/`. Veja [docs/API.md](docs/API.md). |
| **Eventos** | Webhooks de saída para `wake`, `sleep`, `user_said`, `reply`, `reminder`, `emotion`, `face_seen`. |

## Gestos

- **Toque**: falar (durante a fala, interrompe)
- **Toque duplo**: dormir / acordar
- **Toque longo**: configurações (as mudanças valem na hora)

## Começando

### 1. Cérebro: CLIProxyAPI com ChatGPT

No PC ou servidor da sua rede:

```bash
# https://github.com/router-for-me/CLIProxyAPI
./cli-proxy-api --codex-login      # abre o login OAuth do ChatGPT
./cli-proxy-api                    # sobe em :8317
```

No `config.yaml` do proxy, defina uma `api-keys` sua. No app, em **Cérebro (IA)**:

- Base URL: `http://IP-DO-PC:8317/v1`
- API key: a chave que você definiu no proxy
- Modelo: `gpt-6.1-sol` (o botão **Testar conexão** lista os disponíveis)

### 2. Compilar

Requisitos: JDK 17 e Android SDK 36.

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Na primeira execução o app pede microfone, câmera e notificações. Depois baixa o modelo de voz offline (~31 MB) para a ativação.

### 3. Voz melhor (opcional)

- **Kokoro (grátis, local)**: rode o [Kokoro-FastAPI](https://github.com/remsky/Kokoro-FastAPI) no PC. Em Voz, escolha `OpenAI / Kokoro`, URL `http://IP:8880/v1`, modelo `kokoro`, voz `pf_dora` (pt-BR).
- **OpenAI**: modelo `gpt-4o-mini-tts`. O campo “estilo” controla a entonação.
- **ElevenLabs**: API key + Voice ID.

## Arquitetura

```
face/       RobotFace (Canvas + física de molas), FaceParams/presets de emoção
brain/      LlmClient (OpenAI-compat), Brain (histórico, prompt, loop de tools, tag de emoção)
tools/      Tool + ToolRegistry, web, clima, memória, lembretes, dispositivo, webhooks
voice/      WakeWordEngine (Vosk), Listener (STT), Speaker (TTS) → PcmPlayer (lip sync)
vision/     FaceTracker (CameraX + ML Kit)
core/       AssistantRuntime (estado, emoção, sono, conversa), EventDispatcher
service/    AssistantService (foreground, loop de voz)
api/        LocalApiServer (Ktor), WebPanel
config/     AppConfig (tudo configurável), ConfigStore (JSON privado, recarrega ao vivo)
data/       Room: memórias e lembretes
```

Para adicionar uma capacidade, implemente `Tool` e registre em `AppGraph`. Ela fica disponível para a IA e em `POST /api/v1/tools/{name}`.

## Privacidade e segurança

- Chaves e token ficam só no armazenamento privado do app, sem backup (`allowBackup=false`). Nenhum segredo vai para o repositório: `local.properties`, keystores e `.env` estão no `.gitignore`.
- A API local exige token e trafega em HTTP puro dentro da rede. **Não exponha a porta na internet.** Para acesso remoto, use VPN (ex.: Tailscale).
- Ativação por voz e detecção de rosto rodam no aparelho. Só o texto da conversa vai para o LLM configurado (e o áudio, se você escolher STT/TTS remotos).

## Roadmap

- [ ] Reconhecimento facial (quem é quem) com embeddings locais
- [ ] Reconhecimento de locutor (voz de cada pessoa)
- [ ] Streaming de resposta + TTS por frase (menos latência)
- [ ] Interromper a fala com a voz (barge-in)
- [ ] TTS neural offline (Piper/Kokoro via sherpa-onnx)
- [ ] Integração nativa com Home Assistant
- [ ] Rotinas (“bom dia” → clima + agenda + notícias)

## Créditos e inspiração

Ideias de [OpenCompanion](https://github.com/vynride/opencompanion), [RAI](https://github.com/dustincorder/rai), [Haze Bot](https://github.com/dmvvilela/haze_bot_app), [OpenClaw Assistant](https://github.com/yuga-hashimoto/openclaw-assistant) e [OpenClaw Companion](https://github.com/marce1994/OpenClaw-Companion).

## Licença

Apache-2.0
