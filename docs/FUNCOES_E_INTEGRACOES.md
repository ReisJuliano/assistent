# Funções e integrações do assistente

Documento vivo para ampliar o assistente sem transformar cada integração em uma promessa vaga.

## Legenda

- `[ ]` ainda não começou
- `[~]` protótipo ou parte implementada, mas falta teste/integração completa
- `[x]` implementado e verificado no aparelho ou no ambiente real
- `[!]` depende de API, modelo, permissão ou hardware externo

## O que já foi pedido

### Rede e dispositivos

- `[~]` **Wake-on-LAN para PC e TV**
  - Cadastro de nome, MAC, broadcast e porta em `Configuração > Casa > Dispositivos da rede`.
  - Ferramenta da IA: acordar o dispositivo pelo nome.
  - Falta testar com um PC e uma TV reais; o aparelho precisa aceitar Wake-on-LAN e a rede precisa encaminhar o broadcast.
  - O padrão é o magic packet UDP; a mesma ideia existe na integração oficial do Home Assistant.
- `[ ]` **Estado dos dispositivos**
  - Depois do magic packet, verificar por ping/HTTP/ADB e informar `acordando`, `online` ou `não respondeu`.
  - Nunca confirmar “liguei” só porque o pacote foi enviado.
- `[ ]` **Android TV por ADB seguro**
  - Parear por IP/QR, abrir aplicativo, enviar teclas, controlar volume, pausar, retomar e desligar.
  - ADB deve ser opcional, com confirmação para ações destrutivas.
- `[ ]` **Google Cast/MediaRouter para TV**
  - Descobrir TVs e iniciar mídia compatível por Cast.
  - Filmes dependem da fonte: YouTube, Plex, Jellyfin, navegador ou aplicativo instalado.
- `[ ]` **TV por fabricante**
  - Adaptadores separados para Android TV, Samsung/Tizen, LG/webOS, Roku e Chromecast.
  - Não prometer “qualquer Smart TV” sem identificar o protocolo.
- `[ ]` **Bluetooth**
  - Descobrir, parear e selecionar fones/caixas usando as permissões modernas do Android.
  - Controlar play, pausa, volume e troca de faixa apenas quando o perfil Bluetooth do dispositivo oferecer isso.
  - Exibir claramente quando o Android exigir confirmação do sistema.
- `[ ]` **Home Assistant nativo**
  - Token, URL local/Tailscale, descoberta de entidades e chamada de serviços.
  - Isso cobre lâmpadas, tomadas, sensores, TV, persianas e automações sem criar um driver para cada marca.

### Música e mídia

- `[~]` **Spotify no próprio assistente**
  - Já existem ferramentas de busca/tocar, pausa, próxima faixa, volume e música atual.
  - Falta testar uma conta Premium real e tratar ausência de dispositivo ativo.
- `[~]` **Spotify em outro dispositivo**
  - Listar dispositivos Connect e transferir reprodução por nome, incluindo caixa/TV/Alexa quando aparecerem no Spotify.
  - Falta testar Alexa real e deixar a seleção de dispositivo mais amigável no app.
- `[ ]` **Spotify com fila, álbum, playlist e podcast**
  - Entender “toque o álbum”, “continue minha playlist” e “retome o podcast”.
- `[ ]` **Reprodução local**
  - Tocar arquivos locais, rádio HTTP, sons próprios e respostas faladas sem misturar a fila do Spotify.
- `[ ]` **Multiroom**
  - Tocar em vários aparelhos pareados ou transferir para um grupo quando o serviço suportar grupo.
  - Sincronização precisa usar um protocolo de mídia, não apenas falar em cada aparelho ao mesmo tempo.
- `[ ]` **Controle de filmes**
  - “Abra o filme”, “pause”, “avance 10 minutos”, “volte”, “qual episódio?” e “continue de onde parei”.
  - A implementação será por adaptadores Plex/Jellyfin/Cast/ADB.

### Experiência de Echo Dot

- `[ ]` **Alarmes completos**
  - Alarme único, repetição por dias, rótulo, pessoa, cômodo, som, volume, duração, soneca e cancelamento por voz.
- `[ ]` **Alarme musical**
  - Tocar som local, rádio ou Spotify no horário; respeitar Premium, rede e dispositivo disponível.
- `[ ]` **Despertar gradual**
  - Aumentar brilho, volume e animação aos poucos; oferecer “mais 5 minutos”.
- `[ ]` **Sons escolhíveis**
  - Biblioteca de sons próprios: sino, rádio, chuva, floresta, sintetizador, voz robótica e arquivo importado.
  - Cada som deve informar se está instalado e tocar uma prévia nas configurações.
- `[ ]` **Timer e cronômetro avançados**
  - Vários timers nomeados, contagem regressiva visual, repetir, pausar, adicionar tempo e avisar a pessoa certa.
- `[ ]` **Rotinas/cenas**
  - Uma frase executa várias ações em paralelo: luz, TV, música, clima, mensagem e timer.
  - Cada ação retorna sucesso/erro individual; a IA não diz que a rotina terminou se uma etapa falhou.
