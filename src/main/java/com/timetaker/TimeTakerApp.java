package com.timetaker;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.undo.UndoManager;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.CannotRedoException;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Locale;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TimeTaker - editor de markdown minimalista voltado a registro de tempo (clock-in).
 *
 * GUI: Java Swing (Java 8). Escolhido por trazer JMenuBar / JTextArea / JScrollPane
 * e aceleradores de teclado prontos, mantendo a aplicacao leve e rapida no Windows 11.
 */
public class TimeTakerApp extends JFrame {

    /** Abreviacoes de dia da semana em portugues (3 caracteres), indexadas por Calendar.DAY_OF_WEEK. */
    private static final String[] WEEKDAYS_PT = {
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
    private static final String SETTINGS_FILE = "timetaker.properties";

    private final JTextArea textArea = new JTextArea();
    private File currentFile;

    /** Gerencia o historico de undo (desfazer) das edicoes do textArea. */
    private final UndoManager undoManager = new UndoManager();

    /** Indica se o documento tem alteracoes nao salvas (documento "sujo"). */
    private boolean modified = false;

    /** Tamanho padrao da janela na primeira execucao. */
    private static final int DEFAULT_WIDTH = 1000;
    private static final int DEFAULT_HEIGHT = 700;

    // --- Preferencias (persistidas em timetaker.properties) ---
    private String fontName = Font.MONOSPACED;
    private int fontSize = 13;
    private File defaultDir;

    // Geometria da janela; x/y = -1 significa "centralizar" (sem posicao salva).
    private int winWidth = DEFAULT_WIDTH;
    private int winHeight = DEFAULT_HEIGHT;
    private int winX = -1;
    private int winY = -1;

    public TimeTakerApp() {
        super("TimeTaker");

        // Controlamos o encerramento manualmente para poder questionar sobre
        // alteracoes nao salvas antes de fechar a janela.
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        loadSettings(); // fonte, pasta padrao e geometria da janela

        // Aplica tamanho/posicao salvos (ou padrao 1000x700 centralizado).
        setSize(winWidth, winHeight);
        if (winX >= 0 && winY >= 0 && isOnScreen(winX, winY, winWidth, winHeight)) {
            setLocation(winX, winY);
        } else {
            setLocationRelativeTo(null); // centraliza na tela
        }

        // Ao fechar (X): questiona sobre alteracoes nao salvas; se confirmado,
        // salva geometria da janela e encerra a aplicacao.
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                exitApplication();
            }
        });

        textArea.setFont(new Font(fontName, Font.PLAIN, fontSize));
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setBorder(new EmptyBorder(6, 6, 6, 6));

        // Registra o UndoManager para acompanhar as edicoes do documento.
        textArea.getDocument().addUndoableEditListener(undoManager);

        // Rastreia alteracoes do documento para o controle de "documento sujo".
        textArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                modified = true;
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                modified = true;
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                modified = true;
            }
        });

        JScrollPane scroll = new JScrollPane(textArea);
        setContentPane(scroll);

        setJMenuBar(buildMenuBar());

        // Carrega sempre o arquivo do dia ao iniciar.
        openDefaultDailyFile();
    }

    // ----------------------------------------------------------------- Menu

    private JMenuBar buildMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        int menuMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask(); // CTRL no Windows

        // --- Arquivos ---
        JMenu fileMenu = new JMenu("Arquivos");
        fileMenu.setMnemonic(KeyEvent.VK_A);

        fileMenu.add(makeItem("Novo arquivo", KeyEvent.VK_N,
                KeyStroke.getKeyStroke(KeyEvent.VK_N, menuMask), e -> newFile()));
        fileMenu.add(makeItem("Abrir", KeyEvent.VK_B,
                KeyStroke.getKeyStroke(KeyEvent.VK_O, menuMask | InputEvent.SHIFT_DOWN_MASK), e -> openFile()));
        fileMenu.add(makeItem("Salvar", KeyEvent.VK_S,
                KeyStroke.getKeyStroke(KeyEvent.VK_S, menuMask), e -> saveFile()));
        fileMenu.add(makeItem("Salvar como", KeyEvent.VK_C,
                KeyStroke.getKeyStroke(KeyEvent.VK_S, menuMask | InputEvent.SHIFT_DOWN_MASK), e -> saveFileAs()));

        // --- Editar ---
        JMenu editMenu = new JMenu("Editar");
        editMenu.setMnemonic(KeyEvent.VK_E);
        editMenu.add(makeItem("Configuracoes", KeyEvent.VK_C,
                KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, menuMask), e -> showSettings()));

        // --- Ajuda ---
        JMenu helpMenu = new JMenu("Ajuda");
        helpMenu.setMnemonic(KeyEvent.VK_J);
        helpMenu.add(makeItem("Ajuda", KeyEvent.VK_A,
                KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0), e -> showHelp()));

        menuBar.add(fileMenu);
        menuBar.add(editMenu);
        menuBar.add(helpMenu);

        // Atalhos globais de registro de tempo:
        //   CTRL+I -> entrada (clock in)   |   CTRL+O -> saida (clock out)
        registerGlobalShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_I, menuMask),
                "clockIn", this::insertClockLine);
        registerGlobalShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_O, menuMask),
                "clockOut", this::insertClockOut);

        // CTRL+Z -> desfaz a ultima edicao (suporta multiplos passos de historico).
        registerGlobalShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask),
                "undo", this::undoEdit);

        // CTRL+SHIFT+Z -> refaz a ultima edicao desfeita (suporta multiplos passos).
        registerGlobalShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask | InputEvent.SHIFT_DOWN_MASK),
                "redo", this::redoEdit);

        // CTRL+R -> recalcula a duracao de todos os registros CLOCK fechados.
        registerGlobalShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_R, menuMask),
                "recalc", this::recalculateDurations);

        return menuBar;
    }

    private JMenuItem makeItem(String text, int mnemonic, KeyStroke accelerator, ActionListenerLike action) {
        JMenuItem item = new JMenuItem(text);
        item.setMnemonic(mnemonic);
        if (accelerator != null) {
            item.setAccelerator(accelerator);
        }
        item.addActionListener(action::perform);
        return item;
    }

    /** Pequena interface funcional para manter as lambdas legiveis. */
    private interface ActionListenerLike {
        void perform(ActionEvent e);
    }

    private void registerGlobalShortcut(KeyStroke keyStroke, String name, Runnable action) {
        JRootPane root = getRootPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(keyStroke, name);
        root.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    /** Desfaz a ultima edicao de texto; nao faz nada se nao houver historico. */
    private void undoEdit() {
        try {
            if (undoManager.canUndo()) {
                undoManager.undo();
            }
        } catch (CannotUndoException ex) {
            // Sem nada para desfazer; ignora silenciosamente.
        }
    }

    /** Refaz a ultima edicao desfeita; nao faz nada se nao houver o que refazer. */
    private void redoEdit() {
        try {
            if (undoManager.canRedo()) {
                undoManager.redo();
            }
        } catch (CannotRedoException ex) {
            // Nada para refazer; ignora silenciosamente.
        }
    }

    // ------------------------------------------------------ Acoes de arquivo

    private void newFile() {
        textArea.setText("");
        currentFile = null;
        setTitle("TimeTaker - (novo arquivo)");
        // Limpa o historico: nao faz sentido desfazer de volta ao arquivo anterior.
        undoManager.discardAllEdits();
        modified = false; // documento recem-criado esta limpo
    }

    private void openFile() {
        JFileChooser chooser = new JFileChooser();
        if (currentFile != null) {
            chooser.setCurrentDirectory(currentFile.getParentFile());
        } else {
            chooser.setCurrentDirectory(defaultDir);
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            loadFile(chooser.getSelectedFile());
        }
    }

    private void saveFile() {
        if (currentFile == null) {
            saveFileAs();
            return;
        }
        writeToDisk(currentFile);
    }

    private void saveFileAs() {
        JFileChooser chooser = new JFileChooser();
        chooser.setCurrentDirectory(defaultDir);
        if (currentFile != null) {
            chooser.setSelectedFile(currentFile);
        }
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            currentFile = chooser.getSelectedFile();
            writeToDisk(currentFile);
        }
    }

    // ----------------------------------------------------- Encerramento

    /**
     * Encerra a aplicacao, questionando o usuario quando ha alteracoes nao salvas.
     * Se houver pendencias, oferece Salvar / Nao salvar / Cancelar; em caso de
     * cancelamento (ou falha ao salvar) o fechamento e abortado.
     */
    private void exitApplication() {
        if (modified) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "Ha alteracoes nao salvas. Deseja salvar antes de sair?",
                    "TimeTaker", JOptionPane.YES_NO_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE);

            if (choice == JOptionPane.YES_OPTION) {
                // Tenta salvar pelo fluxo normal (cai em "Salvar como" se necessario).
                saveFile();
                // Se ainda estiver sujo, o salvamento foi cancelado ou falhou: nao fecha.
                if (modified) {
                    return;
                }
            } else if (choice != JOptionPane.NO_OPTION) {
                // CANCEL ou dialogo fechado: aborta o encerramento.
                return;
            }
        }

        saveSettings();
        dispose();
        System.exit(0);
    }

    // ----------------------------------------------------- Arquivo do dia

    /**
     * Abre (ou cria) o arquivo padrao do dia: <Documentos do usuario>/ano-mes-dia.md.
     */
    private void openDefaultDailyFile() {
        String fileName = new SimpleDateFormat("yyyy-MM-dd").format(new Date()) + ".md";
        File dir = defaultDir;
        File daily = new File(dir, fileName);

        if (!daily.exists()) {
            try {
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                daily.createNewFile();
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this,
                        "Nao foi possivel criar o arquivo do dia:\n" + daily.getAbsolutePath()
                                + "\n\n" + ex.getMessage(),
                        "TimeTaker", JOptionPane.WARNING_MESSAGE);
                // Mesmo sem disco, mantem o caminho como destino de salvamento.
                currentFile = daily;
                setTitle("TimeTaker - " + daily.getName());
                return;
            }
        }
        loadFile(daily);
    }

    // ----------------------------------------------------- Deteccao de SO

    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    static boolean isWindows() {
        return OS.contains("win");
    }

    static boolean isLinux() {
        return OS.contains("linux") || OS.contains("nix") || OS.contains("nux") || OS.contains("aix");
    }

    static boolean isMac() {
        return OS.contains("mac") || OS.contains("darwin");
    }

    /**
     * Resolve a pasta de documentos do usuario conforme o sistema operacional.
     *
     * - Windows: a pasta fisica e sempre "Documents" (ingles), mesmo em sistemas
     *   em portugues onde o Explorer apenas exibe "Documentos".
     * - Linux: segue a especificacao XDG (XDG_DOCUMENTS_DIR / user-dirs.dirs),
     *   que pode estar localizada (ex.: "Documentos" em sistemas em portugues).
     * - macOS / outros: "~/Documents".
     */
    static File documentsDir() {
        String home = System.getProperty("user.home");

        if (isLinux()) {
            File xdg = linuxDocumentsDir(home);
            if (xdg != null) {
                return xdg;
            }
        }

        // Windows, macOS e fallback geral: a pasta fisica e "Documents".
        // Ainda testamos variantes localizadas por robustez.
        String[] candidates = {"Documents", "Documentos", "Document", "Documento"};
        for (String name : candidates) {
            File f = new File(home, name);
            if (f.isDirectory()) {
                return f;
            }
        }
        return new File(home, "Documents");
    }

    /**
     * Descobre a pasta de documentos no Linux via XDG, na ordem:
     * 1) variavel de ambiente XDG_DOCUMENTS_DIR;
     * 2) comando "xdg-user-dir DOCUMENTS";
     * 3) parsing de ~/.config/user-dirs.dirs.
     * Retorna null se nada confiavel for encontrado (cai no fallback generico).
     */
    private static File linuxDocumentsDir(String home) {
        // 1) Variavel de ambiente.
        File f = expandXdg(System.getenv("XDG_DOCUMENTS_DIR"), home);
        if (f != null && f.isDirectory()) {
            return f;
        }

        // 2) Comando xdg-user-dir (resolve nomes localizados).
        try {
            Process p = new ProcessBuilder("xdg-user-dir", "DOCUMENTS").start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line = r.readLine();
                f = expandXdg(line, home);
                if (f != null && f.isDirectory()) {
                    return f;
                }
            }
        } catch (IOException ignored) {
            // xdg-user-dir indisponivel: segue para o proximo metodo.
        }

        // 3) Arquivo de configuracao do XDG.
        File cfg = new File(home, ".config/user-dirs.dirs");
        if (cfg.isFile()) {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(Files.newInputStream(cfg.toPath()), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("XDG_DOCUMENTS_DIR")) {
                        int eq = line.indexOf('=');
                        if (eq >= 0) {
                            String value = line.substring(eq + 1).trim();
                            // Remove aspas em volta do valor.
                            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                                value = value.substring(1, value.length() - 1);
                            }
                            f = expandXdg(value, home);
                            if (f != null && f.isDirectory()) {
                                return f;
                            }
                        }
                    }
                }
            } catch (IOException ignored) {
                // Sem acesso ao arquivo: cai no fallback.
            }
        }

        return null;
    }

    /** Expande "$HOME" / "~" no inicio de um caminho XDG e retorna o File correspondente. */
    private static File expandXdg(String value, String home) {
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

    // ------------------------------------------------------------ I/O disco

    private void loadFile(File file) {
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            textArea.setText(new String(bytes, StandardCharsets.UTF_8));
            textArea.setCaretPosition(textArea.getDocument().getLength());
            currentFile = file;
            setTitle("TimeTaker - " + file.getName());
            // Limpa o historico: nao faz sentido desfazer de volta ao conteudo anterior.
            undoManager.discardAllEdits();
            modified = false; // conteudo recem-carregado do disco esta limpo
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "Erro ao abrir o arquivo:\n" + ex.getMessage(),
                    "TimeTaker", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void writeToDisk(File file) {
        try {
            Files.write(file.toPath(), textArea.getText().getBytes(StandardCharsets.UTF_8));
            setTitle("TimeTaker - " + file.getName());
            modified = false; // salvo com sucesso: documento limpo
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "Erro ao salvar o arquivo:\n" + ex.getMessage(),
                    "TimeTaker", JOptionPane.ERROR_MESSAGE);
        }
    }

    // --------------------------------------------------------- CLOCK (CTRL+I)

    /**
     * Insere no final do documento o registro:
     * CLOCK: [ano-mes-dia ddd hora:minuto]   (ex: CLOCK: [2021-11-16 ter 17:50])
     * Sem quebra de linha: apenas um espaco e adicionado apos o "]".
     * Se houver uma tarefa em execucao (um CLOCK em aberto), ela e fechada antes
     * com a hora atual como horario de saida (e a duracao), e so entao a nova
     * entrada e adicionada. Persiste em disco quando ha um arquivo associado.
     */
    private void insertClockLine() {
        // Se ha uma tarefa em aberto, fecha-a silenciosamente com a hora atual antes
        // de abrir a nova. Se nao houver nada para fechar, segue sem mostrar dialogo.
        closeOpenClock(true);

        String clock = "CLOCK: [" + formatClockStamp(nowToMinute()) + "] ";

        String content = textArea.getText();
        // Garante que o novo registro comece numa linha nova: se ja ha conteudo e o
        // documento nao termina com quebra de linha, antepoe um '\n'. Isso vale tanto
        // quando acabamos de fechar uma tarefa quanto quando o documento ja tinha
        // conteudo sem newline final.
        if (content.length() > 0 && !content.endsWith("\n")) {
            content = content + "\n";
        }
        textArea.setText(content + clock);
        textArea.setCaretPosition(textArea.getDocument().getLength());

        // Persiste automaticamente: o registro de ponto nao deve depender de um save manual.
        // Uma unica escrita ao final cobre tanto o fechamento quanto a nova entrada.
        if (currentFile != null) {
            writeToDisk(currentFile);
        }
    }

    /**
     * Fecha o ultimo registro CLOCK em aberto (Ctrl+O), com dialogos informativos.
     * Ex.: CLOCK: [2021-11-16 ter 17:50]--[2021-11-16 ter 18:18] =>  0:28
     * Persiste em disco quando ha um arquivo associado.
     */
    private void insertClockOut() {
        // Fecha o ultimo CLOCK em aberto exibindo os dialogos informativos (silent=false).
        if (closeOpenClock(false) && currentFile != null) {
            writeToDisk(currentFile);
        }
    }

    /** Padrao de um registro CLOCK fechado: grupo1=entrada, grupo2=saida, grupo3=duracao atual. */
    private static final Pattern CLOSED_CLOCK =
            Pattern.compile("CLOCK: \\[([^\\]]*)\\]--\\[([^\\]]*)\\] =>  +(\\d+:\\d{2})");

    /**
     * Recalcula a duracao de TODOS os registros CLOCK fechados (Ctrl+R) a partir dos
     * horarios de entrada e saida, corrigindo durecoes defasadas por edicao manual.
     * Registros em aberto (sem horario de saida) sao ignorados pelo padrao. Quando algum
     * horario nao puder ser interpretado, o registro e mantido como esta. Persiste em disco
     * ao final quando ha um arquivo associado.
     */
    private void recalculateDurations() {
        String text = textArea.getText();
        Matcher m = CLOSED_CLOCK.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String g1 = m.group(1);
            String g2 = m.group(2);
            Date entrada = parseClockInner(g1);
            Date saida = parseClockInner(g2);
            String replacement;
            if (entrada != null && saida != null) {
                // Recalcula a duracao; usa milissegundos absolutos, logo cruza dias sem problema.
                String novaDuracao = formatDuration(saida.getTime() - entrada.getTime());
                replacement = "CLOCK: [" + g1 + "]--[" + g2 + "] =>  " + novaDuracao;
            } else {
                // Horario invalido: mantem o registro exatamente como estava.
                replacement = m.group(0);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        String novoTexto = sb.toString();

        // Preserva a posicao do cursor de forma segura apos a reconstrucao do texto.
        int caret = textArea.getCaretPosition();
        textArea.setText(novoTexto);
        textArea.setCaretPosition(Math.min(caret, textArea.getDocument().getLength()));

        if (currentFile != null) {
            writeToDisk(currentFile);
        }
    }

    /**
     * Fecha o ultimo registro CLOCK em aberto (sem horario de saida), anexando o
     * horario de saida atual e a duracao decorrida, no padrao:
     *   CLOCK: [entrada]--[saida] =>  h:mm
     * e, logo apos, uma quebra de linha. Posiciona o cursor na nova linha.
     * Retorna true se fechou um registro; false se nao havia tarefa em aberto/valida.
     * Quando silent=true nao exibe dialogos (usado pelo Ctrl+I); quando silent=false
     * mostra os dialogos informativos atuais (usado pelo Ctrl+O). Nao persiste em disco:
     * a escrita fica a cargo de quem chama.
     */
    private boolean closeOpenClock(boolean silent) {
        String text = textArea.getText();

        int start = text.lastIndexOf("CLOCK: [");
        if (start < 0) {
            if (!silent) {
                JOptionPane.showMessageDialog(this,
                        "Nenhum registro CLOCK encontrado para fechar.\n"
                                + "Use Ctrl+I para registrar uma entrada.",
                        "Saida (CLOCK)", JOptionPane.INFORMATION_MESSAGE);
            }
            return false;
        }

        int openBracket = start + "CLOCK: [".length() - 1; // indice do '['
        int closeBracket = text.indexOf(']', start);
        if (closeBracket < 0) {
            if (!silent) {
                JOptionPane.showMessageDialog(this,
                        "O ultimo registro CLOCK esta malformado (sem ']').",
                        "Saida (CLOCK)", JOptionPane.WARNING_MESSAGE);
            }
            return false;
        }

        // Ja existe horario de saida anexado?
        if (text.startsWith("--[", closeBracket + 1)) {
            if (!silent) {
                JOptionPane.showMessageDialog(this,
                        "O ultimo registro ja possui horario de saida.\n"
                                + "Registre uma nova entrada com Ctrl+I antes de fechar.",
                        "Saida (CLOCK)", JOptionPane.INFORMATION_MESSAGE);
            }
            return false;
        }

        String inner = text.substring(openBracket + 1, closeBracket).trim();
        Date entryTime = parseClockInner(inner);
        if (entryTime == null) {
            if (!silent) {
                JOptionPane.showMessageDialog(this,
                        "Nao foi possivel interpretar o horario de entrada:\n" + inner,
                        "Saida (CLOCK)", JOptionPane.WARNING_MESSAGE);
            }
            return false;
        }

        Calendar now = nowToMinute();
        String exitStamp = formatClockStamp(now);
        String diff = formatDuration(now.getTimeInMillis() - entryTime.getTime());

        String insertion = "--[" + exitStamp + "] =>  " + diff;
        // Insere o horario de saida e, logo apos, uma quebra de linha para criar uma
        // nova linha abaixo do registro recem-fechado. Sempre adicionamos um unico '\n'
        // (mesmo que ja exista outra quebra adiante) para manter a logica simples e o
        // cursor sempre numa linha nova vazia.
        String updated = text.substring(0, closeBracket + 1) + insertion + "\n" + text.substring(closeBracket + 1);
        textArea.setText(updated);
        // Inicio da nova linha = posicao logo apos o '\n' que acabamos de inserir.
        // Como o registro pode nao estar no fim do documento, calculamos o indice
        // explicitamente em vez de usar getLength().
        int newLineStart = closeBracket + 1 + insertion.length() + 1;
        textArea.setCaretPosition(newLineStart);
        return true;
    }

    /** Instante atual com segundos/milissegundos zerados (precisao de minuto). */
    private static Calendar nowToMinute() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal;
    }

    /** Formata um instante como "yyyy-MM-dd ddd HH:mm" (ddd = dia em portugues, 3 chars). */
    private static String formatClockStamp(Calendar cal) {
        String date = new SimpleDateFormat("yyyy-MM-dd").format(cal.getTime());
        String time = new SimpleDateFormat("HH:mm").format(cal.getTime());
        String weekday = WEEKDAYS_PT[cal.get(Calendar.DAY_OF_WEEK)];
        return date + " " + weekday + " " + time;
    }

    /** Interpreta o conteudo entre colchetes ("yyyy-MM-dd ddd HH:mm") como Date (data + hora). */
    private static Date parseClockInner(String inner) {
        String[] parts = inner.split("\\s+");
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

    /** Formata uma duracao em milissegundos como "h:mm" (ex.: 0:28, 1:05). */
    private static String formatDuration(long millis) {
        if (millis < 0) {
            millis = 0;
        }
        long totalMinutes = millis / 60000L;
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        return hours + ":" + String.format("%02d", minutes);
    }

    // ----------------------------------------------------- Editar / Ajuda

    private void showSettings() {
        // --- Fonte: familia ---
        String[] families = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        JComboBox<String> fontCombo = new JComboBox<>(families);
        fontCombo.setSelectedItem(fontName);
        fontCombo.setMaximumRowCount(12);

        // --- Fonte: tamanho ---
        JSpinner sizeSpinner = new JSpinner(new SpinnerNumberModel(fontSize, 6, 96, 1));

        // --- Diretorio padrao ---
        JTextField dirField = new JTextField(defaultDir.getAbsolutePath(), 22);
        JButton browse = new JButton("...");
        browse.setMargin(new Insets(0, 6, 0, 6));
        browse.addActionListener(e -> {
            JFileChooser dc = new JFileChooser();
            dc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            File start = new File(dirField.getText().trim());
            dc.setCurrentDirectory(start.isDirectory() ? start : defaultDir);
            if (dc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                dirField.setText(dc.getSelectedFile().getAbsolutePath());
            }
        });
        JPanel dirPanel = new JPanel(new BorderLayout(4, 0));
        dirPanel.add(dirField, BorderLayout.CENTER);
        dirPanel.add(browse, BorderLayout.EAST);

        // --- Layout do formulario ---
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0; c.gridy = 0; c.weightx = 0;
        form.add(new JLabel("Fonte:"), c);
        c.gridx = 1; c.weightx = 1;
        form.add(fontCombo, c);

        c.gridx = 0; c.gridy = 1; c.weightx = 0;
        form.add(new JLabel("Tamanho:"), c);
        c.gridx = 1; c.weightx = 1;
        form.add(sizeSpinner, c);

        c.gridx = 0; c.gridy = 2; c.weightx = 0;
        form.add(new JLabel("Pasta padrao:"), c);
        c.gridx = 1; c.weightx = 1;
        form.add(dirPanel, c);

        int result = JOptionPane.showConfirmDialog(this, form, "Configuracoes",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return;
        }

        // Aplica as escolhas.
        fontName = (String) fontCombo.getSelectedItem();
        fontSize = (Integer) sizeSpinner.getValue();
        textArea.setFont(new Font(fontName, Font.PLAIN, fontSize));

        File chosenDir = new File(dirField.getText().trim());
        if (chosenDir.isDirectory()) {
            defaultDir = chosenDir;
        } else {
            JOptionPane.showMessageDialog(this,
                    "A pasta informada nao existe; mantida a anterior:\n" + defaultDir.getAbsolutePath(),
                    "Configuracoes", JOptionPane.WARNING_MESSAGE);
        }

        saveSettings();
    }

    // ----------------------------------------------------- Configuracoes (I/O)

    /** Pasta onde o .jar (ou as classes) estao localizados; destino do arquivo de config. */
    static File appDir() {
        try {
            File location = new File(
                    TimeTakerApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            // Quando executado a partir de um .jar, location aponta para o arquivo .jar.
            return location.isDirectory() ? location : location.getParentFile();
        } catch (Exception ex) {
            return new File(System.getProperty("user.dir"));
        }
    }

    private File settingsFile() {
        return new File(appDir(), SETTINGS_FILE);
    }

    private void loadSettings() {
        // Valores padrao.
        fontName = Font.MONOSPACED;
        fontSize = 13;
        defaultDir = documentsDir();
        winWidth = DEFAULT_WIDTH;
        winHeight = DEFAULT_HEIGHT;
        winX = -1;
        winY = -1;

        File cfg = settingsFile();
        if (!cfg.isFile()) {
            return;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(cfg.toPath())) {
            p.load(in);
        } catch (IOException ex) {
            return; // mantem os padroes
        }

        fontName = p.getProperty("font.name", fontName);
        fontSize = parseIntOr(p.getProperty("font.size"), fontSize);
        String dir = p.getProperty("default.dir");
        if (dir != null && new File(dir).isDirectory()) {
            defaultDir = new File(dir);
        }

        // Geometria da janela. Largura/altura recebem um minimo de seguranca.
        winWidth = Math.max(200, parseIntOr(p.getProperty("window.width"), winWidth));
        winHeight = Math.max(150, parseIntOr(p.getProperty("window.height"), winHeight));
        winX = parseIntOr(p.getProperty("window.x"), winX);
        winY = parseIntOr(p.getProperty("window.y"), winY);
    }

    private static int parseIntOr(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private void saveSettings() {
        captureWindowBounds();
        Properties p = new Properties();
        p.setProperty("font.name", fontName);
        p.setProperty("font.size", String.valueOf(fontSize));
        p.setProperty("default.dir", defaultDir.getAbsolutePath());
        p.setProperty("window.width", String.valueOf(winWidth));
        p.setProperty("window.height", String.valueOf(winHeight));
        p.setProperty("window.x", String.valueOf(winX));
        p.setProperty("window.y", String.valueOf(winY));
        try (OutputStream out = Files.newOutputStream(settingsFile().toPath())) {
            p.store(out, "Configuracoes do TimeTaker");
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "Nao foi possivel salvar as configuracoes em:\n"
                            + settingsFile().getAbsolutePath() + "\n\n" + ex.getMessage(),
                    "Configuracoes", JOptionPane.WARNING_MESSAGE);
        }
    }

    /**
     * Atualiza winX/winY/winWidth/winHeight com a geometria atual da janela.
     * Ignora estados maximizados/iconificados para preservar o tamanho "normal".
     */
    private void captureWindowBounds() {
        if (!isDisplayable() || (getExtendedState() != Frame.NORMAL)) {
            return;
        }
        Rectangle b = getBounds();
        if (b.width > 0 && b.height > 0) {
            winX = b.x;
            winY = b.y;
            winWidth = b.width;
            winHeight = b.height;
        }
    }

    /** Verifica se o retangulo informado intersecta alguma area visivel de tela. */
    private static boolean isOnScreen(int x, int y, int w, int h) {
        Rectangle wanted = new Rectangle(x, y, w, h);
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        for (GraphicsDevice gd : ge.getScreenDevices()) {
            if (gd.getDefaultConfiguration().getBounds().intersects(wanted)) {
                return true;
            }
        }
        return false;
    }

    private void showHelp() {
        JOptionPane.showMessageDialog(this,
                "TimeTaker - Ajuda\n\n"
                        + "Atalhos de teclado:\n"
                        + "  Ctrl+N         Novo arquivo\n"
                        + "  Ctrl+Shift+O   Abrir\n"
                        + "  Ctrl+S         Salvar\n"
                        + "  Ctrl+Shift+S   Salvar como\n"
                        + "  Ctrl+,         Configuracoes\n"
                        + "  F1             Ajuda\n"
                        + "  Ctrl+I         Entrada: insere CLOCK no final do arquivo\n"
                        + "  Ctrl+O         Saida: fecha o ultimo CLOCK com horario e duracao\n"
                        + "  Ctrl+R         Recalcula as duracoes de todos os registros\n"
                        + "  Ctrl+Z         Desfazer\n"
                        + "  Ctrl+Shift+Z   Refazer\n\n"
                        + "Ao abrir, o app carrega automaticamente o arquivo do dia\n"
                        + "(yyyy-MM-dd.md) na pasta de documentos do usuario.",
                "Ajuda", JOptionPane.INFORMATION_MESSAGE);
    }

    // ------------------------------------------------------------ main

    public static void main(String[] args) {
        // Usa o visual nativo do Windows 11 quando disponivel.
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Mantem o Look & Feel padrao caso o nativo nao esteja disponivel.
        }
        SwingUtilities.invokeLater(() -> new TimeTakerApp().setVisible(true));
    }
}
