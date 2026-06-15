package com.timetaker;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nucleo de logica pura do TimeTaker, sem qualquer dependencia de GUI (Swing/AWT).
 *
 * Toda regra de negocio testavel vive aqui: formatacao/parsing de horario, as
 * transformacoes de texto dos registros CLOCK (entrada, saida, recalculo),
 * deteccao de sistema operacional, resolucao de pasta de documentos e a
 * leitura/escrita de configuracoes. A classe {@link TimeTakerApp} e apenas uma
 * casca de interface grafica que delega para estes metodos.
 */
public final class TimeTakerCore {

    private TimeTakerCore() {
        // Classe utilitaria: nao instanciavel.
    }

    /** Abreviacoes de dia da semana em portugues (3 caracteres), indexadas por Calendar.DAY_OF_WEEK. */
    public static final String[] WEEKDAYS_PT = {
            "",      // indice 0 nao usado
            "dom",   // Calendar.SUNDAY    = 1
            "seg",   // Calendar.MONDAY    = 2
            "ter",   // Calendar.TUESDAY   = 3
            "qua",   // Calendar.WEDNESDAY = 4
            "qui",   // Calendar.THURSDAY  = 5
            "sex",   // Calendar.FRIDAY    = 6
            "sab"    // Calendar.SATURDAY  = 7
    };

    /** Nome do arquivo de configuracoes, gravado sempre na pasta do .jar. */
    public static final String SETTINGS_FILE = "timetaker.properties";

    // ----------------------------------------------------- Deteccao de SO

    /** Normaliza o nome do SO (tolera null) para comparacao em minusculas. */
    private static String normOs(String os) {
        return os == null ? "" : os.toLowerCase(Locale.ROOT);
    }

    public static boolean isWindows(String os) {
        return normOs(os).contains("win");
    }

    /**
     * Calcula o novo tamanho de fonte aplicando {@code delta} ao tamanho atual,
     * com clamp nos limites 6 (piso) e 96 (teto).
     */
    public static int nextFontSize(int current, int delta) {
        return Math.max(6, Math.min(96, current + delta));
    }

    public static boolean isLinux(String os) {
        String o = normOs(os);
        return o.contains("linux") || o.contains("nix") || o.contains("nux") || o.contains("aix");
    }

    public static boolean isMac(String os) {
        String o = normOs(os);
        return o.contains("mac") || o.contains("darwin");
    }

    // ----------------------------------------------------- Horario / formatacao

    /** Instante atual com segundos/milissegundos zerados (precisao de minuto). */
    public static Calendar nowToMinute() {
        return toMinute(Calendar.getInstance());
    }

    /** Zera segundos/milissegundos do Calendar informado e o devolve (precisao de minuto). */
    public static Calendar toMinute(Calendar cal) {
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal;
    }

    /** Abreviacao do dia da semana em portugues (3 chars) para um Calendar.DAY_OF_WEEK. */
    public static String weekdayPt(int dayOfWeek) {
        if (dayOfWeek < 1 || dayOfWeek >= WEEKDAYS_PT.length) {
            return "";
        }
        return WEEKDAYS_PT[dayOfWeek];
    }

    /** Formata um instante como "yyyy-MM-dd ddd HH:mm" (ddd = dia em portugues, 3 chars). */
    public static String formatClockStamp(Calendar cal) {
        String date = new SimpleDateFormat("yyyy-MM-dd").format(cal.getTime());
        String time = new SimpleDateFormat("HH:mm").format(cal.getTime());
        String weekday = weekdayPt(cal.get(Calendar.DAY_OF_WEEK));
        return date + " " + weekday + " " + time;
    }

    /** Interpreta o conteudo entre colchetes ("yyyy-MM-dd ddd HH:mm") como Date (data + hora). */
    public static Date parseClockInner(String inner) {
        if (inner == null) {
            return null;
        }
        String[] parts = inner.trim().split("\\s+");
        if (parts.length < 2) {
            return null;
        }
        String date = parts[0];
        String time = parts[parts.length - 1]; // ultimo token = HH:mm (ignora o dia da semana)
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        fmt.setLenient(false);
        try {
            return fmt.parse(date + " " + time);
        } catch (ParseException ex) {
            return null;
        }
    }

    /** Formata uma duracao em milissegundos como "h:mm" (ex.: 0:28, 1:05). Negativos viram 0. */
    public static String formatDuration(long millis) {
        if (millis < 0) {
            millis = 0;
        }
        long totalMinutes = millis / 60000L;
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        return hours + ":" + String.format("%02d", minutes);
    }

    // ----------------------------------------------------- Transformacoes CLOCK

    /** Resultado de uma transformacao de texto: novo conteudo e posicao do cursor. */
    public static final class TextEdit {
        public final String text;
        public final int caret;

        public TextEdit(String text, int caret) {
            this.text = text;
            this.caret = caret;
        }
    }

    // ----------------------------------------------------- Diff minimo (Document)
    //
    // Ao aplicar um texto novo ao documento do editor, em vez de substituir TODO o
    // conteudo (setText -> remove(0,len) + insert(0,texto): dois edits, e um unico
    // Ctrl+Z deixaria o documento vazio), calcula-se a menor regiao que difere entre o
    // texto atual e o novo. So essa regiao e remove+inserida, mantendo o historico de
    // undo minimo e correto (uma insercao pura vira um unico insertString -> um unico
    // Ctrl+Z a reverte).

