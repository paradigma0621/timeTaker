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

    /** Resultado de fechar um CLOCK: situacao e (quando CLOSED) o novo texto e cursor. */
    public static final class CloseResult {
        public final CloseStatus status;
        public final String text;
        public final int caret;

        public CloseResult(CloseStatus status, String text, int caret) {
            this.status = status;
            this.text = text;
            this.caret = caret;
        }

        public boolean closed() {
            return status == CloseStatus.CLOSED;
        }
    }

    private static final String CLOCK_OPEN = "CLOCK: [";

    /**
     * Fecha o ultimo registro CLOCK em aberto, anexando "--[saida] =>  h:mm" logo apos o
     * horario de entrada. A descricao digitada apos o registro (se houver) permanece na
     * mesma linha, reposicionada depois da duracao; a quebra de linha vai para o fim da
     * linha. Funcao pura: nao altera nada externo. Quando o status e CLOSED, {@code text}
     * traz o documento atualizado e {@code caret} o inicio da linha seguinte. Nos demais
     * status, {@code text} e o original e {@code caret} = -1.
     */
    public static CloseResult closeOpenClock(String text, Calendar now) {
        int start = text.lastIndexOf(CLOCK_OPEN);
        if (start < 0) {
            return new CloseResult(CloseStatus.NO_CLOCK, text, -1);
        }

        int openBracket = start + CLOCK_OPEN.length() - 1; // indice do '['
        int closeBracket = text.indexOf(']', start);
        if (closeBracket < 0) {
            return new CloseResult(CloseStatus.MALFORMED, text, -1);
        }

        // Ja existe horario de saida anexado?
        if (text.startsWith("--[", closeBracket + 1)) {
            return new CloseResult(CloseStatus.ALREADY_CLOSED, text, -1);
        }

        String inner = text.substring(openBracket + 1, closeBracket).trim();
        Date entryTime = parseClockInner(inner);
        if (entryTime == null) {
            return new CloseResult(CloseStatus.UNPARSEABLE, text, -1);
        }

        String exitStamp = formatClockStamp(now);
        String diff = formatDuration(now.getTimeInMillis() - entryTime.getTime());
        String insertion = "--[" + exitStamp + "] =>  " + diff;

        // A descricao digitada apos o ']' de entrada fica na mesma linha: e reposicionada
        // depois da duracao, e a quebra de linha vai para o fim da linha do registro.
        int lineEnd = text.indexOf('\n', closeBracket);
        boolean hasNewline = lineEnd >= 0;
        if (!hasNewline) {
            lineEnd = text.length();
        }
        String description = text.substring(closeBracket + 1, lineEnd).trim();
        if (!description.isEmpty()) {
            description = " " + description;
        }
        String line = text.substring(0, closeBracket + 1) + insertion + description;
        String updated = line + "\n" + (hasNewline ? text.substring(lineEnd + 1) : "");
        int caret = line.length() + 1; // inicio da linha seguinte
        return new CloseResult(CloseStatus.CLOSED, updated, caret);
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
