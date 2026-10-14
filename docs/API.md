# API local

Base: `http://IP-DO-CELULAR:8765/api/v1`. O token aparece em **Configurações → API local**.

Toda rota, exceto `/health`, exige:

```
Authorization: Bearer <token>
```

Se não der para enviar header (ex.: `EventSource`), use `?token=<token>`.

Painel web: `http://IP-DO-CELULAR:8765/`

## Estado e controle

| Método | Rota | Corpo | |
|---|---|---|---|
| GET | `/health` | | Sem token |
| GET | `/state` | | Atividade, emoção, último texto ouvido e respondido, posição do olhar e pessoa reconhecida atual |
| POST | `/chat` | `{"text":"...","speak":true}` | Conversa. Responde `{reply, emotion}` |
| POST | `/say` | `{"text":"...","emotion":"happy"}` | Fala o texto exato |
| POST | `/listen` | | Começa a ouvir (igual a tocar no rosto) |
| POST | `/emotion` | `{"emotion":"love","holdMs":5000}` | `neutral happy joy sad surprised confused angry love thinking sleepy` |
| POST | `/sleep` · `/wake` · `/stop` | | Dormir, acordar, parar de falar |
| POST | `/history/clear` | | Esquece a conversa atual (as memórias ficam) |
| GET | `/events` | | Stream SSE de eventos |
| GET | `/ws?token=...` | | WebSocket para eventos em tempo real e mensagens `{"type":"chat","text":"..."}` |

## Dispositivos e Android TV

Cadastre o dispositivo em `lanDevices` via `/config`, com `name`, `mac`, `host`,
`broadcast`, `adbPort` e, opcionalmente, `checkPort`. O mesmo cadastro e usado
para Wake-on-LAN e ADB.

| MÃ©todo | Rota | Corpo |
|---|---|---|
| GET | `/devices` | | Lista dispositivos cadastrados |
| POST | `/devices/{id}/status` | | Consulta estado por ADB |
| POST | `/devices/{id}/control` | `{"action":"play"}` ou `{"action":"open_app","value":"com.google.android.youtube.tv"}` |

Acoes Android TV: `status`, `home`, `back`, `up`, `down`, `left`, `right`,
`enter`, `play`, `pause`, `next`, `previous`, `volume_up`, `volume_down`,
`mute`, `power` e `open_app`. A resposta so informa sucesso depois que o
comando ADB foi aceito pelo dispositivo.

## Configuração

| Método | Rota | |
|---|---|---|
| GET | `/config` | Configuração completa (formato de `AppConfig.kt`) |
| PUT | `/config` | Substitui tudo |
| PATCH | `/config` | Merge parcial |
| POST | `/config/token/regenerate` | Gera um token novo |
| GET | `/llm/models` | Modelos expostos pelo proxy |

Exemplo: deixar o rosto rosa e trocar o nome.

```bash
curl -X PATCH http://IP:8765/api/v1/config -H "Authorization: Bearer $T" \
  -d '{"face":{"color":"#FF6FAE"},"personality":{"name":"Bia"}}'
```

## Memórias e lembretes

| Método | Rota | Corpo |
|---|---|---|
| GET | `/memories?q=termo&person=Diogo` | | `person` ou `personId` filtra/prioriza memórias da pessoa; sem filtro lista todas |
| POST | `/memories` | `{"content":"...","tags":"casa","person":"Diogo"}` (`personId` também aceito) |
| DELETE | `/memories/{id}` · `/memories` | |
| GET | `/reminders?all=true` | |
| POST | `/reminders` | `{"message":"Tomar remédio","datetime":"2026-10-14T08:00","kind":"alarm","repeat":"daily"}` ou `{"message":"...","in_minutes":10}`. Opcionais: `type` (medicine/water/food/exercise), `phrase` (frase exata, `{n}` = nome), `style` (ex. "motivacional") |
| DELETE | `/reminders/{id}` | |
| GET | `/reminders/{id}/preview` | | Frase que ele falaria agora para o lembrete (não dispara) |
| POST | `/reminders/{id}/ack` | | Confirma um lembrete que exige confirmação |

## Timers

| Método | Rota | Corpo |
|---|---|---|
| GET | `/timers?all=true` | |
| POST | `/timers` | `{"label":"chá","minutes":2}` ou `{"label":"forno","seconds":45}` |
| DELETE | `/timers/{id}` | |

## Agenda

| Método | Rota | Corpo |
|---|---|---|
| GET | `/calendar/events?from=2026-10-13T00:00&to=2026-10-20T23:59` | |
| POST | `/calendar/events` | `{"title":"Dentista","start":"2026-10-14T15:00","location":"Centro","recurrence":"none"}` |
| PATCH | `/calendar/events/{id}` | Campos parciais do evento |
| DELETE | `/calendar/events/{id}` | |

## Recados

| Método | Rota | Corpo |
|---|---|---|
| GET | `/messages` | |
| POST | `/messages` | `{"to":"Ana","text":"O jantar está no forno","from":"Diogo"}` |
| DELETE | `/messages/{id}` | |

## Listas

| Método | Rota | Corpo |
|---|---|---|
| GET | `/lists` | |
| GET | `/lists/compras` | |
| POST | `/lists/compras/items` | `{"text":"leite","qty":"2 caixas"}` |
| PATCH | `/lists/items/{id}` | `{"done":true}` |
| DELETE | `/lists/items/{id}` | |

## Spotify

Configure `spotify.clientId`, `spotify.accessToken` e opcionalmente `spotify.deviceId` via `/config`.
As ferramentas disponíveis são `music_play`, `music_pause`, `music_next`, `music_volume` e `music_now_playing`.

