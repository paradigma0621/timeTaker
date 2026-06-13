# TimeTaker

Editor de markdown minimalista para registro de tempo (clock-in), em **Java 8 + Swing**.

## Por que Swing (e nao AWT)?

Ambos vem no JDK (sem runtime extra). Para um editor com barra de menu, aceleradores
de teclado e area de texto rolavel, o Swing entrega `JMenuBar` / `JTextArea` /
`JScrollPane` prontos, com muito menos codigo que o AWT puro — mantendo a aplicacao
leve e rapida no Windows 11. Os componentes "heavyweight" do AWT nao trazem ganho real
de desempenho neste caso.

## Funcionalidades

- Janela de **1000 x 700 px** centralizada na primeira execucao, com visual
  nativo do Windows.
- **Memoria de janela**: ao fechar, o tamanho e a posicao (x, y) sao salvos no
  arquivo de configuracoes; na reabertura a janela volta com as mesmas dimensoes
  e posicao. Se a posicao salva ficar fora das telas disponiveis (ex.: monitor
  removido), a janela e recentralizada. Estados maximizado/minimizado nao
  sobrescrevem o tamanho "normal" guardado.
- Barra de menu:
  - **Arquivos**: Novo arquivo, Abrir, Salvar, Salvar como
  - **Editar**: Configuracoes (fonte, tamanho e pasta padrao)
  - **Ajuda**
- Ao abrir, **carrega automaticamente o arquivo do dia** `ano-mes-dia.md`
  (ex: `2026-06-10.md`) na **pasta padrao** configurada (por padrao, a pasta
  de documentos do usuario, detectada conforme o sistema operacional).
- **Deteccao de SO** (`os.name`): a pasta de documentos e resolvida de forma
  diferente em cada sistema:
  - **Windows**: pasta fisica sempre `Documents` (mesmo em sistemas em
    portugues, onde o Explorer apenas exibe "Documentos").
  - **Linux**: segue a especificacao **XDG** — `XDG_DOCUMENTS_DIR`, depois o
    comando `xdg-user-dir DOCUMENTS`, depois `~/.config/user-dirs.dirs` — o que
    respeita nomes localizados como `~/Documentos`.
  - **macOS / outros**: `~/Documents`.
  - Em todos os casos, o app cria o arquivo do dia se ele ainda nao existir.
  - O atalho de menu tambem se adapta (Ctrl no Windows/Linux, Cmd no macOS).
- **Ctrl+I** (entrada): insere o registro
  `CLOCK: [ano-mes-dia ddd hora:minuto]` (ex: `CLOCK: [2021-11-16 ter 17:50]`),
  **sem quebra de linha** — apenas um espaco apos o `]` — e salva automaticamente.
  Com o cursor sob um **cabecalho de projeto** (veja abaixo), a entrada vai para o
  fim daquela secao; caso contrario, para o final do arquivo. Se houver uma tarefa
  em aberto (em qualquer projeto), ela e fechada antes com a hora atual.
- **Ctrl+O** (saida): fecha o **registro em aberto** — onde quer que esteja no
  documento — anexando o horario de saida e a duracao decorrida no padrao
  `CLOCK: [entrada]--[saida] =>  h:mm`; a descricao digitada apos a entrada
  permanece na mesma linha, apos a duracao. Ex.:

  ```
  CLOCK: [2021-11-16 ter 17:50]--[2021-11-16 ter 18:18] =>  0:28 revisando PR
  ```

  Se todos os registros ja tiverem saida (ou nao houver registro), o app avisa.
  A duracao usa precisao de minuto e suporta intervalos de varias horas (ex.: `4:00`).

## Projetos (estilo Org-mode)

Inspirado no clock-in/out do Org-mode do Emacs, o arquivo pode ser organizado em
**secoes de projeto**: qualquer linha iniciada por `*` (Org) ou `#` (markdown),
em qualquer nivel, seguida de espaco e um titulo, abre uma secao que vai ate o
proximo cabecalho.

```
* Projeto A
CLOCK: [2026-06-12 sex 09:00]--[2026-06-12 sex 10:30] =>  1:30 reuniao de design
CLOCK: [2026-06-12 sex 14:00] implementando parser

* Projeto B
CLOCK: [2026-06-12 sex 10:30]--[2026-06-12 sex 12:00] =>  1:30
```

- **Ctrl+I** com o cursor numa secao registra a entrada **no fim daquela secao**
  (apos a ultima linha nao vazia), fechando antes qualquer tarefa em aberto —
  como o `org-clock-in`, que para o relogio anterior ao iniciar um novo.
- **Ctrl+O** fecha o registro em aberto mesmo que ele nao seja o ultimo `CLOCK`
  do arquivo (ex.: registros fechados de outros projetos mais abaixo).
- **Ctrl+T** exibe o **relatorio de tempo por projeto** (estilo
  `org-clock-report`): soma as duracoes dos registros fechados de cada secao,
  recalculadas a partir dos horarios; registros antes do primeiro cabecalho
  entram em `(sem projeto)` e tarefas em aberto sao indicadas como
  `(em andamento)`.

  ```
  Tempo por projeto:

    Projeto A   1:30  (em andamento)
    Projeto B   1:30
    Total       3:00
  ```

- **Ctrl+Shift+T** **insere** esse mesmo relatorio **dentro do documento**, na
  posicao do cursor, com **indentacao hierarquica**: cada nivel de cabecalho
  (`*`/`#`, `**`/`##`, ...) ganha dois espacos a mais, refletindo a arvore de
  projetos. As duracoes ficam alinhadas a direita e cada secao mostra o seu
  proprio tempo. A insercao e desfazivel com `Ctrl+Z`.

  ```
  Tempo por projeto:

    Projeto A       1:30
      Subprojeto    0:45  (em andamento)
    Total           2:15
  ```

## Configuracoes

Em **Editar > Configuracoes** (`Ctrl+,`) e possivel escolher:

- **Fonte** (familia disponivel no sistema)
- **Tamanho** da fonte
- **Pasta padrao** de abertura/salvamento dos arquivos

As configuracoes sao gravadas em `timetaker.properties`, **sempre na mesma pasta
do `timetaker.jar`**, e recarregadas automaticamente na proxima execucao. O mesmo
arquivo guarda a geometria da janela:

```properties
font.name=...
font.size=...
default.dir=...
window.width=1000
window.height=700
window.x=...
window.y=...
```

## Atalhos de teclado

| Atalho         | Acao                          |
|----------------|-------------------------------|
| `Ctrl+N`       | Novo arquivo                  |
| `Ctrl+Shift+O` | Abrir                         |
| `Ctrl+S`       | Salvar                        |
| `Ctrl+Shift+S` | Salvar como                   |
| `Ctrl+,`       | Configuracoes                 |
| `F1`           | Ajuda                         |
| `Ctrl+I`       | Entrada: insere CLOCK no projeto do cursor (ou no fim do arquivo) |
| `Ctrl+O`       | Saida: fecha o CLOCK em aberto com horario + duracao |
| `Ctrl+R`       | Recalcula as duracoes de todos os registros fechados |
| `Ctrl+T`       | Relatorio de tempo por projeto |
| `Ctrl+Shift+T` | Insere o relatorio de tempo (indentado por projeto) no cursor |
| `Ctrl+Z`       | Desfazer                      |
| `Ctrl+Shift+Z` | Refazer                       |

## Compilar e executar

Windows:

```bat
build.bat
java -jar timetaker.jar
```

Linux/macOS:

```bash
./build.sh
java -jar timetaker.jar
```

A compilacao usa `javac --release 8`, garantindo bytecode compativel com Java 8.
