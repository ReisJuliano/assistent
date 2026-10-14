# Funcoes e integracoes do assistente

Este documento e um plano de implementacao por blocos. Um bloco representa uma
experiencia completa: suas partes relacionadas entram juntas, sao testadas juntas
e so recebem `[x]` quando o fluxo inteiro funciona. Nao marcar uma subfuncao
isolada como concluida apenas porque sua API foi criada.

## Regras para qualquer bloco

1. Ler `CLAUDE.md`, `AGENTS.md`, `docs/ROADMAP.md` e este arquivo antes de editar.
2. Reutilizar configuracao, ferramentas, eventos SSE, mesh e API local existentes.
3. Criar ou migrar tabelas sem apagar dados antigos.
4. Toda acao da IA deve retornar sucesso somente depois da operacao real; falha,
   timeout e estado desconhecido precisam chegar ao usuario.
5. Operacoes independentes devem rodar em paralelo e poder ser canceladas por
   voz, API ou pela rotina `parar`.
6. Cada bloco precisa ter: configuracao, persistencia, API, ferramenta da IA,
   estado visual, logs, testes unitarios e teste real no emulador/dispositivo.
7. Ao concluir um bloco: atualizar este arquivo, rodar
   `./gradlew.bat assembleDebug testDebugUnitTest`, instalar o APK, testar pelo
   API local e registrar um commit no Git.

## Regra transversal - feedback visual de ferramentas

Toda funcao que possa ser solicitada por voz deve ser uma operacao observavel,
e nao apenas uma frase da IA. Ao ouvir o pedido, o assistente mostra que entendeu,
aciona a ferramenta real e acompanha visualmente cada etapa ate sucesso, falha,
cancelamento ou estado desconhecido. Isso vale tambem para toque, API e mesh.

- A operacao recebe um `operationId` e publica estado `started`, `running`,
  `progress`, `succeeded`, `failed`, `cancelled` ou `unknown`.
- A animacao nunca afirma sucesso antes da confirmacao tecnica. Timeout,
  permissao negada, microfone indisponivel, camera sem leitura e dispositivo
  ausente aparecem na tela e chegam a fala do robo.
- Operacoes independentes podem mostrar animacoes simultaneas e continuam
  executando sem formar uma fila unica que trave o rosto, a fala ou a escuta.
- Cada ferramenta deve declarar sua cena/proprietario visual, seu evento de
  inicio e fim, e seu teste de erro. A palavra `parar` cancela todas as
  operacoes cancelaveis.

### Catalogo visual minimo

| Ferramenta | Feedback visual obrigatorio |
|---|---|
| `device_discover` | telas de TV, celular e PC surgem ao redor do robo; linhas mostram busca e conexao por dispositivo |
| Reconhecimento facial | o robo pega uma lupa com a mao e examina a leitura atual da camera; baixa confianca pede luz ou reposicionamento |
| Gravacao/identificacao de voz | microfone na mao, ondas de audio e indicador de captacao somente durante audio real |
| Pesquisa na internet | nos e redes se conectam, pacotes percorrem fontes, depois aparece comparacao e resultado |
| TV/musica/casa | controle remoto, alto-falante ou aparelho correspondente; descoberta, comando e confirmacao ficam distintos |
| Tarefa composta | varios paineis/dispositivos rodam em paralelo, cada um com progresso e resultado proprio |

As cenas usam a mao existente em `Arms.pose` e props de
`face/LifeScenes.kt`; nao criar uma terceira mao como desenho independente.
O teste de cada item deve iniciar a acao por voz e confirmar por API/evento e
por screenshot que a animacao acompanha o estado real.

### Registro (2026-10-14): regra transversal entregue

- `core/Operations.kt` + `ui/OperationsHud.kt`: toda chamada de ferramenta (voz, API, mesh) vira operação com id,
  rótulo, ícone por tipo e estado real (girando → ok / falhou / "?" sem confirmação / cancelada), eventos SSE
  `tool_*`, `GET /operations`, `POST /operations/cancel`; "parar" cancela tudo. Ferramentas da mesma rodada rodam em
  paralelo. Verificado no emulador (clima + pesquisa simultâneos, falha mostrada como falha).
- Falta: cenas/props específicas por ferramenta (lupa, microfone, rede de nós) além do chip e da atividade do rosto.

## Status

- `[x]` fluxo completo implementado e verificado
- `[~]` bloco em desenvolvimento
- `[ ]` ainda nao iniciado

## Ordem e dependencias

1. Fundacao de dispositivos e TV
2. Audio e Spotify
3. Presenca de desenvolvimento e extensao VS Code
4. Echo local, rotinas e multiroom
5. Alarmes, timers e lembretes persistentes
6. Casa inteligente e automacoes
7. Inteligencia, jogos e convivencia
8. Visao, reconhecimento e sensores
9. Aplicativo companheiro
10. Seguranca, diagnostico e manutencao

Um bloco pode usar outro que ja esteja `[x]`. Se uma tarefa revelar que outro
bloco e indispensavel, terminar o bloco dependente antes de continuar.

