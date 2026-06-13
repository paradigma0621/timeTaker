package com.timetaker;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Date;

/**
 * TimeTaker - editor de markdown minimalista voltado a registro de tempo (clock-in).
 *
 * GUI: Java Swing (Java 8). Esta classe e apenas a casca de interface grafica: toda a
 * logica de negocio (formatacao/parsing de horario, transformacoes dos registros CLOCK,
 * deteccao de SO, configuracoes) vive em {@link TimeTakerCore}, que e testavel sem display.
 */
public class TimeTakerApp extends JFrame {

    // Editor estilizado: JTextPane permite cor por trecho (StyledDocument), necessaria para
    // colorir as palavras-chave TODO/DONE; um JTextArea so admite uma unica cor de texto.
    private final JTextPane textArea = new JTextPane();
    private File currentFile;

    /** Historico de undo/redo das edicoes do textArea (logica em {@link UndoController}). */
    private UndoController undoController;

    /** Indica se o documento tem alteracoes nao salvas (documento "sujo"). */
    private boolean modified = false;

    /** Verde escuro usado para colorir a palavra-chave DONE (mais legivel que o verde puro). */
    private static final Color DONE_GREEN = new Color(0, 153, 0);

    /**
     * Guarda contra reentrancia: enquanto {@code true}, a recoloracao esta em curso e as
     * mutacoes de atributo que ela gera no documento NAO devem marcar o documento como sujo
     * nem disparar nova recoloracao.
     */
    private boolean recoloring = false;

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
        textArea.setBorder(new EmptyBorder(6, 6, 6, 6));

        // Acompanha as edicoes do documento para undo/redo.
        undoController = new UndoController(textArea.getDocument());

        // Rastreia alteracoes do documento (para "documento sujo") e reaplica as cores das
        // palavras-chave TODO/DONE. As insercoes/remocoes sao alteracoes reais de texto; o
        // changedUpdate vem de mudancas de atributo (a propria recoloracao) e e ignorado.
        textArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onTextChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onTextChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // Apenas mudancas de atributo (estilo); nao altera o texto nem o estado sujo.
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

