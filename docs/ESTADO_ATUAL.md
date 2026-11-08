# Estado atual do app (01/11/2026)

O que o Assistent é hoje, o que já funciona, com que linguagem e bibliotecas. Para arquitetura e convenções
veja [CLAUDE.md](../CLAUDE.md); para o que falta, [ROADMAP.md](ROADMAP.md).

## Em uma frase

App Android de código aberto que transforma um celular/tablet num "robô de mesa": um rosto animado em tela
cheia (olhos neon, sobrancelhas, boca, braços) sobre fundo preto, que fala por voz, usa um LLM com ferramentas,
enxerga pela câmera, ouve a casa, tem vida própria quando está ocioso e é controlável por uma API REST local.

- Módulo único `:app`, pacote `io.github.assistent`.
- ~86 arquivos Kotlin, ~18 mil linhas, 12 arquivos de teste unitário JVM.
- Versão `0.1.<versionCode>`, onde `versionCode` = minutos desde 2026-01-01 (todo build é mais novo que o anterior).

## Linguagem e plataforma

| Item | Valor |
|---|---|
| Linguagem | Kotlin 2.2.0 |
| UI | Jetpack Compose (BOM 2025.06.01, Material 3) + um `Canvas` próprio para o rosto |
| SDK | minSdk 26, targetSdk 35, compileSdk 36 |
| JDK | 17 |
| Build | Gradle wrapper 8.14.3, AGP 8.12.0, KSP 2.2.0-2.0.2 |
| Release | R8 + `shrinkResources`, assinado hoje com a chave debug |
| Idiomas | textos do usuário em pt-BR; código, comentários e logs em inglês |

## Bibliotecas

**Base**: androidx core-ktx, activity-compose, lifecycle (runtime/viewmodel/service), kotlinx-coroutines,
kotlinx-serialization-json.

**Dados**: Room 2.7.2 (via KSP) — memórias, lembretes, pessoas, placar de jogos. Configuração em JSON no `filesDir`.

**Rede**: Ktor 3.2.0 server CIO (API REST local + SSE + WebSocket, CORS, content negotiation) para entrar;
OkHttp 4.12 para sair (LLM, TTS, clima, busca, Spotify).

**Visão**: CameraX 1.4.2, ML Kit face-detection + image-labeling + barcode-scanning, ZXing core,
LiteRT 1.4.0 (MobileFaceNet para identidade facial, EfficientDet-Lite0 para pessoas/pets/objetos),
MediaPipe tasks-vision 0.10.35 (mãos e gestos).

**Voz**: Vosk 0.3.75 (wake word offline) + JNA 5.13 (chamada nativa `vosk_model_find_word`), SpeechRecognizer do
Android ou API tipo Whisper para STT, TTS do Android / API compatível com OpenAI (Kokoro) / ElevenLabs,
sherpa-onnx 1.13.8 (embeddings de locutor CAM++), YAMNet (classificação de sons).

**Outros**: dadb 2.0.0 (protocolo ADB real, para controlar Android TV), JUnit 4.13.2.

Nenhum modelo grande vai no repositório: Vosk, MobileFaceNet, EfficientDet, YAMNet, MediaPipe e o modelo de
locutor são baixados em tempo de execução para o `filesDir`; o AAR do sherpa-onnx é baixado pela task Gradle
`downloadSherpaOnnx`. A fonte VT323 é OFL.

## O que já funciona

### Rosto e animação
- Rosto desenhado a cada frame em `Canvas`: silhuetas de olho estilo Cozmo (`EyeShape`), 5 estilos de olho
  (`solid`, `iris`, `cartoon`, `ring`, `orb`), sobrancelhas, boca, braços gordinhos.
- Cada parâmetro visual é uma mola amortecida independente, então trocar de expressão nunca "pula".
- Presets por emoção e por atividade; a cor é sempre a escolhida pelo usuário (nunca muda por emoção).
- Layout independente de orientação; ambiência de chuva/neve/névoa/relâmpago, suor no calor, bafo no frio.
- Tela como lâmpada (`LightGag`): cenas de piada que acendem um painel branco e reinkam o rosto.

### Conversa
- Ciclo de voz completo: wake word offline → escuta → LLM com ferramentas → fala, com follow-up sem precisar
  repetir o nome.
- Wake word funciona para **qualquer nome** (nada hard-coded): reconhecedor por gramática quando as palavras
  existem no vocabulário Vosk, fuzzy matching no decoder livre quando não; valida se foi chamada de verdade
  (nada de "comprei um parafuso"). Autoteste por TTS: 14 de 16 frases corretas.
- Fala enquanto gera e interrupção (barge-in): síntese adiantada em pipeline (lacunas de 0–4 ms medidas no
  emulador), palavra de parada ou o nome durante a fala, e "o que você ia falar?" retoma de onde parou.
- Voz de robô por DSP em tempo real (7 presets).
- Toque no rosto: 1 = escutar, 2 = interromper, 3 rápidos = dormir; carinho e rabiscos têm reação própria.

### Cérebro e ferramentas
- Qualquer endpoint compatível com OpenAI `/chat/completions` (padrão: CLIProxyAPI com Codex, `gpt-6.1-sol`).
- Login oficial "Sign in with ChatGPT" (OAuth+PKCE, tradução de histórico/ferramentas para `/v1/responses`),
  com tokens fora do config compartilhado e pausa automática em erro de cota.