    /** Comprimento do maior prefixo comum entre {@code a} e {@code b}. */
    public static int commonPrefixLength(String a, String b) {
        int max = Math.min(a.length(), b.length());
        int i = 0;
        while (i < max && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }

    /**
     * Comprimento do maior sufixo comum entre {@code a} e {@code b}, limitado a
     * {@code maxLen} para nao invadir um prefixo comum ja contabilizado (evita contar
     * o mesmo caractere duas vezes quando as strings se sobrepoem).
     */
    public static int commonSuffixLength(String a, String b, int maxLen) {
        int max = Math.min(Math.min(a.length(), b.length()), maxLen);
        int i = 0;
        while (i < max && a.charAt(a.length() - 1 - i) == b.charAt(b.length() - 1 - i)) {
            i++;
        }
        return i;
    }

    /**
     * Edicao minima que transforma um texto em outro: substitui o trecho
     * {@code [offset, offset + removeLen)} pelo {@code insertText}. Para uma insercao
     * pura, {@code removeLen == 0}; para uma remocao pura, {@code insertText} e vazio.
     */
    public static final class DiffEdit {
        public final int offset;
        public final int removeLen;
        public final String insertText;

        public DiffEdit(int offset, int removeLen, String insertText) {
            this.offset = offset;
            this.removeLen = removeLen;
            this.insertText = insertText;
        }
    }

    /**
     * Calcula a edicao minima (via prefixo e sufixo comuns) que transforma
     * {@code oldText} em {@code newText}. Quando os textos sao iguais, retorna uma
     * edicao vazia ({@code removeLen == 0} e {@code insertText} vazio).
     */
    public static DiffEdit computeDiff(String oldText, String newText) {
        int prefix = commonPrefixLength(oldText, newText);
        int maxSuffix = Math.min(oldText.length(), newText.length()) - prefix;
        int suffix = commonSuffixLength(oldText, newText, maxSuffix);
        int removeLen = oldText.length() - prefix - suffix;
        String insertText = newText.substring(prefix, newText.length() - suffix);
        return new DiffEdit(prefix, removeLen, insertText);
    }

    /** Situacao da tentativa de fechar o ultimo CLOCK em aberto. */
    public enum CloseStatus {
        NO_CLOCK,        // nenhum "CLOCK: [" encontrado
        MALFORMED,       // sem o ']' de fechamento
        ALREADY_CLOSED,  // ja possui "--[" (horario de saida)
        UNPARSEABLE,     // horario de entrada nao interpretavel
        CLOSED           // fechado com sucesso
    }

    /**
     * Resultado de fechar um CLOCK: situacao e (quando CLOSED) o novo texto, o cursor e
     * o inicio da linha do registro fechado ({@code lineStart}, -1 nos demais status).
     */
    public static final class CloseResult {
        public final CloseStatus status;
        public final String text;
        public final int caret;
        public final int lineStart;

        public CloseResult(CloseStatus status, String text, int caret, int lineStart) {
            this.status = status;
            this.text = text;
            this.caret = caret;
            this.lineStart = lineStart;
        }

        public boolean closed() {
            return status == CloseStatus.CLOSED;
        }
    }

    private static final String CLOCK_OPEN = "CLOCK: [";

    // ----------------------------------------------------- Drawer LOGBOOK (estilo Org)
    //
    // Cada grupo de registros CLOCK vive dentro de um drawer estilo Org-mode: uma linha
    // ":LOGBOOK:" abre o drawer e uma linha ":END:" o fecha. Convencao de alinhamento
    // adotada aqui: as linhas ":LOGBOOK:"/":END:" e os registros CLOCK ficam SEM
    // indentacao extra (coluna 0), mantendo o documento consistente e simples. Convencao
    // de ordenacao (igual ao Emacs Org): o clock-in mais recente entra no TOPO do drawer,
    // logo apos a linha ":LOGBOOK:". As linhas do drawer NAO sao registros CLOCK: nao
    // contem "CLOCK: [" nem casam o padrao de duracao, portanto sao ignoradas naturalmente
    // por closeOpenClock, clockReport e recalculateDurations.

    /** Linha que abre o drawer de registros (comparada apos trim). */
    static final String LOGBOOK_OPEN_LINE = ":LOGBOOK:";

    /** Linha que fecha o drawer de registros (comparada apos trim). */
    static final String LOGBOOK_CLOSE_LINE = ":END:";

    /**
     * Procura, na regiao {@code [from, to)} de {@code text}, o inicio da primeira linha
     * ":LOGBOOK:" (apos trim). Retorna o indice do inicio dessa linha ou -1 se a regiao
     * nao contiver nenhum drawer. {@code to} e sempre um inicio de linha ou o fim do texto,
     * de modo que a varredura nunca corta uma linha ao meio.
     */
    static int findLogbookOpenLine(String text, int from, int to) {
        int lineStart = from;
        while (lineStart < to) {
            int nl = text.indexOf('\n', lineStart);
            int lineEnd = nl < 0 ? text.length() : nl;
            if (text.substring(lineStart, lineEnd).trim().equals(LOGBOOK_OPEN_LINE)) {
                return lineStart;
            }
            if (nl < 0) {
                break;
            }
            lineStart = nl + 1;
        }
        return -1;
    }

    /**
     * Insere "CLOCK: [now] " no TOPO de um drawer ja existente, logo apos a linha
     * ":LOGBOOK:" que comeca em {@code logbookLineStart}. Em um drawer valido sempre ha
     * uma quebra de linha apos ":LOGBOOK:" (a linha ":END:" vem depois); o ramo sem quebra
     * cobre um ":LOGBOOK:" digitado como ultima linha solta. O cursor fica logo apos o
     * espaco do novo registro, pronto para a descricao.
     */
    private static TextEdit insertIntoExistingDrawer(String content, int logbookLineStart, Calendar now) {
        String clock = CLOCK_OPEN + formatClockStamp(now) + "] ";
        int nl = content.indexOf('\n', logbookLineStart);
        int insertAt = nl < 0 ? content.length() : nl + 1;
        String prefix = nl < 0 ? "\n" : "";
        String updated = content.substring(0, insertAt) + prefix + clock + "\n" + content.substring(insertAt);
        return new TextEdit(updated, insertAt + prefix.length() + clock.length());
    }

    /**
     * Cria um novo drawer (":LOGBOOK:" + a linha CLOCK + ":END:") logo apos {@code insertAt}
     * (um indice de fim de linha, ou o fim do texto), precedido de uma quebra de linha. O
     * cursor fica apos o espaco do novo registro (a linha do meio do drawer).
     */
    private static TextEdit createDrawerAt(String content, int insertAt, Calendar now) {
        String clock = CLOCK_OPEN + formatClockStamp(now) + "] ";
        String drawer = LOGBOOK_OPEN_LINE + "\n" + clock + "\n" + LOGBOOK_CLOSE_LINE;
        String updated = content.substring(0, insertAt) + "\n" + drawer + content.substring(insertAt);
        int caret = insertAt + 1 + (LOGBOOK_OPEN_LINE + "\n").length() + clock.length();
        return new TextEdit(updated, caret);
    }

    /**
     * Fecha o ultimo registro CLOCK em aberto, anexando "--[saida] =>  h:mm" logo apos o
     * horario de entrada. As linhas sao varridas de tras para frente em busca do ultimo
     * registro NAO fechado: com secoes de projeto, o registro em aberto pode estar acima
     * de registros ja fechados de outros projetos. A descricao digitada apos o registro
     * (se houver) permanece na mesma linha, reposicionada depois da duracao; a quebra de
     * linha vai para o fim da linha. Funcao pura: nao altera nada externo. Quando o
     * status e CLOSED, {@code text} traz o documento atualizado e {@code caret} o inicio
     * da linha seguinte. Nos demais status, {@code text} e o original e {@code caret} = -1.
     */
    public static CloseResult closeOpenClock(String text, Calendar now) {
        boolean sawClock = false;
        int lineEnd = text.length(); // fim (exclusivo) da linha corrente
        while (true) {
            int lineStart = text.lastIndexOf('\n', lineEnd - 1) + 1;
            String line = text.substring(lineStart, lineEnd);

            int idx = line.indexOf(CLOCK_OPEN);
            if (idx >= 0) {
                sawClock = true;
                int openBracket = idx + CLOCK_OPEN.length() - 1; // indice do '[' na linha
                int closeBracket = line.indexOf(']', openBracket);
                if (closeBracket < 0) {
                    return new CloseResult(CloseStatus.MALFORMED, text, -1, -1);
                }
                if (!line.startsWith("--[", closeBracket + 1)) {
                    // Registro em aberto: este e o alvo.
                    String inner = line.substring(openBracket + 1, closeBracket).trim();
                    Date entryTime = parseClockInner(inner);
                    if (entryTime == null) {
                        return new CloseResult(CloseStatus.UNPARSEABLE, text, -1, -1);
                    }

                    String exitStamp = formatClockStamp(now);
                    String diff = formatDuration(now.getTimeInMillis() - entryTime.getTime());
                    String insertion = "--[" + exitStamp + "] =>  " + diff;

                    // A descricao digitada apos o ']' de entrada fica na mesma linha:
                    // e reposicionada depois da duracao, e a quebra de linha vai para
                    // o fim da linha do registro.
                    String description = line.substring(closeBracket + 1).trim();
                    if (!description.isEmpty()) {
                        description = " " + description;
                    }
                    String newLine = line.substring(0, closeBracket + 1) + insertion + description;
                    String tail = lineEnd < text.length() ? text.substring(lineEnd + 1) : "";
                    String updated = text.substring(0, lineStart) + newLine + "\n" + tail;
                    int caret = lineStart + newLine.length() + 1; // inicio da linha seguinte
                    return new CloseResult(CloseStatus.CLOSED, updated, caret, lineStart);
                }
                // Registro ja fechado: segue procurando um aberto nas linhas acima.
            }

            if (lineStart == 0) {
                break;
            }
            lineEnd = lineStart - 1; // pula o '\n' da linha anterior
        }
        return new CloseResult(sawClock ? CloseStatus.ALREADY_CLOSED : CloseStatus.NO_CLOCK,
                text, -1, -1);
    }

    /**
     * Logica do Ctrl+I: se houver uma tarefa em aberto, fecha-a silenciosamente com
     * {@code now}; em seguida garante quebra de linha e anexa um novo registro de
     * entrada "CLOCK: [now] ". O cursor fica no fim do documento.
     */
    public static TextEdit insertClockLine(String text, Calendar now) {
        CloseResult close = closeOpenClock(text, now);
        String content = close.closed() ? close.text : text;

        // Se ja existir um drawer no documento, o novo registro entra no topo dele.
        int logbookLineStart = findLogbookOpenLine(content, 0, content.length());
        if (logbookLineStart >= 0) {
            return insertIntoExistingDrawer(content, logbookLineStart, now);
        }

        // Sem drawer: cria um novo no fim do documento (garante quebra de linha antes).
        if (content.length() > 0 && !content.endsWith("\n")) {
            content = content + "\n";
        }
        String clock = "CLOCK: [" + formatClockStamp(now) + "] ";
        String drawer = LOGBOOK_OPEN_LINE + "\n" + clock + "\n" + LOGBOOK_CLOSE_LINE;
        String result = content + drawer;
        // Cursor apos o espaco do registro CLOCK (a linha do meio do drawer).
        int caret = content.length() + (LOGBOOK_OPEN_LINE + "\n").length() + clock.length();
        return new TextEdit(result, caret);
    }

    // ----------------------------------------------------- Projetos (estilo Org-mode)

    /**
     * Cabecalho de projeto: linha iniciada por "*" (Org-mode) ou "#" (markdown), em
     * qualquer nivel, seguidos de espaco e um titulo nao vazio. Grupo 2 = titulo.
     */
    static final Pattern HEADING = Pattern.compile("^(\\*+|#+)\\s+(\\S.*)$");

    /**
     * Numero de enumeracao hierarquica no inicio de um titulo de cabecalho ("1", "1.2",
     * "1.2.3"), seguido de espaco e do restante do titulo. Grupo 1 = numero; grupo 2 = resto.
     * Usado para (re)numerar topicos (Ctrl+E) e para ignorar o numero ao colorir TODO/DONE.
     */
    static final Pattern LEADING_NUMBER = Pattern.compile("^(\\d+(?:\\.\\d+)*)\\s+(\\S.*)$");

    /** Indica se a linha e um cabecalho de projeto. */
    public static boolean isHeading(String line) {
        return HEADING.matcher(line).matches();
    }

    /** Titulo (sem marcadores) de uma linha de cabecalho, ou null se nao for cabecalho. */
    public static String headingTitle(String line) {
        Matcher m = HEADING.matcher(line);
        return m.matches() ? m.group(2).trim() : null;
    }

    // ----------------------------------------------------- Palavras-chave TODO/DONE

    /** Tipo de palavra-chave de tarefa reconhecida no inicio do titulo de um cabecalho. */
    public enum Keyword {
        TODO,
        DONE
    }

    /**
     * Trecho (span) de texto a ser colorido: {@code start} e o offset absoluto da palavra
     * no texto completo (contando os '\n'), {@code length} o seu comprimento e {@code kind}
     * a palavra-chave correspondente. Imutavel; consumido pela GUI para aplicar a cor.
     */
    public static final class KeywordSpan {
        public final int start;
        public final int length;
        public final Keyword kind;

        public KeywordSpan(int start, int length, Keyword kind) {
            this.start = start;
            this.length = length;
            this.kind = kind;
        }
    }

    /**
     * Localiza as palavras-chave TODO/DONE a colorir no estilo Org-mode: apenas quando a
     * palavra e o PRIMEIRO token do titulo de um cabecalho (logo apos o marcador "#"/"*" e o
     * espaco). TODO/DONE em meio a texto comum, no corpo, ou que nao sejam o primeiro token
     * sao ignorados. Os offsets retornados sao ABSOLUTOS no {@code text} (contando os '\n'),
     * prontos para a GUI aplicar diretamente. Funcao pura.
     */
    public static List<KeywordSpan> keywordSpans(String text) {
        List<KeywordSpan> spans = new ArrayList<>();
        int len = text.length();
        int lineStart = 0;
        // O laco sempre termina pelo break interno (nl < 0 na ultima linha); por isso usa
        // "while (true)" — uma condicao "lineStart <= len" teria um ramo-falso inalcancavel.
        while (true) {
            int nl = text.indexOf('\n', lineStart);
            int lineEnd = nl < 0 ? len : nl;
            String line = text.substring(lineStart, lineEnd);
            Matcher m = HEADING.matcher(line);
            if (m.matches()) {
                int titleStart = m.start(2); // inicio do titulo (primeiro nao-espaco apos o marcador)
                // Ignora um numero de enumeracao inicial ("1.2 ") para que TODO/DONE logo apos o
                // numero continuem sendo coloridos (interacao com a auto-enumeracao do Ctrl+E).
                Matcher num = LEADING_NUMBER.matcher(line.substring(titleStart));
                if (num.matches()) {
                    titleStart += num.start(2);
                }
                int tokenEnd = titleStart;
                while (tokenEnd < line.length() && !Character.isWhitespace(line.charAt(tokenEnd))) {
                    tokenEnd++;
                }
                String token = line.substring(titleStart, tokenEnd);
                Keyword kind = null;
                if (token.equals("TODO")) {
                    kind = Keyword.TODO;
                } else if (token.equals("DONE")) {
                    kind = Keyword.DONE;
                }
                if (kind != null) {
                    spans.add(new KeywordSpan(lineStart + titleStart, token.length(), kind));
                }
            }
            if (nl < 0) {
                break;
            }
            lineStart = nl + 1;
        }
        return spans;
    }

    // ----------------------------------------------------- Coloracao de titulos por nivel

    /**
     * Paleta de cores (RGB empacotado em int, 0xRRGGBB) para colorir titulos por nivel: o
     * indice 0 vale para o nivel 1, o indice 1 para o nivel 2, e assim por diante. Niveis
     * mais profundos que a paleta reutilizam a ultima cor. Mantida como int para nao
     * arrastar dependencia de AWT/Swing ao nucleo; a GUI converte via {@code new Color(rgb)}.
     */
    public static final int[] HEADING_COLORS = {
        0x1565C0, // nivel 1 - azul
        0x2E7D32, // nivel 2 - verde
        0xC62828, // nivel 3 - vermelho
        0x6A1B9A, // nivel 4 - roxo
        0xEF6C00, // nivel 5 - laranja
        0x00838F  // nivel 6+ - ciano
    };

    /** Cor (RGB empacotado) para um cabecalho do nivel informado (1-based), saturando na ultima. */
    public static int headingColor(int level) {
        int idx = Math.max(1, level) - 1;
        if (idx >= HEADING_COLORS.length) {
            idx = HEADING_COLORS.length - 1;
        }
        return HEADING_COLORS[idx];
    }

    /**
     * Trecho de linha de cabecalho a colorir: {@code start} e o offset absoluto no texto
     * completo (contando os '\n'), {@code length} o comprimento da linha do cabecalho e
     * {@code level} o nivel (quantidade de marcadores "#"/"*"). Imutavel; consumido pela GUI.
     */
    public static final class HeadingSpan {
        public final int start;
        public final int length;
        public final int level;

        public HeadingSpan(int start, int length, int level) {
            this.start = start;
            this.length = length;
            this.level = level;
        }
    }

    /**
     * Localiza as linhas de cabecalho a colorir por nivel. Para cada cabecalho devolve um
     * span cobrindo a linha inteira (marcador + titulo) com offsets ABSOLUTOS no {@code text}
     * (contando os '\n') e o nivel = quantidade de marcadores. Funcao pura; a GUI aplica a
     * cor de {@link #headingColor(int)} a cada trecho.
     */
    public static List<HeadingSpan> colorizeHeadings(String text) {
        List<HeadingSpan> spans = new ArrayList<>();
        int len = text.length();
        int lineStart = 0;
        while (true) {
            int nl = text.indexOf('\n', lineStart);
            int lineEnd = nl < 0 ? len : nl;
            String line = text.substring(lineStart, lineEnd);
            Matcher m = HEADING.matcher(line);
            if (m.matches()) {
                int level = m.group(1).length(); // numero de marcadores "#"/"*"
                spans.add(new HeadingSpan(lineStart, line.length(), level));
            }
            if (nl < 0) {
                break;
            }
            lineStart = nl + 1;
        }
        return spans;
    }

    // ----------------------------------------------------- Auto-enumeracao de topicos (Ctrl+E)

    /**
     * (Re)numera hierarquicamente todos os cabecalhos do texto, no estilo "1", "1.1", "1.1.1",
     * "1.2", "2"... O numero deriva do nivel (quantidade de marcadores "#"/"*"): cada cabecalho
     * incrementa o contador do seu nivel e zera os niveis mais profundos. Numeros ja existentes
     * no inicio do titulo sao substituidos, tornando a operacao idempotente (re-rodar atualiza,
     * nao empilha). Funcao pura; linhas que nao sao cabecalho ficam intactas.
     */
    public static String autoNumberHeadings(String text) {
        StringBuilder out = new StringBuilder(text.length() + 32);
        List<Integer> counters = new ArrayList<>();
        int i = 0;
        int len = text.length();
        while (true) {
            int nl = text.indexOf('\n', i);
            int end = nl < 0 ? len : nl;
            out.append(renumberHeadingLine(text.substring(i, end), counters));
            if (nl < 0) {
                break;
            }
            out.append('\n');
            i = nl + 1;
        }
        return out.toString();
    }

    /**
     * Reescreve uma linha de cabecalho com o proximo numero hierarquico (atualizando os
     * contadores por nivel); linhas que nao sao cabecalho sao devolvidas sem alteracao.
     */
    private static String renumberHeadingLine(String line, List<Integer> counters) {
        Matcher m = HEADING.matcher(line);
        if (!m.matches()) {
            return line;
        }
        int level = m.group(1).length();

        // m.start(2) e o inicio do titulo (primeiro nao-espaco apos o marcador); o trecho entre
        // os marcadores e ele e o espacamento original, preservado na reconstrucao.
        String markers = line.substring(0, level);
        String gap = line.substring(level, m.start(2));
        String title = m.group(2);

        // Remove um numero de enumeracao pre-existente (so se houver conteudo apos ele).
        Matcher num = LEADING_NUMBER.matcher(title);
        if (num.matches()) {
            title = num.group(2);
        }

        // Atualiza contadores: abre ancestrais implicitos, incrementa o nivel, zera os profundos.
        while (counters.size() < level) {
            counters.add(0);
        }
        for (int k = 0; k < level - 1; k++) {
            if (counters.get(k) == 0) {
                counters.set(k, 1);
            }
        }
        counters.set(level - 1, counters.get(level - 1) + 1);
        for (int j = level; j < counters.size(); j++) {
            counters.set(j, 0);
        }

        StringBuilder label = new StringBuilder();
        for (int k = 0; k < level; k++) {
            if (k > 0) {
                label.append('.');
            }
            label.append(counters.get(k));
        }
        return markers + gap + label + " " + title;
    }

    /**
     * Remove o numero de enumeracao inicial do titulo de todos os cabecalhos, deixando apenas o
     * titulo (e o TODO/DONE, se houver). E a operacao inversa de {@link #autoNumberHeadings}:
     * juntas, formam o toggle de duas etapas do Ctrl+E. Preserva marcadores e o espacamento
     * original; linhas que nao sao cabecalho — ou cujo titulo nao comeca por numero — ficam
     * intactas. Funcao pura.
     */
    public static String removeHeadingNumbers(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        int len = text.length();
        while (true) {
            int nl = text.indexOf('\n', i);
            int end = nl < 0 ? len : nl;
            out.append(stripHeadingNumberLine(text.substring(i, end)));
            if (nl < 0) {
                break;
            }
            out.append('\n');
            i = nl + 1;
        }
        return out.toString();
    }

    /**
     * Reescreve uma linha de cabecalho sem o numero de enumeracao inicial (preservando marcadores
     * e espacamento); linhas que nao sao cabecalho sao devolvidas sem alteracao.
     */
    private static String stripHeadingNumberLine(String line) {
        Matcher m = HEADING.matcher(line);
        if (!m.matches()) {
            return line;
        }
        String markers = line.substring(0, m.group(1).length());
        String gap = line.substring(m.group(1).length(), m.start(2));
        String title = m.group(2);
        Matcher num = LEADING_NUMBER.matcher(title);
        if (num.matches()) {
            title = num.group(2);
        }
        return markers + gap + title;
    }

    /**
     * Indica se existe ao menos um cabecalho cujo titulo comeca por um numero de enumeracao
     * ({@link #LEADING_NUMBER}). E o que decide o "tempo" do toggle do Ctrl+E a partir do proprio
     * conteudo: ja numerado -> remover; ainda nao -> enumerar. Funcao pura.
     */
    public static boolean hasNumberedHeadings(String text) {
        int i = 0;
        int len = text.length();
        while (true) {
            int nl = text.indexOf('\n', i);
            int end = nl < 0 ? len : nl;
            Matcher m = HEADING.matcher(text.substring(i, end));
            if (m.matches() && LEADING_NUMBER.matcher(m.group(2)).matches()) {
                return true;
            }
            if (nl < 0) {
                break;
            }
            i = nl + 1;
        }
        return false;
    }

    /**
     * Inicio da linha do cabecalho de projeto que contem o caret: a linha do proprio
     * caret ou a primeira linha de cabecalho acima dela. Retorna -1 quando o caret nao
     * esta sob nenhum cabecalho (preambulo ou documento sem projetos).
     */
    public static int headingLineStartFor(String text, int caret) {
        caret = Math.max(0, Math.min(caret, text.length()));
        int lineStart = text.lastIndexOf('\n', caret - 1) + 1;
        while (true) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = text.length();
            }
            if (isHeading(text.substring(lineStart, lineEnd))) {
                return lineStart;
            }
            if (lineStart == 0) {
                return -1;
            }
            lineStart = text.lastIndexOf('\n', lineStart - 2) + 1;
        }
    }

    /**
     * Nivel do titulo cuja secao contem a posicao informada: a linha de cabecalho do
     * proprio offset ou o primeiro cabecalho acima dele. Retorna 0 quando a posicao nao
     * esta sob nenhum titulo (preambulo). Funcao pura.
     */
    public static int headingSectionLevel(String text, int position) {
        int headingStart = headingLineStartFor(text, position);
        if (headingStart < 0) {
            return 0;
        }
        int headingEnd = text.indexOf('\n', headingStart);
        if (headingEnd < 0) {
            headingEnd = text.length();
        }
        return headingLevel(text.substring(headingStart, headingEnd));
    }

    // ----------------------------------------------------- Folding (encolher/expandir topicos)

    /** Nivel de um cabecalho (numero de marcadores "#"/"*"), ou 0 se a linha nao for cabecalho. */
    public static int headingLevel(String line) {
        Matcher m = HEADING.matcher(line);
        return m.matches() ? m.group(1).length() : 0;
    }

    /**
     * Regiao dobravel de um topico: o cabecalho sob o caret e a faixa do seu CORPO que a GUI
     * pode esconder/mostrar. {@code headingStart} e o inicio da linha do cabecalho; o corpo a
     * esconder e o intervalo de offsets [bodyStart, bodyEnd). Imutavel.
     */
    public static final class FoldRegion {
        public final int headingStart;
        public final int bodyStart;
        public final int bodyEnd;

        public FoldRegion(int headingStart, int bodyStart, int bodyEnd) {
            this.headingStart = headingStart;
            this.bodyStart = bodyStart;
            this.bodyEnd = bodyEnd;
        }
    }

    /**
     * Calcula a regiao dobravel do topico que contem o caret: a linha de cabecalho do caret
     * (ou o cabecalho acima dele) e o corpo ate o proximo cabecalho de nivel igual ou superior
     * (irmao/ancestral); cabecalhos mais profundos (filhos) fazem parte do corpo. Funcao pura.
     * Retorna {@code null} quando o caret nao esta sob cabecalho algum, quando o cabecalho e a
     * ultima linha (sem '\n', logo sem corpo) ou quando o topico nao tem corpo a esconder.
     */
    public static FoldRegion foldRegionFor(String text, int caret) {
        int headingStart = headingLineStartFor(text, caret);
        if (headingStart < 0) {
            return null;
        }
        int headingEnd = text.indexOf('\n', headingStart);
        if (headingEnd < 0) {
            return null; // cabecalho na ultima linha: nao ha corpo
        }
        int level = headingLevel(text.substring(headingStart, headingEnd));
        int bodyStart = headingEnd + 1;
        int bodyEnd = subtreeEnd(text, bodyStart, level);
        if (bodyStart >= bodyEnd) {
            return null; // topico sem corpo
        }
        return new FoldRegion(headingStart, bodyStart, bodyEnd);
    }

    /**
     * Fim do subtopico iniciado em {@code from}: inicio da primeira linha de cabecalho com
     * nivel <= {@code level} (um irmao ou ancestral), ou {@code text.length()} se nao houver.
     * Cabecalhos de nivel maior (filhos) integram o subtopico e nao o encerram.
     */
    private static int subtreeEnd(String text, int from, int level) {
        int len = text.length();
        int lineStart = from;
        while (lineStart < len) {
            int nl = text.indexOf('\n', lineStart);
            int lineEnd = nl < 0 ? len : nl;
            int lvl = headingLevel(text.substring(lineStart, lineEnd));
            if (lvl > 0 && lvl <= level) {
                return lineStart;
            }
            if (nl < 0) {
                break;
            }
            lineStart = nl + 1;
        }
        return len;
    }

    /**
     * Inicio da proxima linha de cabecalho apos {@code from} (posicao dentro da linha
     * corrente), ou {@code text.length()} se nao houver outra: o fim da secao.
     */
    static int nextHeadingStart(String text, int from) {
        int pos = from;
        while (true) {
            int nl = text.indexOf('\n', pos);
            if (nl < 0) {
                return text.length();
            }
            int lineStart = nl + 1;
            int lineEnd = text.indexOf('\n', lineStart);
            int end = lineEnd < 0 ? text.length() : lineEnd;
            if (isHeading(text.substring(lineStart, end))) {
                return lineStart;
            }
            pos = lineStart;
        }
    }

    /**
     * Logica do Ctrl+I com contexto de projeto (estilo Org-mode): se o caret estiver sob
     * um cabecalho de projeto ("* Nome" ou "# Nome"), o novo registro de entrada e
     * inserido no fim daquela secao (apos a ultima linha nao vazia, antes do proximo
     * cabecalho), e nao no fim do documento. Qualquer tarefa em aberto — em qualquer
     * projeto — e fechada antes com {@code now}, como no clock-in do Org. Sem cabecalho,
     * o comportamento e o legado de {@link #insertClockLine(String, Calendar)}.
     */
    public static TextEdit insertClockLine(String text, int caret, Calendar now) {
        int headingStart = headingLineStartFor(text, caret);
        if (headingStart < 0) {
            return insertClockLine(text, now);
        }

        CloseResult close = closeOpenClock(text, now);
        String content = text;
        if (close.closed()) {
            // O fechamento edita uma unica linha; se ela estiver antes do cabecalho,
            // o deslocamento do texto precisa ser aplicado ao indice do cabecalho.
            if (close.lineStart < headingStart) {
                headingStart += close.text.length() - text.length();
            }
            content = close.text;
        }

        int headingLineEnd = content.indexOf('\n', headingStart);
        if (headingLineEnd < 0) {
            headingLineEnd = content.length();
        }
        int sectionEnd = nextHeadingStart(content, headingLineEnd);

        // Se a secao ja tiver um drawer, o novo registro entra no topo dele.
        int logbookLineStart = findLogbookOpenLine(content, headingLineEnd, sectionEnd);
        if (logbookLineStart >= 0) {
            return insertIntoExistingDrawer(content, logbookLineStart, now);
        }

        // Ponto de insercao: fim da ultima linha nao vazia da secao (preserva as linhas
        // em branco que separam a proxima secao); secao vazia insere logo apos o titulo.
        int insertAt = headingLineEnd;
        for (int i = sectionEnd - 1; i > headingLineEnd; i--) {
            if (!Character.isWhitespace(content.charAt(i))) {
                int nl = content.indexOf('\n', i);
                insertAt = nl < 0 ? content.length() : nl;
                break;
            }
        }

        // Sem drawer na secao: cria um novo nesse ponto de insercao.
        return createDrawerAt(content, insertAt, now);
    }

    /**
     * No da arvore de secoes do relatorio. Cada no guarda o tempo PROPRIO (clocks
     * fechados diretamente sob ele) e a contagem de clocks em aberto proprios; o tempo
     * cumulativo (proprio + descendentes) e calculado depois em {@link #accumulate(Node)}.
     */
    private static final class Node {
        final int level;          // comprimento do grupo de marcadores (1=topo); 0 = "(sem projeto)"
        final String title;
        final List<Node> children = new ArrayList<>();
        long ownMs;               // tempo proprio (clocks fechados diretamente sob este no)
        int ownOpen;              // clocks em aberto proprios
        long cumMs;               // tempo cumulativo (proprio + descendentes)
        boolean cumOpen;          // ha algum clock em aberto na subarvore?

        Node(int level, String title) {
            this.level = level;
            this.title = title;
        }
    }

    /** Calcula recursivamente o tempo cumulativo e o estado "em andamento" da subarvore. */
    private static void accumulate(Node n) {
        n.cumMs = n.ownMs;
        n.cumOpen = n.ownOpen > 0;
        for (Node c : n.children) {
            accumulate(c);
            n.cumMs += c.cumMs;
            n.cumOpen = n.cumOpen || c.cumOpen;
        }
    }

    /**
     * Acumula a duracao de um registro CLOCK fechado da {@code line} no tempo proprio do
     * no {@code target}: recalcula a partir dos horarios de entrada/saida e, se forem
     * ilegiveis, cai na duracao "h:mm" ja gravada.
     */
    private static void addClock(Node target, String line, int idx) {
        Matcher m = CLOSED_CLOCK.matcher(line);
        if (m.find()) {
            Date entrada = parseClockInner(m.group(1));
            Date saida = parseClockInner(m.group(2));
            if (entrada != null && saida != null) {
                target.ownMs += Math.max(0, saida.getTime() - entrada.getTime());
            } else {
                // Horarios ilegiveis: aproveita a duracao "h:mm" ja gravada.
                String[] hm = m.group(3).split(":");
                target.ownMs += (Long.parseLong(hm[0]) * 60 + Long.parseLong(hm[1])) * 60_000L;
            }
        } else if (line.indexOf(']', idx) >= 0) {
            target.ownOpen++; // registro em aberto (com '[...]' mas sem saida)
        }
    }

    /** Linha pronta do relatorio: rotulo ja indentado, tempo a exibir e estado aberto. */
    private static final class Row {
        final String label;
        final long millis;
        final boolean open;

        Row(String label, long millis, boolean open) {
            this.label = label;
            this.millis = millis;
            this.open = open;
        }
    }

    /**
     * Percorre a arvore em pre-ordem (ordem do documento), indentando cada nivel com dois
     * espacos por profundidade. Quando {@code cumulative}, cada linha exibe o tempo/estado
     * CUMULATIVO da subarvore (preenchido por {@link #accumulate(Node)}); caso contrario,
     * exibe apenas o tempo/estado PROPRIO do no.
     */
    private static void flatten(Node n, int depth, boolean cumulative, List<Row> rows) {
        StringBuilder label = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            label.append("  ");
        }
        label.append(n.title);
        long millis = cumulative ? n.cumMs : n.ownMs;
        boolean open = cumulative ? n.cumOpen : n.ownOpen > 0;
        rows.add(new Row(label.toString(), millis, open));
        for (Node c : n.children) {
            flatten(c, depth + 1, cumulative, rows);
        }
    }

    /**
     * Monta a arvore de secoes do documento pelo NIVEL do cabecalho (comprimento dos
     * marcadores): cada secao vira um {@link Node} com seu tempo PROPRIO e abertos proprios,
     * aninhado sob a secao mais rasa que o contem. Registros antes do primeiro cabecalho vao
     * para um no "(sem projeto)" de nivel 0, inserido como primeira raiz. Devolve as raizes
     * na ordem de aparicao (lista vazia se nao houver nenhum registro CLOCK). Esta arvore e a
     * representacao unica compartilhada por {@link #clockReport} e {@link #clockReportIndented}.
     */
    private static List<Node> buildSectionTree(String text) {
        // roots preserva a ordem de aparicao das secoes de topo no documento.
        List<Node> roots = new ArrayList<>();
        // stack: ancestrais da secao corrente, do topo (mais profundo) para a raiz.
        Deque<Node> stack = new ArrayDeque<>();
        Node semProjeto = null;   // bucket dos clocks antes do primeiro cabecalho
        Node current = null;      // secao corrente onde os clocks sao somados

        for (String line : text.split("\n", -1)) {
            Matcher h = HEADING.matcher(line);
            if (h.matches()) {
                int level = h.group(1).length(); // o comprimento dos marcadores e o nivel
                Node node = new Node(level, h.group(2).trim());
                // Desempilha ancestrais de nivel >= ao novo (irmaos e secoes mais profundas):
                // a nova secao "fecha" tudo que nao a contem.
                while (!stack.isEmpty() && stack.peek().level >= level) {
                    stack.pop();
                }
                if (stack.isEmpty()) {
                    roots.add(node);
                } else {
                    stack.peek().children.add(node); // filho da secao mais rasa que a contem
                }
                stack.push(node);
                current = node;
                continue;
            }

            int idx = line.indexOf(CLOCK_OPEN);
            if (idx < 0) {
                continue;
            }
            Node target = current;
            if (target == null) {
                if (semProjeto == null) {
                    semProjeto = new Node(0, "(sem projeto)");
                    roots.add(0, semProjeto); // preambulo aparece antes dos cabecalhos
                }
                target = semProjeto;
            }
            addClock(target, line, idx);
        }
        return roots;
    }

    /** Renderiza as linhas do relatorio com a coluna de duracao alinhada e a linha "Total". */
    private static String renderReport(List<Row> rows, long grandTotal) {
        int nameWidth = "Total".length();
        for (Row row : rows) {
            nameWidth = Math.max(nameWidth, row.label.length());
        }

        StringBuilder sb = new StringBuilder("Tempo por projeto:\n\n");
        for (Row row : rows) {
            sb.append(String.format("  %-" + nameWidth + "s  %6s", row.label, formatDuration(row.millis)));
            if (row.open) {
                sb.append("  (em andamento)");
            }
            sb.append('\n');
        }
        sb.append(String.format("  %-" + nameWidth + "s  %6s", "Total", formatDuration(grandTotal))).append('\n');
        return sb.toString();
    }

    /**
     * Relatorio de tempo por projeto (estilo org-clock-report): monta a hierarquia das
     * secoes pelo NIVEL do cabecalho e exibe, para cada secao, o tempo CUMULATIVO = tempo
     * proprio (clocks fechados diretamente sob ela) MAIS os tempos de todas as suas subsecoes
     * descendentes. As duracoes sao recalculadas a partir dos horarios de entrada/saida (cai
     * na duracao gravada se forem ilegiveis). Registros antes do primeiro cabecalho entram em
     * "(sem projeto)"; uma secao e marcada "(em andamento)" quando ha algum registro em aberto
     * na sua subarvore.
     */
    public static String clockReport(String text) {
        List<Node> roots = buildSectionTree(text);
        if (roots.isEmpty()) {
            return "Nenhum registro CLOCK encontrado.";
        }
        // Calcula os totais cumulativos e achata a arvore em pre-ordem (ordem do documento).
        List<Row> rows = new ArrayList<>();
        long grandTotal = 0;
        for (Node root : roots) {
            accumulate(root);
            grandTotal += root.cumMs; // soma so as raizes: cada uma ja inclui seus descendentes
            flatten(root, 0, true, rows);
        }
        return renderReport(rows, grandTotal);
    }

    /**
     * Relatorio de tempo HIERARQUICO, pensado para ser inserido no proprio documento (ao
     * contrario de {@link #clockReport(String)}, que monta o texto para um dialogo). Reusa a
     * MESMA arvore de secoes de {@link #buildSectionTree(String)}, mas cada linha exibe apenas
     * o tempo PROPRIO da secao (sem somar subsecoes); a indentacao reflete a profundidade na
     * hierarquia (dois espacos por nivel, no estilo de uma org-clock-report aninhada) e as
     * duracoes ficam alinhadas a direita. Registros antes do primeiro cabecalho entram em
     * "(sem projeto)"; registros em aberto nao somam tempo, mas marcam a secao com
     * "(em andamento)". Funcao pura.
     */
    public static String clockReportIndented(String text) {
        List<Node> roots = buildSectionTree(text);
        if (roots.isEmpty()) {
            return "Nenhum registro CLOCK encontrado.";
        }
        // Achata exibindo o tempo proprio; o Total soma o tempo proprio de todas as secoes.
        List<Row> rows = new ArrayList<>();
        for (Node root : roots) {
            flatten(root, 0, false, rows);
        }
        long grandTotal = 0;
        for (Row row : rows) {
            grandTotal += row.millis;
        }
        return renderReport(rows, grandTotal);
    }

    /** Padrao de um registro CLOCK fechado: grupo1=entrada, grupo2=saida, grupo3=duracao atual. */
    static final Pattern CLOSED_CLOCK =
            Pattern.compile("CLOCK: \\[([^\\]]*)\\]--\\[([^\\]]*)\\] =>  +(\\d+:\\d{2})");

    /**
     * Recalcula a duracao de TODOS os registros CLOCK fechados a partir dos horarios de
     * entrada e saida. Registros em aberto nao casam o padrao e sao ignorados; quando um
     * horario nao puder ser interpretado, o registro e mantido como esta. Funcao pura.
     */
    public static String recalculateDurations(String text) {
        Matcher m = CLOSED_CLOCK.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String g1 = m.group(1);
            String g2 = m.group(2);
            Date entrada = parseClockInner(g1);
            Date saida = parseClockInner(g2);
            String replacement;
            if (entrada != null && saida != null) {
                String novaDuracao = formatDuration(saida.getTime() - entrada.getTime());
                replacement = "CLOCK: [" + g1 + "]--[" + g2 + "] =>  " + novaDuracao;
            } else {
                replacement = m.group(0);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ----------------------------------------------------- Ajuste de horario sob o cursor

    /** Horario "HH:mm": dois digitos de hora, ':' e dois digitos de minuto. */
    static final Pattern TIME_FIELD = Pattern.compile("\\d{2}:\\d{2}");

    /**
     * Ajusta em {@code delta} a HORA ou o MINUTO do horario "HH:mm" sob o caret (Ctrl+Up = +1,
     * Ctrl+Down = -1). Localiza o "HH:mm" cujos digitos contem ou encostam o caret (a regiao
     * {@code [inicio, fim]}, inclusive nas duas pontas). O campo afetado depende da posicao
     * relativa ao ':': {@code caret <= indice do ':'} ajusta a HORA, {@code caret >} ajusta o
     * MINUTO — ou seja, o caret encostado a esquerda do ':' (logo apos "HH") ainda conta como
     * hora, e logo apos o ':' (antes de "mm") conta como minuto. O wrap fica DENTRO do proprio
     * campo, sem propagar "vai-um": hora 23->00 e 00->23; minuto 59->00 e 00->59. Apenas os dois
     * digitos do campo sao reescritos (com zero a esquerda), preservando o resto da linha; o
     * cursor permanece na mesma posicao (o campo tem largura fixa de 5 caracteres). Retorna
     * {@code null} (no-op) quando o caret nao esta sobre nenhum "HH:mm". Funcao pura.
     */
    public static TextEdit adjustTimeField(String text, int caret, int delta) {
        if (text == null) {
            return null;
        }
        caret = Math.max(0, Math.min(caret, text.length()));
        Matcher m = TIME_FIELD.matcher(text);
        while (m.find()) {
            int start = m.start(); // primeiro digito da hora
            int end = m.end();     // logo apos o ultimo digito do minuto (start + 5)
            if (caret < start || caret > end) {
                continue;
            }
            int colon = start + 2; // indice do ':'
            int hour = Integer.parseInt(text.substring(start, start + 2));
            int minute = Integer.parseInt(text.substring(start + 3, start + 5));
            if (caret <= colon) {
                hour = ((hour + delta) % 24 + 24) % 24;
            } else {
                minute = ((minute + delta) % 60 + 60) % 60;
            }
            String replacement = String.format("%02d:%02d", hour, minute);
            String updated = text.substring(0, start) + replacement + text.substring(end);
            return new TextEdit(updated, caret);
        }
        return null;
    }

    // ----------------------------------------------------- Ajuste de data sob o cursor

    /** Token de data "yyyy-MM-dd ddd": data ISO seguida da abreviacao do dia da semana (pt). */
    static final Pattern DATE_FIELD =
            Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2}) (dom|seg|ter|qua|qui|sex|sab)");

    /**
     * Desloca em {@code delta} dias a DATA do token "yyyy-MM-dd ddd" sob o caret (Ctrl+Up = +1,
     * Ctrl+Down = -1). Localiza o token cujos caracteres contem ou encostam o caret (regiao
     * {@code [inicio, fim]}, inclusive nas duas pontas), atualizando JUNTOS o "yyyy-MM-dd" e a
     * abreviacao do dia da semana (recomputada via {@link #weekdayPt(int)}). O token tem largura
     * fixa (10 + 1 + 3 = 14 caracteres), entao o cursor permanece na mesma posicao. Apos o ajuste,
     * recalcula as duracoes ({@link #recalculateDurations}) para manter as horas relativas
     * consistentes. A regiao da data nao se sobrepoe a do horario "HH:mm" (separados por um espaco),
     * de modo que {@link #adjustTimeField} continua tratando o "HH:mm". Retorna {@code null} (no-op)
     * quando o caret nao esta sobre uma data/dia da semana. Funcao pura.
     */
    public static TextEdit adjustDateField(String text, int caret, int delta) {
        if (text == null) {
            return null;
        }
        caret = Math.max(0, Math.min(caret, text.length()));
        Matcher m = DATE_FIELD.matcher(text);
        while (m.find()) {
            int start = m.start();
            int end = m.end();
            if (caret < start || caret > end) {
                continue;
            }
            Calendar cal = Calendar.getInstance();
            cal.clear();
            cal.set(Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)) - 1,
                    Integer.parseInt(m.group(3)));
            cal.add(Calendar.DAY_OF_MONTH, delta);
            String replacement = formatDay(cal); // "yyyy-MM-dd ddd" com o dia da semana recomputado
            String updated = text.substring(0, start) + replacement + text.substring(end);
            return new TextEdit(recalculateDurations(updated), caret);
        }
        return null;
    }

    // ----------------------------------------------------- Coffee (pausas de cafe)
    //
    // O comando "Coffee" (Ctrl+Shift+Alt+C) registra uma pausa de cafe no FINAL do
    // documento, sob uma secao "# Coffee" criada apenas uma vez. Dentro dela, os
    // registros sao agrupados por dia em subtopicos "## yyyy-MM-dd ddd"; cada
    // pressionamento gera uma unica linha "- HH:mm". O primeiro registro de um dia cria
    // o subtopico; os seguintes apenas acrescentam a linha sob ele.

    /** Cabecalho (nivel 1) da secao de pausas de cafe. */
    public static final String COFFEE_HEADING = "# Coffee";

    /** Formata um instante como "yyyy-MM-dd ddd" (dia, sem hora), reusando {@link #weekdayPt(int)}. */
    public static String formatDay(Calendar cal) {
        String date = new SimpleDateFormat("yyyy-MM-dd").format(cal.getTime());
        String weekday = weekdayPt(cal.get(Calendar.DAY_OF_WEEK));
        return date + " " + weekday;
    }

    /**
     * Inicio da linha do cabecalho "# Coffee" (titulo "Coffee", em qualquer nivel de
     * marcadores), ou -1 se a secao ainda nao existir.
     */
    private static int findCoffeeHeading(String text) {
        int lineStart = 0;
        // Termina sempre pelo break interno (nl < 0 na ultima linha): "while (true)" evita um
        // ramo-falso inalcancavel na condicao do laco.
        while (true) {
            int nl = text.indexOf('\n', lineStart);
            int lineEnd = nl < 0 ? text.length() : nl;
            if ("Coffee".equals(headingTitle(text.substring(lineStart, lineEnd)))) {
                return lineStart;
            }
            if (nl < 0) {
                break;
            }
            lineStart = nl + 1;
        }
        return -1;
    }

    /**
     * Inicio da proxima linha de cabecalho de NIVEL 1 (um unico marcador "#"/"*") apos
     * {@code from}, ou {@code text.length()} se nao houver outra: o fim da secao de cafe.
     * Subtopicos de dia ("## ...") tem nivel 2 e nao encerram a secao.
     */
    private static int nextTopHeadingStart(String text, int from) {
        int pos = from;
        while (true) {
            int nl = text.indexOf('\n', pos);
            if (nl < 0) {
                return text.length();
            }
            int lineStart = nl + 1;
            int lineEnd = text.indexOf('\n', lineStart);
            int end = lineEnd < 0 ? text.length() : lineEnd;
            Matcher m = HEADING.matcher(text.substring(lineStart, end));
            if (m.matches() && m.group(1).length() == 1) {
                return lineStart;
            }
            pos = lineStart;
        }
    }

    /** Inicio da primeira linha cujo conteudo (apos trim) e {@code target}, em [from, to); -1 se ausente. */
    private static int findExactLine(String text, String target, int from, int to) {
        int lineStart = from;
        while (lineStart < to) {
            int nl = text.indexOf('\n', lineStart);
            int lineEnd = nl < 0 ? text.length() : nl;
            if (text.substring(lineStart, lineEnd).trim().equals(target)) {
                return lineStart;
            }
            if (nl < 0) {
                break;
            }
            lineStart = nl + 1;
        }
        return -1;
    }

    /**
     * Posicao logo apos a ultima linha NAO vazia da regiao [from, to): o fim dessa linha
     * (antes da sua quebra de linha), de modo que uma insercao ali preserve as linhas em
     * branco que separam a regiao seguinte. Se a regiao for so espacos, devolve {@code from}.
     */
    private static int lastContentLineEnd(String text, int from, int to) {
        for (int i = to - 1; i >= from; i--) {
            if (!Character.isWhitespace(text.charAt(i))) {
                int nl = text.indexOf('\n', i);
                // A quebra apos o ultimo conteudo nunca passa de "to" (sempre inicio de linha ou
                // fim do texto); basta tratar a ausencia de quebra (conteudo ate o fim da regiao).
                return nl < 0 ? to : nl;
            }
        }
        return from;
    }

    /**
     * Registra uma pausa de cafe para {@code now} no fim da secao "# Coffee" (criada se
     * ausente), agrupada no subtopico do dia "## yyyy-MM-dd ddd" (criado apenas no primeiro
     * registro do dia). Cada chamada acrescenta exatamente uma linha "- HH:mm". Funcao pura:
     * recebe o {@code now} por parametro e devolve o texto atualizado e o cursor (logo apos
     * a linha inserida).
     */
    public static TextEdit registerCoffee(String text, Calendar now) {
        String dayLine = "## " + formatDay(now);
        String timeLine = "- " + new SimpleDateFormat("HH:mm").format(now.getTime());

        int coffeeStart = findCoffeeHeading(text);
        if (coffeeStart < 0) {
            // Secao ainda nao existe: cria no fim do documento (na sua propria linha).
            String prefix = (text.isEmpty() || text.endsWith("\n")) ? "" : "\n";
            String head = text + prefix + COFFEE_HEADING + "\n" + dayLine + "\n" + timeLine;
            return new TextEdit(head + "\n", head.length());
        }

        int coffeeLineEnd = text.indexOf('\n', coffeeStart);
        if (coffeeLineEnd < 0) {
            coffeeLineEnd = text.length();
        }
        int sectionEnd = nextTopHeadingStart(text, coffeeLineEnd);

        int dayStart = findExactLine(text, dayLine, coffeeLineEnd, sectionEnd);
        if (dayStart >= 0) {
            // Subtopico do dia ja existe: acrescenta a linha no fim dele.
            int dayLineEnd = text.indexOf('\n', dayStart);
            if (dayLineEnd < 0) {
                dayLineEnd = text.length();
            }
            int subEnd = Math.min(nextHeadingStart(text, dayLineEnd), sectionEnd);
            int insertAt = lastContentLineEnd(text, dayLineEnd, subEnd);
            String ins = "\n" + timeLine;
            return new TextEdit(text.substring(0, insertAt) + ins + text.substring(insertAt),
                    insertAt + ins.length());
        }

        // Secao existe, mas e o primeiro registro do dia: cria o subtopico no fim da secao.
        int insertAt = lastContentLineEnd(text, coffeeLineEnd, sectionEnd);
        String ins = "\n" + dayLine + "\n" + timeLine;
        return new TextEdit(text.substring(0, insertAt) + ins + text.substring(insertAt),
                insertAt + ins.length());
    }

    // ----------------------------------------------------- Encerramento (decisao)

    /** Escolha do usuario no dialogo de "salvar antes de sair". */
    public enum ExitChoice {
        SAVE,     // YES: salvar e sair
        DISCARD,  // NO: sair sem salvar
        CANCEL    // CANCEL/fechado: abortar
    }

    /** Plano de acao resultante para o encerramento. */
    public enum ExitPlan {
        EXIT_NOW,        // pode encerrar imediatamente
        SAVE_THEN_EXIT,  // salvar primeiro; so encerra se o salvamento concluir
        ABORT            // nao encerra
    }

    /**
     * Decide o que fazer ao tentar encerrar, dado se ha alteracoes nao salvas e a escolha
     * do usuario. Sem alteracoes, encerra direto (a escolha e irrelevante). Com alteracoes:
     * SAVE -> salvar e entao encerrar; DISCARD -> encerrar; CANCEL -> abortar.
     */
    public static ExitPlan planExit(boolean modified, ExitChoice choice) {
        if (!modified) {
            return ExitPlan.EXIT_NOW;
        }
        switch (choice) {
            case SAVE:
                return ExitPlan.SAVE_THEN_EXIT;
            case DISCARD:
                return ExitPlan.EXIT_NOW;
            default: // CANCEL
                return ExitPlan.ABORT;
        }
    }

    // ----------------------------------------------------- XDG / pasta de documentos

    /** Expande "$HOME" / "~" no inicio de um caminho XDG e retorna o File correspondente. */
    public static File expandXdg(String value, String home) {
        if (value == null) {
            return null;
        }
        value = value.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.startsWith("$HOME")) {
            value = home + value.substring("$HOME".length());
        } else if (value.startsWith("~")) {
            value = home + value.substring(1);
        }
        return new File(value);
    }

    /**
     * Extrai o valor (sem aspas) de XDG_DOCUMENTS_DIR a partir do conteudo de um arquivo
     * user-dirs.dirs. Retorna null se a chave nao estiver presente.
     */
    public static String extractXdgDocumentsValue(String fileContent) {
        if (fileContent == null) {
            return null;
        }
        for (String raw : fileContent.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("XDG_DOCUMENTS_DIR")) {
                int eq = line.indexOf('=');
                if (eq >= 0) {
                    String value = line.substring(eq + 1).trim();
                    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                        value = value.substring(1, value.length() - 1);
                    }
                    return value;
                }
            }
        }
        return null;
    }

    /**
     * Resolve a pasta de documentos para Windows/macOS/fallback: testa variantes
     * localizadas e cai em "Documents". Para Linux, a resolucao XDG fica em
     * {@link TimeTakerApp} (depende de processo externo); este metodo cobre o fallback.
     */
    public static File documentsFallback(String home) {
        String[] candidates = {"Documents", "Documentos", "Document", "Documento"};
        for (String name : candidates) {
            File f = new File(home, name);
            if (f.isDirectory()) {
                return f;
            }
        }
        return new File(home, "Documents");
    }

    // ----------------------------------------------------- Configuracoes

    /** Conjunto de preferencias persistidas em timetaker.properties. */
    public static final class Settings {
        public String fontName;
        public int fontSize;
        public String defaultDir; // caminho absoluto
        public int winWidth;
        public int winHeight;
        public int winX;
        public int winY;
        public String lastFile; // caminho absoluto do ultimo arquivo aberto (pode ser null)
        public boolean showHidden; // mostrar arquivos/pastas ocultos nos dialogos de arquivo
        public boolean colorizeHeadings; // colorir titulos com uma cor diferente por nivel
        public int indentSpaces; // espacos de indentacao visual por nivel de titulo (0 = desligado)

        public Settings(String fontName, int fontSize, String defaultDir,
                        int winWidth, int winHeight, int winX, int winY,
                        String lastFile, boolean showHidden, boolean colorizeHeadings,
                        int indentSpaces) {
            this.fontName = fontName;
            this.fontSize = fontSize;
            this.defaultDir = defaultDir;
            this.winWidth = winWidth;
            this.winHeight = winHeight;
            this.winX = winX;
            this.winY = winY;
            this.lastFile = lastFile;
            this.showHidden = showHidden;
            this.colorizeHeadings = colorizeHeadings;
            this.indentSpaces = indentSpaces;
        }
    }

    public static int parseIntOr(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /**
     * Le as configuracoes do arquivo {@code cfg} sobre uma base de {@code defaults}.
     * Se o arquivo nao existir ou nao puder ser lido, devolve os defaults inalterados.
     * Largura/altura recebem um minimo de seguranca (200x150). A pasta padrao so e
     * aceita se existir em disco.
     */
    public static Settings loadSettings(File cfg, Settings defaults) {
        if (cfg == null || !cfg.isFile()) {
            return defaults;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(cfg.toPath())) {
            p.load(in);
        } catch (IOException ex) {
            return defaults; // mantem os padroes
        }

        defaults.fontName = p.getProperty("font.name", defaults.fontName);
        defaults.fontSize = parseIntOr(p.getProperty("font.size"), defaults.fontSize);
        String dir = p.getProperty("default.dir");
        if (dir != null && new File(dir).isDirectory()) {
            defaults.defaultDir = dir;
        }
        defaults.winWidth = Math.max(200, parseIntOr(p.getProperty("window.width"), defaults.winWidth));
        defaults.winHeight = Math.max(150, parseIntOr(p.getProperty("window.height"), defaults.winHeight));
        defaults.winX = parseIntOr(p.getProperty("window.x"), defaults.winX);
        defaults.winY = parseIntOr(p.getProperty("window.y"), defaults.winY);
        defaults.lastFile = p.getProperty("last.file", defaults.lastFile);
        defaults.showHidden = Boolean.parseBoolean(
                p.getProperty("show.hidden", String.valueOf(defaults.showHidden)));
        defaults.colorizeHeadings = Boolean.parseBoolean(
                p.getProperty("colorize.headings", String.valueOf(defaults.colorizeHeadings)));
        defaults.indentSpaces = Math.max(0, Math.min(32,
                parseIntOr(p.getProperty("indent.spaces"), defaults.indentSpaces)));
        return defaults;
    }

    /** Grava as configuracoes em {@code cfg} no formato Properties. */
    public static void saveSettings(File cfg, Settings s) throws IOException {
        Properties p = new Properties();
        p.setProperty("font.name", s.fontName);
        p.setProperty("font.size", String.valueOf(s.fontSize));
        p.setProperty("default.dir", s.defaultDir);
        p.setProperty("window.width", String.valueOf(s.winWidth));
        p.setProperty("window.height", String.valueOf(s.winHeight));
        p.setProperty("window.x", String.valueOf(s.winX));
        p.setProperty("window.y", String.valueOf(s.winY));
        if (s.lastFile != null) {
            p.setProperty("last.file", s.lastFile);
        }
        p.setProperty("show.hidden", String.valueOf(s.showHidden));
        p.setProperty("colorize.headings", String.valueOf(s.colorizeHeadings));
        p.setProperty("indent.spaces", String.valueOf(s.indentSpaces));
        try (OutputStream out = Files.newOutputStream(cfg.toPath())) {
            p.store(out, "Configuracoes do TimeTaker");
        }
    }

    // ----------------------------------------------------- I/O de arquivo (UTF-8)

    public static String readFile(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    public static void writeFile(File f, String content) throws IOException {
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    /** Nome do arquivo diario do dia informado: "yyyy-MM-dd.md". */
    public static String dailyFileName(Date day) {
        return new SimpleDateFormat("yyyy-MM-dd").format(day) + ".md";
    }
}
