package com.timetaker;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
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

        // Garante que o novo registro comece numa linha nova.
        if (content.length() > 0 && !content.endsWith("\n")) {
            content = content + "\n";
        }
        String clock = "CLOCK: [" + formatClockStamp(now) + "] ";
        String result = content + clock;
        return new TextEdit(result, result.length());
    }

    // ----------------------------------------------------- Projetos (estilo Org-mode)

    /**
     * Cabecalho de projeto: linha iniciada por "*" (Org-mode) ou "#" (markdown), em
     * qualquer nivel, seguidos de espaco e um titulo nao vazio. Grupo 2 = titulo.
     */
    static final Pattern HEADING = Pattern.compile("^(\\*+|#+)\\s+(\\S.*)$");

    /** Indica se a linha e um cabecalho de projeto. */
    public static boolean isHeading(String line) {
        return HEADING.matcher(line).matches();
    }

    /** Titulo (sem marcadores) de uma linha de cabecalho, ou null se nao for cabecalho. */
    public static String headingTitle(String line) {
        Matcher m = HEADING.matcher(line);
        return m.matches() ? m.group(2).trim() : null;
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

        String clock = "CLOCK: [" + formatClockStamp(now) + "] ";
        String updated = content.substring(0, insertAt) + "\n" + clock + content.substring(insertAt);
        return new TextEdit(updated, insertAt + 1 + clock.length());
    }

    /**
     * Relatorio de tempo por projeto (estilo org-clock-report): soma as duracoes dos
     * registros CLOCK fechados de cada secao de projeto, recalculadas a partir dos
     * horarios de entrada/saida (cai na duracao gravada se os horarios nao puderem ser
     * interpretados). Registros antes do primeiro cabecalho entram em "(sem projeto)";
     * registros em aberto nao somam tempo, mas sao indicados como "em andamento".
     */
    public static String clockReport(String text) {
        // LinkedHashMap preserva a ordem de aparicao dos projetos no documento.
        LinkedHashMap<String, long[]> totals = new LinkedHashMap<>();
        String project = "(sem projeto)";

        for (String line : text.split("\n", -1)) {
            String title = headingTitle(line);
            if (title != null) {
                project = title;
                totals.putIfAbsent(project, new long[2]); // projeto aparece mesmo sem registros
                continue;
            }

            int idx = line.indexOf(CLOCK_OPEN);
            if (idx < 0) {
                continue;
            }
            long[] acc = totals.computeIfAbsent(project, k -> new long[2]);

            Matcher m = CLOSED_CLOCK.matcher(line);
            if (m.find()) {
                Date entrada = parseClockInner(m.group(1));
                Date saida = parseClockInner(m.group(2));
                if (entrada != null && saida != null) {
                    acc[0] += Math.max(0, saida.getTime() - entrada.getTime());
                } else {
                    // Horarios ilegiveis: aproveita a duracao "h:mm" ja gravada.
                    String[] hm = m.group(3).split(":");
                    acc[0] += (Long.parseLong(hm[0]) * 60 + Long.parseLong(hm[1])) * 60_000L;
                }
            } else if (line.indexOf(']', idx) >= 0) {
                acc[1]++; // registro em aberto (com '[...]' mas sem saida)
            }
        }

        if (totals.isEmpty()) {
            return "Nenhum registro CLOCK encontrado.";
        }

        int nameWidth = "Total".length();
        for (String name : totals.keySet()) {
            nameWidth = Math.max(nameWidth, name.length());
        }

        StringBuilder sb = new StringBuilder("Tempo por projeto:\n\n");
        long grandTotal = 0;
        for (Map.Entry<String, long[]> e : totals.entrySet()) {
            grandTotal += e.getValue()[0];
            sb.append(String.format("  %-" + nameWidth + "s  %6s", e.getKey(),
                    formatDuration(e.getValue()[0])));
            if (e.getValue()[1] > 0) {
                sb.append("  (em andamento)");
            }
            sb.append('\n');
        }
        sb.append(String.format("  %-" + nameWidth + "s  %6s", "Total", formatDuration(grandTotal))).append('\n');
        return sb.toString();
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

        public Settings(String fontName, int fontSize, String defaultDir,
                        int winWidth, int winHeight, int winX, int winY) {
            this.fontName = fontName;
            this.fontSize = fontSize;
            this.defaultDir = defaultDir;
            this.winWidth = winWidth;
            this.winHeight = winHeight;
            this.winX = winX;
            this.winY = winY;
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