- Ferramentas: busca na web, ler página, clima, memória, lembretes, dispositivo, webhooks (um por webhook
  configurado), luz da tela, TV/PC, Spotify, jogos, mesh (`where_is`, `say_in_room`, `list_rooms`).
- Chamadas da mesma rodada rodam em paralelo, aparecem como chips canceláveis no canto do rosto e saem por
  SSE; "parar" cancela todas.

### Vida própria
- Hobbies, sonhos, reações ao acordar, comentários e momentos de clima, todos **aleatórios** (sem agenda),
  com regras de vida real: nada de passear com o cachorro na chuva, estrelas só em noite limpa, suco no calor,
  chá no frio, lanche na hora do almoço.
- Ciclo de sono calmo e aleatório: dorme mesmo com gente perto, cochila sozinho e, dormindo, só acorda com
  interação de verdade (nome, toque, app) — movimento e rostos no máximo rendem uma espiada.
- Frases: cada categoria tem exemplos escritos à mão, a IA gera lotes de 10 no mesmo estilo e eles ficam
  salvos em `phrases.json`.
- O guarda-chuva fica de pé sempre que há chuva visível, inclusive durante conversa e sono.

### Interação social
Funcionam e estão testados: tchauzinho de volta (detector de aceno com teste unitário), polegar para
cima/baixo, vitória, "eu te amo", dança no BPM estimado da música (celular ou sala), ronronar com carinho,
reclamação quando sacodem o celular, reação ao carregador e à bateria fraca, "onde você foi?" ao tapar a
câmera, rosto muito perto, encarada longa, aceno e negativa de cabeça, tchau de quem já foi reconhecido.

### Ouvidos
YAMNet classifica o último segundo do microfone uma vez por segundo: fala, música, gato, cachorro, campainha,
batida na porta, espirro, risada, choro de bebê, trovão. Clipes ESC-50 deram gato 0.98, cachorro 0.89,
batida 0.94, trovão 0.85. Com música tocando a wake word exige casamento quase exato, então letra de música
não vira comando.

### Reconhecimento
- Rosto: alinhamento estilo ArcFace 112×112, filtro de qualidade, embedding somado ao do espelho, score misto
  com estabilização temporal e autoaprendizado de até 30 amostras boas.
- Voz: CAM++ no áudio da própria chamada — 7 de 7 corretos em locutores de teste do sherpa, desconhecidos ~0.36.
- Presença na casa: pessoas/pets/objetos por EfficientDet (pessoa 0.91 em amostras COCO), "roupa do dia" por
  histograma HSV do torso, voz na sala por YAMNet + identificação. Sinais fracos nunca viram identidade
  confiável para o cérebro.

### Casa
- Varredura de rede (~12 s no emulador): mDNS + SSDP/UPnP + sweep da /24, com nome e MAC de PCs Windows via
  NetBIOS e de Android TVs via ADB.
- Wake-on-LAN verificado: pacote mágico repetido e espera até o aparelho responder de fato.
- `tv_control`: status, o que está tocando, ligar/desligar, timer de desligamento que sobrevive a reinício,
  teclas, play/pause, próxima/anterior, seek, volume lido de volta, abrir app, YouTube, busca, URL,
  notificação na TV, digitar texto. TVs sem ADB (Samsung/LG/Chromecast) usam DIAL.
- Spotify: login PKCE próprio por dono, `music_play` com escolha de dispositivo em pt-BR, controles completos,
  resultados confirmados lendo `me/player` de volta.
- Lembretes e alarmes com AlarmManager, notificação, reagendamento no boot e falas geradas pela IA no tom
  configurado (com fallback escrito à mão; nunca piada em lembrete de remédio).

### Jogos
Quiz, verdadeiro ou falso, adivinhar o número (números falados), pedra-papel-tesoura pela mão na câmera ou
pela palavra, melhor de três. O robô conduz sem ida e volta ao LLM por resposta; placar por pessoa; modo
infantil simplifica as perguntas.

### Operação
- API REST local em `/api/v1` com token Bearer, SSE de eventos, webhooks de saída e painel HTML em `/`.
- Mesh multi-aparelho: pareamento por QR, config/rostos/memórias compartilhados, presença por sala,
  falar numa sala específica.
- Autoatualização: `publishRelease` cria release no GitHub, o app verifica periodicamente, confere o SHA-256
  e instala com `PackageInstaller` (silencioso depois da primeira confirmação). Só reabre o rosto se ele
  estava na tela e o aparelho está ligado e destravado.
- Tudo configurável em tempo real: `SettingsScreen` (toque longo) com 7 abas, sem botão de salvar.

## Limites conhecidos

- Mesh só foi testado num emulador (QR/pareamento pela API); falta validar com dois aparelhos reais.
- Spotify nunca foi testado com conta real; controle de playback exige Premium.
- Controle de TV verificado com o emulador fazendo o papel de TV; TVs reais pendentes, e o app velho de
  YouTube do emulador não confirma reprodução.
- Qualidade do reconhecimento facial depende do modelo baixado e da luz; o limiar é configurável.
- Rótulos de imagem do ML Kit para mímica são "melhor esforço".
- Release assinado com a chave debug: quem distribuir precisa de keystore própria.
- Não "atualize" o wrapper do Gradle sem testar: a versão 9.3.1 em cache falhava até em projeto Kotlin vazio.