- `[ ]` **Interfone e anúncios**
  - Falar em todos os aparelhos, em um cômodo ou para uma pessoa; responder pelo aparelho de origem.
- `[ ]` **Modo não perturbe e volume noturno**
  - Horários silenciosos, exceções para alarmes/remédios e limite de volume por cômodo.
- `[ ]` **Resumo proativo**
  - Bom-dia com agenda, clima, timers, lembretes, recados e bateria dos dispositivos, por pessoa.

### Funções úteis pesquisadas

- `[ ]` **Listas por voz**: compras, tarefas, filmes para ver, livros e itens recorrentes.
- `[ ]` **Presença e contexto**: rosto, voz, cômodo, horário, dispositivo mais próximo e pessoa-alvo.
- `[ ]` **Mensagens internas**: recado entregue uma vez, confirmação de leitura e expiração.
- `[ ]` **Chamadas entre cômodos**: canal de voz com aceitar/recusar e encerramento por voz.
- `[ ]` **Detecção de sons**: campainha, bebê chorando, alarme, cachorro, gato, tosse e vidro quebrando, com regras de privacidade.
- `[ ]` **Tradução e conversão**: idioma, moeda, unidade, fuso e conversão falada rápida.
- `[ ]` **Acessibilidade**: leitura de texto pela câmera, descrição de ambiente, modo alto contraste e comandos sem tela.
- `[ ]` **Modo convidado**: comandos de casa sem acessar memórias, calendário, Spotify ou dados pessoais.
- `[ ]` **Permissões por pessoa**: criança, morador, cuidador e administrador; ações sensíveis exigem confirmação.
- `[ ]` **Auditoria compreensível**: histórico de ferramenta, dispositivo, horário, resultado e motivo de falha.
- `[ ]` **Modo offline**: wake word, timers, alarmes, Bluetooth local, Wake-on-LAN e comandos básicos sem internet.
- `[ ]` **Backup e restauração**: exportar configurações, listas, rotinas e aliases sem exportar tokens secretos.
- `[ ]` **Webhooks seguros**: ações customizadas com autenticação, timeout, retry e resultado verificável.
- `[ ]` **Plugins de comunidade**: adaptadores isolados por protocolo, sem permitir que uma integração trave o assistente.

## O que a pesquisa confirmou

1. **Wake-on-LAN** é um pacote mágico local; ele acorda apenas equipamentos configurados para aceitar WoL. O Home Assistant também separa o envio do pacote da verificação posterior de estado.
2. **Spotify Connect** permite listar dispositivos e transferir a reprodução para um dispositivo, mas a API exige Premium para controlar playback. Alexa só será destino se aparecer como dispositivo Spotify disponível.
3. **Spotify no próprio Android** não deve ser tratado como um player local ilimitado: o Web Playback SDK é voltado a navegador e também exige Premium. A primeira versão deve controlar o app/Connect; player local é outro módulo.
4. **Android TV** não possui uma API universal de “controle de qualquer TV”. O caminho confiável é escolher entre ADB pareado, Google Cast, MediaSession ou adaptador do fabricante.
5. **Alexa** oferece conceitos importantes para copiar: skills, filas de mídia, alarmes musicais, multiroom, rotinas e anúncios. Não devemos fingir que uma Alexa pode ser controlada por uma API pública genérica sem a conta/dispositivo e o protocolo corretos.
6. **Bluetooth** é uma integração de transporte, não uma garantia de controle: o Android pode conectar a caixa, mas play/volume/entrada dependem dos perfis e do fabricante.

## Fontes oficiais

- [Spotify: listar dispositivos disponíveis](https://developer.spotify.com/documentation/web-api/reference/get-a-users-available-devices)
- [Spotify: transferir reprodução](https://developer.spotify.com/documentation/web-api/reference/transfer-a-users-playback)
- [Spotify Web Playback SDK](https://developer.spotify.com/documentation/web-playback-sdk)
- [Alexa Music, Radio and Podcast Skill API](https://developer.amazon.com/en-US/docs/alexa/music-skills/understand-the-music-skill-api.html)
- [Android TV MediaSession](https://developer.android.com/training/tv/playback/media-session)
- [Home Assistant Wake-on-LAN](https://www.home-assistant.io/integrations/wake_on_lan)
- [Sherpa-ONNX keyword spotting](https://k2-fsa.github.io/sherpa/onnx/kws/index.html)

## Ordem de implementação

1. Wake-on-LAN + estado verificável.
2. Spotify: login por usuário, listar dispositivos, transferir e selecionar por cômodo.
3. Alarmes com sons, soneca e rotinas paralelas.
4. Adaptador Android TV: ADB + Cast.
5. Bluetooth com descoberta, pareamento assistido e media controls.
6. Home Assistant/Tailscale como ponte para o restante da casa.
7. Echo local: anúncios, interfone, multiroom, rotinas e modo não perturbe.
8. Plugins, permissões e auditoria.

## Critério geral de pronto

Uma função só pode ser marcada `[x]` quando: existe configuração na UI ou API, a ferramenta da IA descreve os limites, o resultado real é verificado, falhas não são confirmadas como sucesso, funciona sem bloquear outras ações e há teste no aparelho ou no dispositivo externo correspondente.