## App companheiro

| Método | Rota | Corpo |
|---|---|---|
| GET | `/companion/tokens` | |
| POST | `/companion/tokens` | `{"name":"Celular do Diogo","personId":1,"scopes":"chat,lists"}` retorna o token uma única vez; esse token também funciona em `Authorization: Bearer` |
| POST | `/companion/pair` | Igual ao anterior, mas também retorna `uri` para QR/deep link `assistent-companion://pair?...` |
| DELETE | `/companion/tokens/{id}` | Revoga o token |

## Sono, quizzes, voz e alertas

| Método | Rota | Corpo |
|---|---|---|
| GET | `/sleep/report?days=7` | Estimativa, não médica |
| GET | `/quiz/scores` | |
| POST | `/voice/enroll` | `{"personId":1}` prepara perfil de voz local |
| GET | `/alerts` | |
| POST | `/alerts` | `{"source":"homeassistant","kind":"doorbell","text":"Tem alguém na porta","room":"Sala"}` |

## Webhooks (ações personalizadas)

Cada webhook vira uma ferramenta `hook_<id>` que a IA pode chamar.

```bash
curl -X POST http://IP:8765/api/v1/webhooks -H "Authorization: Bearer $T" -d '{
  "id": "luz_sala",
  "description": "Liga ou desliga a luz da sala. state = on|off",
  "method": "POST",
  "url": "http://homeassistant.local:8123/api/services/light/turn_{{state}}",
  "headers": {"Authorization": "Bearer SEU_TOKEN_HA", "Content-Type": "application/json"},
  "body": "{\"entity_id\": \"light.sala\"}",
  "params": [{"name": "state", "description": "on ou off"}]
}'
```

| Método | Rota | |
|---|---|---|
| GET / POST | `/webhooks` | Listar / criar ou substituir pelo `id` |
| DELETE | `/webhooks/{id}` | |
| POST | `/webhooks/{id}/run` | `{"args":{"state":"on"}}` |

## Ferramentas

| Método | Rota | |
|---|---|---|
| GET | `/tools` | Todas as ferramentas com schema JSON |
| POST | `/tools/{name}` | `{"args":{...}}` executa direto, ex.: `get_weather`, `web_search` |

## Eventos de saída

Configure `events.webhookUrls` (e, se quiser, filtre por `events.events`). Cada evento chega como:

```json
{"event":"reply","data":{"text":"...","emotion":"happy","tools":["get_weather"]},"ts":1760000000000}
```

Eventos: `wake`, `sleep`, `user_said`, `reply`, `reminder`, `emotion`, `face_seen`.

## Vida, balão e clima

| Método | Rota | Corpo | |
|---|---|---|---|
| GET | `/scenes` | | Lista de cenas (atividades, sonhos, reações ao acordar) |
| POST | `/scene` | `{"scene":"gaming"}` | Toca uma cena agora |
| DELETE | `/scene` | | Para a cena atual |
| GET | `/chatgpt` | | Status do login "Sign in with ChatGPT" |
| POST | `/chatgpt/signin` | | Abre o login oficial do ChatGPT no navegador do aparelho |
| POST | `/chatgpt/signout` | | Desconecta a conta ChatGPT deste aparelho |
| POST | `/vision/detect` | imagem (JPEG/PNG) no corpo | Objetos/pessoas/bichos que ele vê: `[{"label":"cat","score":0.7,"box":"x1,y1,x2,y2"}]` |
| POST | `/sounds/classify` | WAV no corpo | O que ele ouve no clipe (YAMNet): `{"cat":0.98,...}` |
| POST | `/light` | `{"on":true,"style":"lamp","seconds":300}` / `{"on":false}` | Tela vira luz branca (brilho máximo) com uma gag: `lamp` (abajur), `phone` (lanterna do celular), `bulb` (ideia brilhante) ou `random` |
| POST | `/ambient` | `{"condition":"rain","temperature":8}` | Força um clima (teste). Condições: `clear`, `cloudy`, `fog`, `drizzle`, `rain`, `storm`, `snow` |
| POST | `/motion` | | Simula movimento na câmera (com ele dormindo) |
| POST | `/bubble` | `{"text":"Oi!","speak":false}` | Mostra um balão de fala |
| GET / DELETE | `/phrases` (`?category=`) | | Frases geradas pela IA, por categoria |

## Pessoas (reconhecimento facial)

| Método | Rota | Corpo |
|---|---|---|
| GET | `/people` | |
| POST | `/people/enroll` | `{"name":"Diogo"}`: olhe para a câmera por alguns segundos |
| PATCH | `/people/{id}` | `{"birthday":"10-13","notes":"..."}` |
| DELETE | `/people/{id}` | |
| POST | `/greet/test` | Mostra um cumprimento agora |

## Vários aparelhos (mesh)

| Método | Rota | |
|---|---|---|
| GET | `/mesh` | Este aparelho, os outros e quem está onde |
| POST | `/mesh/pair` | Gera o código e o conteúdo do QR (vale 5 min) |
| POST | `/mesh/connect` | `{"qr":"assistent://pair?..."}` ou `{"host":"192.168.0.20","code":"123456"}` |
| POST | `/mesh/leave` | Sai do grupo |
| POST | `/mesh/broadcast` | `{"text":"Jantar pronto!"}` fala em todos os aparelhos |
| POST | `/mesh/call` | `{"room":"Quarto"}` anuncia uma chamada de interfone simples |

As rotas de aparelho para aparelho (`/api/v1/mesh/hello`, `config`, `presence`, `person`, `memory`, `say`, `peer`) usam o header `X-Mesh-Secret`. A rota `/mesh/join` usa o código de pareamento.