        // CTRL+T -> relatorio de tempo por projeto (estilo org-clock-report).
        registerGlobalShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_T, menuMask),
                "clockReport", this::showClockReport);

        // CTRL+SHIFT+T -> insere no cursor o relatorio de tempo indentado por projeto.
        registerGlobalShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_T, menuMask | InputEvent.SHIFT_DOWN_MASK),
                "insertClockReport", this::insertClockReport);

        // CTRL+SHIFT+ALT+C -> registra uma pausa de cafe na secao "# Coffee" no fim do documento.
        registerGlobalShortcut(
                KeyStroke.getKeyStroke(KeyEvent.VK_C, menuMask | InputEvent.SHIFT_DOWN_MASK | InputEvent.ALT_DOWN_MASK),
                "coffee", this::insertCoffee);

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
        undoController.undo();
    }

    /** Refaz a ultima edicao desfeita; nao faz nada se nao houver o que refazer. */
    private void redoEdit() {
        undoController.redo();
    }

    // ------------------------------------------------------ Acoes de arquivo

    private void newFile() {
        textArea.setText("");
        currentFile = null;
        setTitle("TimeTaker - (novo arquivo)");
        // Limpa o historico: nao faz sentido desfazer de volta ao arquivo anterior.
        undoController.discardHistory();
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
        // Por padrao (documento limpo) a escolha e irrelevante; planExit ignora-a.
        TimeTakerCore.ExitChoice choice = TimeTakerCore.ExitChoice.DISCARD;
        if (modified) {
            int r = JOptionPane.showConfirmDialog(this,
                    "Ha alteracoes nao salvas. Deseja salvar antes de sair?",
                    "TimeTaker", JOptionPane.YES_NO_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (r == JOptionPane.YES_OPTION) {
                choice = TimeTakerCore.ExitChoice.SAVE;
            } else if (r == JOptionPane.NO_OPTION) {
                choice = TimeTakerCore.ExitChoice.DISCARD;
            } else {
                choice = TimeTakerCore.ExitChoice.CANCEL; // CANCEL ou dialogo fechado
            }
        }

        TimeTakerCore.ExitPlan plan = TimeTakerCore.planExit(modified, choice);
        if (plan == TimeTakerCore.ExitPlan.ABORT) {
            return;
        }
        if (plan == TimeTakerCore.ExitPlan.SAVE_THEN_EXIT) {
            // Tenta salvar pelo fluxo normal (cai em "Salvar como" se necessario).
            saveFile();
            // Se ainda estiver sujo, o salvamento foi cancelado ou falhou: nao fecha.
            if (modified) {
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
        File daily = new File(defaultDir, TimeTakerCore.dailyFileName(new Date()));

        if (!daily.exists()) {
            try {
                if (!defaultDir.exists()) {
                    defaultDir.mkdirs();
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

    private static String osName() {
        return System.getProperty("os.name", "");
    }

    static boolean isWindows() {
        return TimeTakerCore.isWindows(osName());
    }

    static boolean isLinux() {
        return TimeTakerCore.isLinux(osName());
    }

    static boolean isMac() {
        return TimeTakerCore.isMac(osName());
    }

    /**
     * Resolve a pasta de documentos do usuario conforme o sistema operacional.
     * No Linux tenta a resolucao XDG (variavel de ambiente, xdg-user-dir, user-dirs.dirs);
     * nos demais casos (e como fallback) usa {@link TimeTakerCore#documentsFallback(String)}.
     */
    static File documentsDir() {
        String home = System.getProperty("user.home");

        if (isLinux()) {
            File xdg = linuxDocumentsDir(home);
            if (xdg != null) {
                return xdg;
            }
        }
        return TimeTakerCore.documentsFallback(home);
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
        File f = TimeTakerCore.expandXdg(System.getenv("XDG_DOCUMENTS_DIR"), home);
        if (f != null && f.isDirectory()) {
            return f;
        }

        // 2) Comando xdg-user-dir (resolve nomes localizados).
        try {
            Process p = new ProcessBuilder("xdg-user-dir", "DOCUMENTS").start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line = r.readLine();
                f = TimeTakerCore.expandXdg(line, home);
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
            try {
                String value = TimeTakerCore.extractXdgDocumentsValue(TimeTakerCore.readFile(cfg));
                f = TimeTakerCore.expandXdg(value, home);
                if (f != null && f.isDirectory()) {
                    return f;
                }
            } catch (IOException ignored) {
                // Sem acesso ao arquivo: cai no fallback.
            }
        }

        return null;
    }

    // ------------------------------------------------------------ I/O disco

    private void loadFile(File file) {
        try {
            textArea.setText(TimeTakerCore.readFile(file));
            textArea.setCaretPosition(textArea.getDocument().getLength());
            currentFile = file;
            setTitle("TimeTaker - " + file.getName());
            // Limpa o historico: nao faz sentido desfazer de volta ao conteudo anterior.
            undoController.discardHistory();
            modified = false; // conteudo recem-carregado do disco esta limpo
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "Erro ao abrir o arquivo:\n" + ex.getMessage(),
                    "TimeTaker", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void writeToDisk(File file) {
        try {
            TimeTakerCore.writeFile(file, textArea.getText());
            setTitle("TimeTaker - " + file.getName());
            modified = false; // salvo com sucesso: documento limpo
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "Erro ao salvar o arquivo:\n" + ex.getMessage(),
                    "TimeTaker", JOptionPane.ERROR_MESSAGE);
        }
    }

    // --------------------------------------------------------- CLOCK

    /**
     * Ctrl+I: se houver uma tarefa em execucao, fecha-a com a hora atual antes de abrir
     * a nova entrada. Com o cursor sob um cabecalho de projeto ("* Nome" ou "# Nome"),
     * a entrada e inserida no fim daquela secao (estilo Org-mode); caso contrario, no
     * fim do arquivo. Delega a transformacao de texto para {@link TimeTakerCore}.
     */
    private void insertClockLine() {
        TimeTakerCore.TextEdit edit = TimeTakerCore.insertClockLine(
                textArea.getText(), textArea.getCaretPosition(), TimeTakerCore.nowToMinute());
        applyEdit(edit.text, edit.caret);
    }

    /**
     * Ctrl+O: fecha o ultimo CLOCK em aberto — onde quer que esteja no documento, ja que
     * com secoes de projeto ele pode estar acima de registros fechados de outros projetos
     * — exibindo dialogos informativos conforme a situacao retornada por
     * {@link TimeTakerCore#closeOpenClock}.
     */
    private void insertClockOut() {
        TimeTakerCore.CloseResult r =
                TimeTakerCore.closeOpenClock(textArea.getText(), TimeTakerCore.nowToMinute());
        switch (r.status) {
            case NO_CLOCK:
                JOptionPane.showMessageDialog(this,
                        "Nenhum registro CLOCK encontrado para fechar.\n"
                                + "Use Ctrl+I para registrar uma entrada.",
                        "Saida (CLOCK)", JOptionPane.INFORMATION_MESSAGE);
                return;
            case MALFORMED:
                JOptionPane.showMessageDialog(this,
                        "O registro CLOCK em aberto esta malformado (sem ']').",
                        "Saida (CLOCK)", JOptionPane.WARNING_MESSAGE);
                return;
            case ALREADY_CLOSED:
                JOptionPane.showMessageDialog(this,
                        "Todos os registros ja possuem horario de saida.\n"
                                + "Registre uma nova entrada com Ctrl+I antes de fechar.",
                        "Saida (CLOCK)", JOptionPane.INFORMATION_MESSAGE);
                return;
            case UNPARSEABLE:
                JOptionPane.showMessageDialog(this,
                        "Nao foi possivel interpretar o horario de entrada do registro em aberto.",
                        "Saida (CLOCK)", JOptionPane.WARNING_MESSAGE);
                return;
            default: // CLOSED
                applyEdit(r.text, r.caret);
        }
    }

    /**
     * Ctrl+T: exibe o relatorio de tempo total por projeto (estilo org-clock-report),
     * calculado por {@link TimeTakerCore#clockReport}. Nao altera o documento.
     */
    private void showClockReport() {
        JTextArea report = new JTextArea(TimeTakerCore.clockReport(textArea.getText()));
        report.setEditable(false);
        report.setFont(new Font(Font.MONOSPACED, Font.PLAIN, fontSize));
        report.setBorder(new EmptyBorder(6, 6, 6, 6));
        JOptionPane.showMessageDialog(this, new JScrollPane(report),
                "Tempo por projeto", JOptionPane.PLAIN_MESSAGE);
    }

    /**
     * Ctrl+Shift+T: insere no proprio documento, na posicao do cursor, o relatorio de tempo
     * HIERARQUICO (indentado conforme o nivel de cada projeto) — diferente do Ctrl+T, que
     * apenas o exibe num dialogo. O bloco e separado do conteudo anterior por uma quebra de
     * linha quando necessario, e o cursor fica logo apos o texto inserido. A edicao passa por
     * {@link #applyEdit}, portanto e desfazivel com Ctrl+Z como as demais.
     */
    private void insertClockReport() {
        int caret = textArea.getCaretPosition();
        String text = textArea.getText();
        String report = TimeTakerCore.clockReportIndented(text);

        String before = text.substring(0, caret);
        // Garante que o relatorio comece numa linha propria, sem colar no conteudo anterior.
        String block = (before.isEmpty() || before.endsWith("\n")) ? report : "\n" + report;
        // E que haja uma quebra de linha apos o bloco, separando-o do conteudo seguinte.
        if (!block.endsWith("\n")) {
            block = block + "\n";
        }
        String updated = before + block + text.substring(caret);
        applyEdit(updated, caret + block.length());
    }

    /**
     * Ctrl+Shift+Alt+C: registra uma pausa de cafe no fim do documento, sob a secao
     * "# Coffee" (criada apenas uma vez), agrupada no subtopico do dia. Delega a
     * transformacao para {@link TimeTakerCore#registerCoffee} e e desfazivel com Ctrl+Z.
     */
    private void insertCoffee() {
        TimeTakerCore.TextEdit edit =
                TimeTakerCore.registerCoffee(textArea.getText(), TimeTakerCore.nowToMinute());
        applyEdit(edit.text, edit.caret);
    }

    /**
     * Ctrl+R: recalcula as duracoes de todos os registros fechados, preservando o cursor.
     */
    private void recalculateDurations() {
        int caret = textArea.getCaretPosition();
        String novoTexto = TimeTakerCore.recalculateDurations(textArea.getText());
        textArea.setText(novoTexto);
        textArea.setCaretPosition(Math.min(caret, textArea.getDocument().getLength()));
        if (currentFile != null) {
            writeToDisk(currentFile);
        }
    }

    /** Aplica um novo texto + posicao de cursor e persiste em disco quando ha arquivo. */
    private void applyEdit(String text, int caret) {
        textArea.setText(text);
        textArea.setCaretPosition(Math.min(caret, textArea.getDocument().getLength()));
        if (currentFile != null) {
            writeToDisk(currentFile);
        }
    }

    // --------------------------------------------------------- Coloracao TODO/DONE

    /**
     * Reage a uma alteracao real de texto (insercao/remocao): marca o documento como sujo e
     * agenda a recoloracao das palavras-chave. Durante a propria recoloracao (mutacao de
     * atributo) e ignorado, evitando reentrancia e nao marcando o documento como sujo.
     */
    private void onTextChanged() {
        if (recoloring) {
            return;
        }
        modified = true;
        // A recoloracao muta o documento; nao se pode faze-lo durante o evento. Adia-se.
        SwingUtilities.invokeLater(this::recolorKeywords);
    }

    /**
     * Recolore o documento: reseta tudo para a cor padrao e aplica vermelho ao TODO e verde
     * ao DONE nos trechos calculados por {@link TimeTakerCore#keywordSpans(String)}. O undo e
     * suspenso para que estas mutacoes de atributo nao poluam o historico de Ctrl+Z, e a flag
     * {@code recoloring} impede que o DocumentListener as trate como edicoes do usuario.
     */
    private void recolorKeywords() {
        if (recoloring) {
            return;
        }
        recoloring = true;
        undoController.suspend();
        try {
            StyledDocument doc = textArea.getStyledDocument();
            String text = textArea.getText();

            SimpleAttributeSet base = new SimpleAttributeSet();
            StyleConstants.setForeground(base, Color.BLACK);
            doc.setCharacterAttributes(0, doc.getLength(), base, true);

            for (TimeTakerCore.KeywordSpan span : TimeTakerCore.keywordSpans(text)) {
                SimpleAttributeSet attr = new SimpleAttributeSet();
                StyleConstants.setForeground(attr,
                        span.kind == TimeTakerCore.Keyword.TODO ? Color.RED : DONE_GREEN);
                doc.setCharacterAttributes(span.start, span.length, attr, false);
            }
        } finally {
            undoController.resume();
            recoloring = false;
        }
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
        return new File(appDir(), TimeTakerCore.SETTINGS_FILE);
    }

    private void loadSettings() {
        TimeTakerCore.Settings s = new TimeTakerCore.Settings(
                Font.MONOSPACED, 13, documentsDir().getAbsolutePath(),
                DEFAULT_WIDTH, DEFAULT_HEIGHT, -1, -1);
        s = TimeTakerCore.loadSettings(settingsFile(), s);

        fontName = s.fontName;
        fontSize = s.fontSize;
        defaultDir = new File(s.defaultDir);
        winWidth = s.winWidth;
        winHeight = s.winHeight;
        winX = s.winX;
        winY = s.winY;
    }

    private void saveSettings() {
        captureWindowBounds();
        TimeTakerCore.Settings s = new TimeTakerCore.Settings(
                fontName, fontSize, defaultDir.getAbsolutePath(),
                winWidth, winHeight, winX, winY);
        try {
            TimeTakerCore.saveSettings(settingsFile(), s);
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
                        + "  Ctrl+I         Entrada: insere CLOCK no projeto do cursor\n"
                        + "                 (ou no final do arquivo, sem projeto)\n"
                        + "  Ctrl+O         Saida: fecha o CLOCK em aberto com horario e duracao\n"
                        + "  Ctrl+R         Recalcula as duracoes de todos os registros\n"
                        + "  Ctrl+T         Relatorio de tempo por projeto\n"
                        + "  Ctrl+Shift+T   Insere o relatorio (indentado) no cursor\n"
                        + "  Ctrl+Shift+Alt+C  Registra pausa de cafe na secao \"# Coffee\"\n"
                        + "                 (agrupada por dia, no fim do documento)\n"
                        + "  Ctrl+Z         Desfazer\n"
                        + "  Ctrl+Shift+Z   Refazer\n\n"
                        + "Projetos (estilo Org-mode): linhas \"* Nome\" ou \"# Nome\" criam\n"
                        + "secoes de projeto; Ctrl+I com o cursor numa secao registra a\n"
                        + "entrada no fim daquela secao, fechando antes qualquer tarefa\n"
                        + "em aberto (em qualquer projeto).\n\n"
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