## Bloco 1 - Fundacao de dispositivos e experiencia de TV

Status: `[~]`

Este e um unico bloco. O comando `ligue a TV`, `abra um filme na TV` e `pause
na TV` deve passar pela mesma identidade de dispositivo, pela mesma verificacao
de estado e pela mesma selecao por comodo/pessoa.

### Escopo que entra junto

- Cadastro de PC, TV e dispositivo de rede com nome, comodo, pessoa, MAC,
  broadcast, IP/host, tipo e portas de verificacao.
- Descoberta de dispositivos na rede e apelidos editaveis.
- Wake-on-LAN seguido de polling de estado; nunca dizer que ligou apenas por
  ter enviado o pacote.
- Android TV por ADB: parear, conectar, listar apps, abrir app, Home, Back,
  volume, mute, ligar, desligar, pause, play, proxima, anterior e seek.
- TV, filmes e videos: Netflix, YouTube, Plex e Jellyfin; busca por titulo,
  ator, genero, temporada e episodio; continuar de onde parou; historico e
  lista assistir depois.
- Transportes de reproducao: ADB/MediaSession quando for Android TV,
  Google Cast quando o destino anunciar Cast e Spotify Connect para audio.
- Escolha automatica da TV mais proxima da pessoa, com confirmacao quando
  houver mais de uma TV elegivel.
- Notificacoes, recados e lembretes na TV.
- Modo cinema como rotina composta: TV, volume, luz, cortina, temperatura e
  celular, executando acoes independentes em paralelo.

### Implementacao esperada

- `LanDeviceConfig` e `TvDeviceConfig` devem ser evolutiveis e migradas sem
  apagar configuracoes existentes.
- Criar um `DeviceRepository` para descoberta, pareamento, health check,
  ultimo estado e comodo; nao espalhar sockets e comandos ADB nas tools.
- Criar ferramentas separadas e composaveis: `device_discover`,
  `device_status`, `wake_lan`, `tv_pair_adb`, `tv_control`, `tv_open_app`,
  `tv_search`, `tv_play` e `tv_queue`.
- Expor API local para listar dispositivos, parear, testar, controlar e
  consultar estado. Emitir eventos SSE de `device_state`, `device_action` e
  `device_error`.
- O fluxo de uma acao deve ser: selecionar destino -> acordar se necessario
  -> aguardar estado online -> conectar protocolo -> executar -> confirmar
  estado final. Cada etapa deve ter timeout e cancelamento.
- Registrar apenas dados tecnicos necessarios; credenciais de pareamento ficam
  no armazenamento privado e nunca entram no Git ou no mesh sem protecao.

### Face e animacoes

- Enquanto procura a TV: olhar ao redor e mostrar um estado de busca.
- Enquanto acorda/conecta: cena curta de mexer em um controle remoto.
- Enquanto busca filme: olhos curiosos e bolha de progresso sem cortar texto.
- Enquanto reproduz: cena assistindo, com controle remoto e reacoes ocasionais.
- Em erro: explicar o passo que falhou e oferecer nova tentativa, sem afirmar
  que a TV foi controlada.

### Aceite do bloco

- Uma TV pode ser cadastrada, descoberta, acordada, verificada e controlada
  pelo mesmo nome e comodo.
- O teste cobre TV desligada, TV iniciando, TV online e TV indisponivel.
- `ligue a TV`, `abra o YouTube`, `procure o filme`, `continue`, `pause`,
  `avance`, `volte` e `desligue` funcionam pelo fluxo de voz e pela API.
- Duas TVs visiveis nao causam comando no destino errado.
- O APK inicia no emulador, os testes passam e existe evidencia de teste em
  pelo menos um dispositivo Android TV real ou em um alvo ADB controlavel.

### Registro de entrega (2026-10-14)

- O cliente ADB anterior falava o protocolo do *servidor adb do PC* (porta 5037), não o da TV (5555, CNXN/AUTH RSA):
  não funcionava com TV real. Substituído por **dadb 2.0.0** (`AdbRemote` em `tools/TvAdb.kt`, chave RSA própria em
  `filesDir/adb`).
- `tv_control` confirma pelo estado real lido da TV: energia (`dumpsys power`), app em primeiro plano, sessão de mídia.
  Ações: status, power_on (Wake-on-LAN + `KEYCODE_WAKEUP` + polling), power_off, teclas, play/pause verificados,
  open_app por nome amigável, search (links diretos YouTube/Netflix/Spotify ou intent SEARCH), type_text.
- Teste real: o app conectou por ADB de rede no próprio emulador (`10.0.2.2:5555`) e confirmou abrir YouTube, buscar,
  desligar e ligar a tela; app ausente (Netflix) é reportado como tal.
- Falta para `[x]`: teste numa Android TV física (autorização de depuração na tela), Google Cast, histórico/"continuar",
  modo cinema, escolha por cômodo com 2+ TVs.

## Bloco 2 - Audio e Spotify

