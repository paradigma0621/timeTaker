package com.timetaker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Calendar;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes unitarios de TimeTakerCore. Cobrem 100% das linhas e ramos da logica pura:
 * formatacao/parsing de horario, transformacoes dos registros CLOCK (Ctrl+I/O/R),
 * deteccao de SO, resolucao XDG, parsing de configuracoes e I/O de arquivo.
 */
class TimeTakerCoreTest {

    /** Cria um Calendar limpo no horario informado (mes 1-based para legibilidade). */
    private static Calendar cal(int y, int month1, int d, int h, int min, int s) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(y, month1 - 1, d, h, min, s);
        return c;
    }

    // ----------------------------------------------------- Classe utilitaria

    @Test
    void construtorPrivadoEhInvocavelViaReflexao() throws Exception {
        Constructor<TimeTakerCore> ctor = TimeTakerCore.class.getDeclaredConstructor();
        assertFalse(ctor.isAccessible());
        ctor.setAccessible(true);
        assertNotNull(ctor.newInstance());
    }

    // ----------------------------------------------------- Deteccao de SO

    @Test
    void isWindows() {
        assertTrue(TimeTakerCore.isWindows("Windows 11"));
        assertFalse(TimeTakerCore.isWindows("Linux"));
        assertFalse(TimeTakerCore.isWindows(null)); // tolera null
    }

    @Test
    void isLinuxCobreTodosOsRamos() {
        assertTrue(TimeTakerCore.isLinux("Linux"));   // contains "linux"
        assertTrue(TimeTakerCore.isLinux("Unix"));    // contains "nix"
        assertTrue(TimeTakerCore.isLinux("anux"));    // contains "nux" (sem "linux"/"nix")
        assertTrue(TimeTakerCore.isLinux("AIX"));     // contains "aix"
        assertFalse(TimeTakerCore.isLinux("Windows"));
        assertFalse(TimeTakerCore.isLinux(null));
    }

    @Test
    void isMac() {
        assertTrue(TimeTakerCore.isMac("Mac OS X"));  // contains "mac"
        assertTrue(TimeTakerCore.isMac("Darwin"));    // contains "darwin"
        assertFalse(TimeTakerCore.isMac("Linux"));
    }

    // ----------------------------------------------------- Horario / formatacao

    @Test
    void nowToMinuteZeraSegundosEMillis() {
        Calendar c = TimeTakerCore.nowToMinute();
        assertEquals(0, c.get(Calendar.SECOND));
        assertEquals(0, c.get(Calendar.MILLISECOND));
    }

    @Test
    void toMinuteZeraCampos() {
        Calendar base = cal(2021, 11, 16, 17, 50, 45);
        base.set(Calendar.MILLISECOND, 123);
        Calendar c = TimeTakerCore.toMinute(base);
        assertEquals(0, c.get(Calendar.SECOND));
        assertEquals(0, c.get(Calendar.MILLISECOND));
        assertEquals(50, c.get(Calendar.MINUTE));
    }

    @Test
    void weekdayPt() {
        assertEquals("", TimeTakerCore.weekdayPt(0));   // indice < 1
        assertEquals("dom", TimeTakerCore.weekdayPt(Calendar.SUNDAY));
        assertEquals("ter", TimeTakerCore.weekdayPt(Calendar.TUESDAY));
        assertEquals("sab", TimeTakerCore.weekdayPt(Calendar.SATURDAY));
        assertEquals("", TimeTakerCore.weekdayPt(8));   // indice >= length
        assertEquals("", TimeTakerCore.weekdayPt(-3));  // negativo
    }

    @Test
    void formatClockStamp() {
        // 16/11/2021 foi uma terca-feira.
        assertEquals("2021-11-16 ter 17:50", TimeTakerCore.formatClockStamp(cal(2021, 11, 16, 17, 50, 0)));
    }

    @Test
    void parseClockInner() {
        assertNull(TimeTakerCore.parseClockInner(null));         // null
        assertNull(TimeTakerCore.parseClockInner(""));           // < 2 tokens
        assertNull(TimeTakerCore.parseClockInner("2021-11-16")); // < 2 tokens
        assertNull(TimeTakerCore.parseClockInner("xxxx-xx-xx zz:zz")); // ParseException
        assertNull(TimeTakerCore.parseClockInner("2021-11-16 ter 25:61")); // invalido (lenient=false)

        Date d = TimeTakerCore.parseClockInner("2021-11-16 ter 17:50");
        assertNotNull(d);
        Calendar c = Calendar.getInstance();
        c.setTime(d);
        assertEquals(2021, c.get(Calendar.YEAR));
        assertEquals(17, c.get(Calendar.HOUR_OF_DAY));
        assertEquals(50, c.get(Calendar.MINUTE));
    }

    @Test
    void formatDuration() {
        assertEquals("0:00", TimeTakerCore.formatDuration(-5000)); // negativo vira 0
        assertEquals("0:00", TimeTakerCore.formatDuration(0));
        assertEquals("0:28", TimeTakerCore.formatDuration(28 * 60_000L));
        assertEquals("1:05", TimeTakerCore.formatDuration(65 * 60_000L));
        assertEquals("12:30", TimeTakerCore.formatDuration((12 * 60 + 30) * 60_000L));
    }

    // ----------------------------------------------------- closeOpenClock

    @Test
    void closeOpenClock_noClock() {
        TimeTakerCore.CloseResult r = TimeTakerCore.closeOpenClock("sem registro", cal(2021, 11, 16, 18, 18, 0));
        assertEquals(TimeTakerCore.CloseStatus.NO_CLOCK, r.status);
        assertFalse(r.closed());
        assertEquals(-1, r.caret);
        assertEquals("sem registro", r.text);
    }

    @Test
    void closeOpenClock_malformed() {
        TimeTakerCore.CloseResult r = TimeTakerCore.closeOpenClock("CLOCK: [2021-11-16 ter 17:50", cal(2021, 11, 16, 18, 18, 0));
        assertEquals(TimeTakerCore.CloseStatus.MALFORMED, r.status);
    }

    @Test
    void closeOpenClock_alreadyClosed() {
        String text = "CLOCK: [2021-11-16 ter 17:50]--[2021-11-16 ter 18:18] =>  0:28";
        TimeTakerCore.CloseResult r = TimeTakerCore.closeOpenClock(text, cal(2021, 11, 16, 19, 0, 0));
        assertEquals(TimeTakerCore.CloseStatus.ALREADY_CLOSED, r.status);
    }

    @Test
    void closeOpenClock_unparseable() {
        String text = "CLOCK: [horario invalido aqui]";
        TimeTakerCore.CloseResult r = TimeTakerCore.closeOpenClock(text, cal(2021, 11, 16, 18, 18, 0));
        assertEquals(TimeTakerCore.CloseStatus.UNPARSEABLE, r.status);
    }

    @Test
    void closeOpenClock_closed() {
        String text = "CLOCK: [2021-11-16 ter 17:50] ";
        TimeTakerCore.CloseResult r = TimeTakerCore.closeOpenClock(text, cal(2021, 11, 16, 18, 18, 0));
        assertEquals(TimeTakerCore.CloseStatus.CLOSED, r.status);
        assertTrue(r.closed());
        assertEquals("CLOCK: [2021-11-16 ter 17:50]--[2021-11-16 ter 18:18] =>  0:28\n", r.text);
        // caret no inicio da nova linha (logo apos o '\n' inserido).
        assertEquals('\n', r.text.charAt(r.caret - 1));
    }

    @Test
    void closeOpenClock_descricaoPermaneceNaMesmaLinha() {
        // A descricao digitada apos o registro de entrada nao vai para a linha de baixo:
        // e reposicionada apos a duracao, na mesma linha.
        String text = "CLOCK: [2021-11-16 ter 17:50] estudando java";
        TimeTakerCore.CloseResult r = TimeTakerCore.closeOpenClock(text, cal(2021, 11, 16, 18, 18, 0));
        assertEquals(TimeTakerCore.CloseStatus.CLOSED, r.status);
        assertEquals("CLOCK: [2021-11-16 ter 17:50]--[2021-11-16 ter 18:18] =>  0:28 estudando java\n", r.text);
        assertEquals(r.text.length(), r.caret);
    }

    @Test
    void closeOpenClock_descricaoComLinhasAbaixoPreservadas() {
        // Registro em aberto no meio do documento: descricao fica na mesma linha e as
        // linhas seguintes nao sao alteradas.
        String text = "CLOCK: [2021-11-16 ter 17:50] revisando PR\nanotacao abaixo\n";
        TimeTakerCore.CloseResult r = TimeTakerCore.closeOpenClock(text, cal(2021, 11, 16, 18, 18, 0));
        assertEquals(TimeTakerCore.CloseStatus.CLOSED, r.status);
        assertEquals(
                "CLOCK: [2021-11-16 ter 17:50]--[2021-11-16 ter 18:18] =>  0:28 revisando PR\n"
                        + "anotacao abaixo\n",
                r.text);
        // caret no inicio da linha seguinte ("anotacao abaixo").
        assertEquals(r.text.indexOf("anotacao"), r.caret);
    }

    // ----------------------------------------------------- insertClockLine

    @Test
    void insertClockLine_documentoVazio() {
        TimeTakerCore.TextEdit e = TimeTakerCore.insertClockLine("", cal(2021, 11, 16, 9, 0, 0));
        assertEquals("CLOCK: [2021-11-16 ter 09:00] ", e.text);
        assertEquals(e.text.length(), e.caret);
    }

    @Test
    void insertClockLine_fechaTarefaEmAberto() {
        String text = "CLOCK: [2021-11-16 ter 09:00] ";
        TimeTakerCore.TextEdit e = TimeTakerCore.insertClockLine(text, cal(2021, 11, 16, 10, 30, 0));
        assertEquals(
                "CLOCK: [2021-11-16 ter 09:00]--[2021-11-16 ter 10:30] =>  1:30\n"
                        + "CLOCK: [2021-11-16 ter 10:30] ",
                e.text);
    }

    @Test
    void insertClockLine_conteudoSemNewlineFinal() {
        // Sem CLOCK em aberto, conteudo existente sem '\n' no fim: deve anteceder '\n'.
        TimeTakerCore.TextEdit e = TimeTakerCore.insertClockLine("anotacao", cal(2021, 11, 16, 9, 0, 0));
        assertEquals("anotacao\nCLOCK: [2021-11-16 ter 09:00] ", e.text);
    }

    @Test
    void insertClockLine_conteudoComNewlineFinal() {
        TimeTakerCore.TextEdit e = TimeTakerCore.insertClockLine("anotacao\n", cal(2021, 11, 16, 9, 0, 0));
        assertEquals("anotacao\nCLOCK: [2021-11-16 ter 09:00] ", e.text);
    }

    // ----------------------------------------------------- recalculateDurations

    @Test
    void recalculate_semRegistros() {
        assertEquals("texto qualquer", TimeTakerCore.recalculateDurations("texto qualquer"));
    }

    @Test
    void recalculate_corrigeDuracaoDefasada() {
        String in = "CLOCK: [2021-11-16 ter 09:00]--[2021-11-16 ter 10:30] =>  0:01\n";
        String out = "CLOCK: [2021-11-16 ter 09:00]--[2021-11-16 ter 10:30] =>  1:30\n";
        assertEquals(out, TimeTakerCore.recalculateDurations(in));
    }

    @Test
    void recalculate_variosRegistros() {
        String in = "CLOCK: [2021-11-16 ter 09:00]--[2021-11-16 ter 09:30] =>  9:99\n"
                + "CLOCK: [2021-11-16 ter 10:00]--[2021-11-16 ter 12:00] =>  0:00\n";
        String out = "CLOCK: [2021-11-16 ter 09:00]--[2021-11-16 ter 09:30] =>  0:30\n"
                + "CLOCK: [2021-11-16 ter 10:00]--[2021-11-16 ter 12:00] =>  2:00\n";
        assertEquals(out, TimeTakerCore.recalculateDurations(in));
    }

    @Test
    void recalculate_horarioInvalidoMantemRegistro() {
        // Entrada/saida nao interpretaveis: o registro casa o padrao mas e mantido como esta.
        String in = "CLOCK: [data ruim]--[outra ruim] =>  3:33";
        assertEquals(in, TimeTakerCore.recalculateDurations(in));
    }

    @Test
    void recalculate_entradaValidaMasSaidaInvalida() {
        // Cobre o ramo entrada != null (true) && saida != null (false): mantem o registro.
        String in = "CLOCK: [2021-11-16 ter 09:00]--[saida ruim] =>  1:00";
        assertEquals(in, TimeTakerCore.recalculateDurations(in));
    }

    // ----------------------------------------------------- planExit (feature 1)

    @Test
    void planExit_documentoLimpoEncerraDireto() {
        // Sem alteracoes: encerra direto, independentemente da escolha.
        assertEquals(TimeTakerCore.ExitPlan.EXIT_NOW,
                TimeTakerCore.planExit(false, TimeTakerCore.ExitChoice.CANCEL));
        assertEquals(TimeTakerCore.ExitPlan.EXIT_NOW,
                TimeTakerCore.planExit(false, TimeTakerCore.ExitChoice.SAVE));
    }

    @Test
    void planExit_comAlteracoes() {
        assertEquals(TimeTakerCore.ExitPlan.SAVE_THEN_EXIT,
                TimeTakerCore.planExit(true, TimeTakerCore.ExitChoice.SAVE));
        assertEquals(TimeTakerCore.ExitPlan.EXIT_NOW,
                TimeTakerCore.planExit(true, TimeTakerCore.ExitChoice.DISCARD));
        assertEquals(TimeTakerCore.ExitPlan.ABORT,
                TimeTakerCore.planExit(true, TimeTakerCore.ExitChoice.CANCEL));
    }

    @Test
    void exitEnumsValuesEValueOf() {
        assertEquals(3, TimeTakerCore.ExitChoice.values().length);
        assertEquals(TimeTakerCore.ExitChoice.SAVE, TimeTakerCore.ExitChoice.valueOf("SAVE"));
        assertEquals(3, TimeTakerCore.ExitPlan.values().length);
        assertEquals(TimeTakerCore.ExitPlan.ABORT, TimeTakerCore.ExitPlan.valueOf("ABORT"));
    }

    // ----------------------------------------------------- expandXdg

    @Test
    void expandXdg() {
        assertNull(TimeTakerCore.expandXdg(null, "/home/u"));
        assertNull(TimeTakerCore.expandXdg("   ", "/home/u"));
        assertEquals(new File("/home/u/Docs"), TimeTakerCore.expandXdg("$HOME/Docs", "/home/u"));
        assertEquals(new File("/home/u/Docs"), TimeTakerCore.expandXdg("~/Docs", "/home/u"));
        assertEquals(new File("/abs/path"), TimeTakerCore.expandXdg("/abs/path", "/home/u"));
    }

    // ----------------------------------------------------- extractXdgDocumentsValue

    @Test
    void extractXdgDocumentsValue() {
        assertNull(TimeTakerCore.extractXdgDocumentsValue(null));
        assertNull(TimeTakerCore.extractXdgDocumentsValue("OUTRA_COISA=1\n")); // chave ausente
        assertNull(TimeTakerCore.extractXdgDocumentsValue("XDG_DOCUMENTS_DIR\n")); // sem '='
        assertEquals("$HOME/Documents",
                TimeTakerCore.extractXdgDocumentsValue("# comentario\nXDG_DOCUMENTS_DIR=\"$HOME/Documents\"\n"));
        assertEquals("/sem/aspas",
                TimeTakerCore.extractXdgDocumentsValue("XDG_DOCUMENTS_DIR=/sem/aspas"));
        assertEquals("\"so/abre", // aspa no inicio mas nao no fim -> nao remove
                TimeTakerCore.extractXdgDocumentsValue("XDG_DOCUMENTS_DIR=\"so/abre"));
        assertEquals("", // valor "" (length 2, par de aspas) -> vira vazio
                TimeTakerCore.extractXdgDocumentsValue("XDG_DOCUMENTS_DIR=\"\""));
        assertEquals("x", // valor de 1 caractere: ramo value.length() >= 2 == false
                TimeTakerCore.extractXdgDocumentsValue("XDG_DOCUMENTS_DIR=x"));
    }

    // ----------------------------------------------------- documentsFallback

    @Test
    void documentsFallback_encontraVariante(@TempDir Path home) throws Exception {
        Files.createDirectory(home.resolve("Documentos"));
        File f = TimeTakerCore.documentsFallback(home.toString());
        assertEquals(new File(home.toFile(), "Documentos"), f);
    }

    @Test
    void documentsFallback_padraoQuandoNaoExiste(@TempDir Path home) {
        File f = TimeTakerCore.documentsFallback(home.toString());
        assertEquals(new File(home.toFile(), "Documents"), f);
    }

    // ----------------------------------------------------- parseIntOr

    @Test
    void parseIntOr() {
        assertEquals(7, TimeTakerCore.parseIntOr(null, 7));
        assertEquals(42, TimeTakerCore.parseIntOr(" 42 ", 7));
        assertEquals(7, TimeTakerCore.parseIntOr("abc", 7));
    }

    // ----------------------------------------------------- Settings load/save

    private static TimeTakerCore.Settings defaults(String dir) {
        return new TimeTakerCore.Settings("Monospaced", 13, dir, 1000, 700, -1, -1);
    }

    @Test
    void loadSettings_cfgNuloOuInexistente(@TempDir Path tmp) {
        TimeTakerCore.Settings d1 = defaults(tmp.toString());
        assertSame(d1, TimeTakerCore.loadSettings(null, d1)); // cfg == null

        File inexistente = tmp.resolve("nao-existe.properties").toFile();
        TimeTakerCore.Settings d2 = defaults(tmp.toString());
        assertSame(d2, TimeTakerCore.loadSettings(inexistente, d2)); // !isFile()
    }

    @Test
    void loadSettings_leTodosOsCampos(@TempDir Path tmp) throws Exception {
        Path docs = tmp.resolve("docs");
        Files.createDirectory(docs);
        File cfg = tmp.resolve("timetaker.properties").toFile();
        String props = "font.name=Courier\n"
                + "font.size=20\n"
                + "default.dir=" + docs.toAbsolutePath() + "\n"
                + "window.width=800\n"
                + "window.height=600\n"
                + "window.x=10\n"
                + "window.y=20\n";
        Files.write(cfg.toPath(), props.getBytes(StandardCharsets.UTF_8));

        TimeTakerCore.Settings s = TimeTakerCore.loadSettings(cfg, defaults(tmp.toString()));
        assertEquals("Courier", s.fontName);
        assertEquals(20, s.fontSize);
        assertEquals(docs.toAbsolutePath().toString(), s.defaultDir);
        assertEquals(800, s.winWidth);
        assertEquals(600, s.winHeight);
        assertEquals(10, s.winX);
        assertEquals(20, s.winY);
    }

    @Test
    void loadSettings_dirInexistenteEhIgnoradoEDimensoesTemMinimo(@TempDir Path tmp) throws Exception {
        File cfg = tmp.resolve("timetaker.properties").toFile();
        // default.dir aponta para algo que nao existe -> mantem o default.
        // largura/altura abaixo do minimo -> sao elevadas a 200/150.
        String props = "default.dir=" + tmp.resolve("nao-existe") + "\n"
                + "window.width=10\n"
                + "window.height=10\n";
        Files.write(cfg.toPath(), props.getBytes(StandardCharsets.UTF_8));

        TimeTakerCore.Settings s = TimeTakerCore.loadSettings(cfg, defaults(tmp.toString()));
        assertEquals(tmp.toString(), s.defaultDir); // inalterado
        assertEquals(200, s.winWidth);
        assertEquals(150, s.winHeight);
    }

    @Test
    void loadSettings_semChaveDefaultDirMantemNull(@TempDir Path tmp) throws Exception {
        // Cobre o ramo "dir == null" (propriedade default.dir ausente).
        File cfg = tmp.resolve("timetaker.properties").toFile();
        Files.write(cfg.toPath(), "font.size=15\n".getBytes(StandardCharsets.UTF_8));
        TimeTakerCore.Settings s = TimeTakerCore.loadSettings(cfg, defaults(tmp.toString()));
        assertEquals(15, s.fontSize);
        assertEquals(tmp.toString(), s.defaultDir);
    }

    @Test
    void loadSettings_arquivoIlegivelCaiNoCatch(@TempDir Path tmp) throws Exception {
        File cfg = tmp.resolve("timetaker.properties").toFile();
        Files.write(cfg.toPath(), "font.size=99\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(cfg.setReadable(false), "ambiente deve permitir remover leitura");
        assertFalse(cfg.canRead(), "arquivo precisa estar ilegivel para exercitar o catch");

        TimeTakerCore.Settings base = defaults(tmp.toString());
        TimeTakerCore.Settings s = TimeTakerCore.loadSettings(cfg, base);
        // IOException ao abrir -> retorna os defaults inalterados.
        assertEquals(13, s.fontSize);
        cfg.setReadable(true); // restaura para limpeza do @TempDir
    }

    @Test
    void saveSettings_eDepoisLoadFazRoundTrip(@TempDir Path tmp) throws Exception {
        Path docs = tmp.resolve("d");
        Files.createDirectory(docs);
        File cfg = tmp.resolve("out.properties").toFile();
        TimeTakerCore.Settings s = new TimeTakerCore.Settings(
                "Arial", 18, docs.toAbsolutePath().toString(), 1234, 876, 5, 6);
        TimeTakerCore.saveSettings(cfg, s);
        assertTrue(cfg.isFile());

        TimeTakerCore.Settings r = TimeTakerCore.loadSettings(cfg, defaults(tmp.toString()));
        assertEquals("Arial", r.fontName);
        assertEquals(18, r.fontSize);
        assertEquals(docs.toAbsolutePath().toString(), r.defaultDir);
        assertEquals(1234, r.winWidth);
        assertEquals(876, r.winHeight);
        assertEquals(5, r.winX);
        assertEquals(6, r.winY);
    }

    // ----------------------------------------------------- I/O de arquivo

    @Test
    void readFile_writeFile_roundTrip(@TempDir Path tmp) throws Exception {
        File f = tmp.resolve("a.md").toFile();
        TimeTakerCore.writeFile(f, "linha 1\nacento: ção\n");
        assertEquals("linha 1\nacento: ção\n", TimeTakerCore.readFile(f));
    }

    @Test
    void dailyFileName() {
        assertEquals("2026-06-11.md", TimeTakerCore.dailyFileName(cal(2026, 6, 11, 0, 0, 0).getTime()));
    }

    // ----------------------------------------------------- enum / value objects

    @Test
    void closeStatusEnumValuesEValueOf() {
        assertEquals(5, TimeTakerCore.CloseStatus.values().length);
        assertEquals(TimeTakerCore.CloseStatus.CLOSED, TimeTakerCore.CloseStatus.valueOf("CLOSED"));
    }

    @Test
    void valueObjectsExpoeCampos() {
        TimeTakerCore.TextEdit e = new TimeTakerCore.TextEdit("x", 1);
        assertEquals("x", e.text);
        assertEquals(1, e.caret);

        TimeTakerCore.CloseResult r = new TimeTakerCore.CloseResult(TimeTakerCore.CloseStatus.NO_CLOCK, "t", -1);
        assertEquals("t", r.text);
        assertEquals(-1, r.caret);
        assertFalse(r.closed());
    }
}