Status: `[~]`

Entram juntos: login por usuario, busca de faixa/artista/album/playlist/
podcast, selecao de dispositivo, play/pause/seek, fila, favoritos, retomada,
Spotify Connect na TV/PC/caixa/Alexa, multiroom e ducking. O assistente deve
  consultar dispositivos antes de dizer onde a musica esta tocando.

Arquivos principais: `SpotifyTools.kt`, configuracao Spotify, API local,
`EventDispatcher` e cenas `DANCE`. Testar 20 comandos consecutivos sem perder
o dispositivo ou confundir resposta de busca com reproducao.

## Bloco 3 - Presenca de desenvolvimento e extensao VS Code

Status: `[ ]`

- Extensao oficial em `integrations/vscode`.
- Presenca rica no estilo Discord: projeto, arquivo, linguagem, branch e
  tempo de atividade, com modo privado para ocultar nomes.
- Estados: escrevendo, depurando, compilando, testes, erro, pausado e ausente.
- Canal autenticado com o API local e atualizacao em tempo real por SSE/WebSocket.
- Cena `CODING`: o boneco sentado digitando em um teclado; reacoes diferentes
  para build ok, erro de compilacao, teste falhando e deploy.
- Configurar pessoa, projeto, comodo, privacidade e publicacao opcional no
  Discord. A extensao nao deve enviar codigo-fonte.

Aceite: abrir VS Code muda o status em segundos, trocar arquivo atualiza o
status, fechar/ficar ausente encerra a presenca e a cena do teclado aparece no
assistente sem travar conversa, musica ou reconhecimento.

## Bloco 4 - Echo local, rotinas e multiroom

Status: `[ ]`

Wake word livre por aparelho, cadastro guiado, follow-up, sussurro, nao
perturbe, volume noturno, anuncios por aparelho/comodo/pessoa, interfone,
canal de voz, chamadas autorizadas e rotinas com acoes paralelas. Incluir
bom-dia, boa-noite, cinema, dormir e acordar como rotinas reais, nao como texto.

## Bloco 5 - Alarmes, timers e lembretes

Status: `[ ]`

Despertadores unicos/recorrentes por pessoa e comodo, sons escolhidos,
musica/playlist, volume e luz graduais, soneca, confirmacao de acordar,
escalonamento para cuidador, timers nomeados simultaneos, pausa/retomada,
lembretes persistentes por rosto/voz/comodo e historico de confirmacao.

## Bloco 6 - Casa inteligente e automacoes

Status: `[ ]`

Home Assistant, luzes, tomadas, sensores, persianas, fechaduras, cameras,
climatizacao, cenas de presenca, chuva, frio, calor e seguranca, monitoramento
de PC/roteador/servidor, scripts autorizados e notificacoes em PC/celular/TV.
Incluir Tailscale para acesso remoto e descoberta de dispositivos.

## Bloco 7 - Inteligencia, jogos e convivencia

Status: `[ ]`

Resumos da manha/noite, perguntas de acompanhamento, memoria e preferencias
por pessoa/comodo, piadas contextuais, quizzes, jogos, placar, modo crianca,
visitante/cuidador, permissoes, historico, explicacao de falhas e regra global
de nunca confirmar tarefa nao executada.

## Bloco 8 - Visao, reconhecimento e sensores

Status: `[~]`

Entregue: aceno real (MediaPipe, palma aberta indo e voltando), joinha/paz/te amo, sons (YAMNet: campainha, choro,
animais, tosse, alarmes, vidro via classes), objetos e pessoas (EfficientDet), roupa do dia, voz no ambiente, rosto
alinhado. Falta: OCR, descrição do ambiente pela IA, objetos perdidos, validação em aparelho real.

Reconhecimento facial continuo e rastreamento da pessoa fixada, voz por pessoa,
gestos, aceno, palmas, campainha, choro, animais, tosse, alarmes, vidro,
OCR, descricao do ambiente, QR, objetos perdidos e iluminacao simulada. Toda
resposta sobre identidade deve consultar a leitura atual da camera.

## Bloco 9 - Aplicativo companheiro

Status: `[ ]`

Chat, compras, tarefas, agenda, recados, lembretes, controle de musica/TV,
historico, QR, token individual por celular, revogacao, permissoes, Tailscale,
push e chamadas entre aparelhos.

## Bloco 10 - Seguranca, diagnostico e manutencao

Status: `[ ]`

Backup/restauracao, exportacao sem segredos, atualizacao automatica, logs
pesquisaveis, diagnostico de microfone/camera/rede/Bluetooth, teste de cada
integracao, telas de dispositivos online, ferramentas disponiveis, permissoes,
tarefas em andamento e cancelamento universal por voz.

## Registro de cada entrega

Ao trabalhar em um bloco, adicionar ao final da secao:

- data e commit;
- arquivos e migrations criados;
- endpoints e ferramentas adicionados;
- testes automatizados e teste real executado;
- problemas conhecidos que impedem `[x]`.
