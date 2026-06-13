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
        assertEquals(0, r.lineStart);
    }

    @Test
    void closeOpenClock_abertoAcimaDeFechadosDeOutroProjeto() {
        // Com secoes de projeto, o registro em aberto pode estar acima de registros ja
        // fechados: a varredura deve ignorar os fechados e fechar o aberto.
        String text = "* Projeto A\n"
                + "CLOCK: [2021-11-16 ter 09:00] tarefa A\n"
                + "\n"
                + "* Projeto B\n"
                + "CLOCK: [2021-11-16 ter 07:00]--[2021-11-16 ter 08:00] =>  1:00 tarefa B\n";
        TimeTakerCore.CloseResult r = TimeTakerCore.closeOpenClock(text, cal(2021, 11, 16, 10, 0, 0));
        assertEquals(TimeTakerCore.CloseStatus.CLOSED, r.status);
        assertEquals("* Projeto A\n"
                + "CLOCK: [2021-11-16 ter 09:00]--[2021-11-16 ter 10:00] =>  1:00 tarefa A\n"
                + "\n"
                + "* Projeto B\n"
                + "CLOCK: [2021-11-16 ter 07:00]--[2021-11-16 ter 08:00] =>  1:00 tarefa B\n", r.text);
        assertEquals(text.indexOf("CLOCK"), r.lineStart);
    }

    @Test
    void closeOpenClock_abertoDentroDeDrawer() {
        // O CLOCK em aberto vive dentro de um drawer ":LOGBOOK:"/":END:": a varredura de
        // tras para frente ignora as linhas do drawer e fecha o registro corretamente.
        String text = "* A\n"
                + ":LOGBOOK:\n"
                + "CLOCK: [2021-11-16 ter 09:00] tarefa\n"
                + ":END:\n";
        TimeTakerCore.CloseResult r = TimeTakerCore.closeOpenClock(text, cal(2021, 11, 16, 10, 0, 0));
        assertEquals(TimeTakerCore.CloseStatus.CLOSED, r.status);
        assertEquals("* A\n"
                + ":LOGBOOK:\n"
                + "CLOCK: [2021-11-16 ter 09:00]--[2021-11-16 ter 10:00] =>  1:00 tarefa\n"
                + ":END:\n", r.text);
    }

    @Test
    void closeOpenClock_todosFechadosEmVariasLinhas() {
        String text = "* A\n"
                + "CLOCK: [2021-11-16 ter 07:00]--[2021-11-16 ter 08:00] =>  1:00\n"
                + "CLOCK: [2021-11-16 ter 08:00]--[2021-11-16 ter 09:00] =>  1:00\n";
        TimeTakerCore.CloseResult r = TimeTakerCore.closeOpenClock(text, cal(2021, 11, 16, 10, 0, 0));
        assertEquals(TimeTakerCore.CloseStatus.ALREADY_CLOSED, r.status);
    }

    // ----------------------------------------------------- insertClockLine

    @Test
    void insertClockLine_documentoVazio() {
        // Sem drawer pre-existente: cria um novo drawer ":LOGBOOK:"/":END:" envolvendo o CLOCK.
        TimeTakerCore.TextEdit e = TimeTakerCore.insertClockLine("", cal(2021, 11, 16, 9, 0, 0));
        assertEquals(":LOGBOOK:\nCLOCK: [2021-11-16 ter 09:00] \n:END:", e.text);
        // Cursor apos o espaco do registro, logo antes do "\n:END:".
        assertEquals(e.text.indexOf("\n:END:"), e.caret);
    }

    @Test
    void insertClockLine_fechaTarefaEmAberto() {
        // CLOCK em aberto solto (sem drawer): fecha o aberto e cria um novo drawer para a
        // nova entrada no fim do documento.
        String text = "CLOCK: [2021-11-16 ter 09:00] ";
        TimeTakerCore.TextEdit e = TimeTakerCore.insertClockLine(text, cal(2021, 11, 16, 10, 30, 0));
        assertEquals(
                "CLOCK: [2021-11-16 ter 09:00]--[2021-11-16 ter 10:30] =>  1:30\n"
                        + ":LOGBOOK:\n"
                        + "CLOCK: [2021-11-16 ter 10:30] \n"
                        + ":END:",
                e.text);
        assertEquals(e.text.indexOf("\n:END:"), e.caret);
    }

    @Test
    void insertClockLine_conteudoSemNewlineFinal() {
        // Sem CLOCK em aberto, conteudo existente sem '\n' no fim: deve anteceder '\n'.
        TimeTakerCore.TextEdit e = TimeTakerCore.insertClockLine("anotacao", cal(2021, 11, 16, 9, 0, 0));
        assertEquals("anotacao\n:LOGBOOK:\nCLOCK: [2021-11-16 ter 09:00] \n:END:", e.text);
    }

    @Test
    void insertClockLine_conteudoComNewlineFinal() {
        TimeTakerCore.TextEdit e = TimeTakerCore.insertClockLine("anotacao\n", cal(2021, 11, 16, 9, 0, 0));
        assertEquals("anotacao\n:LOGBOOK:\nCLOCK: [2021-11-16 ter 09:00] \n:END:", e.text);
    }

    @Test
    void insertClockLine_drawerExistenteRecebeNovoClockNoTopo() {
        // Ja existe um drawer: o clock-in mais recente entra logo apos ":LOGBOOK:" (topo).
        String text = ":LOGBOOK:\n"
                + "CLOCK: [2021-11-16 ter 07:00]--[2021-11-16 ter 08:00] =>  1:00\n"
                + ":END:\n";
        TimeTakerCore.TextEdit e = TimeTakerCore.insertClockLine(text, cal(2021, 11, 16, 9, 0, 0));
        assertEquals(":LOGBOOK:\n"
                + "CLOCK: [2021-11-16 ter 09:00] \n"
                + "CLOCK: [2021-11-16 ter 07:00]--[2021-11-16 ter 08:00] =>  1:00\n"
                + ":END:\n", e.text);
        String clock = "CLOCK: [2021-11-16 ter 09:00] ";
        assertEquals(e.text.indexOf(clock) + clock.length(), e.caret);
    }

    @Test
    void insertClockLine_logbookSoltoSemEndNaUltimaLinha() {
        // ":LOGBOOK:" digitado como ultima linha solta, sem '\n' e sem ":END:": o registro
        // entra logo abaixo (cobre o ramo sem quebra de linha apos ":LOGBOOK:").
        TimeTakerCore.TextEdit e = TimeTakerCore.insertClockLine("texto\n:LOGBOOK:", cal(2021, 11, 16, 9, 0, 0));
        assertEquals("texto\n:LOGBOOK:\nCLOCK: [2021-11-16 ter 09:00] \n", e.text);
        String clock = "CLOCK: [2021-11-16 ter 09:00] ";
        assertEquals(e.text.indexOf(clock) + clock.length(), e.caret);
    }

    // ----------------------------------------------------- Projetos (estilo Org-mode)

    @Test
    void isHeading_eHeadingTitle() {
        assertTrue(TimeTakerCore.isHeading("* Projeto"));
        assertTrue(TimeTakerCore.isHeading("** Subnivel"));
        assertTrue(TimeTakerCore.isHeading("# Projeto"));
        assertTrue(TimeTakerCore.isHeading("### Projeto"));
        assertFalse(TimeTakerCore.isHeading("*sem espaco"));
        assertFalse(TimeTakerCore.isHeading("* "));            // sem titulo
        assertFalse(TimeTakerCore.isHeading("texto comum"));
        assertFalse(TimeTakerCore.isHeading("CLOCK: [2021-11-16 ter 09:00] "));

        assertEquals("Projeto A", TimeTakerCore.headingTitle("* Projeto A "));
        assertEquals("Projeto B", TimeTakerCore.headingTitle("## Projeto B"));
        assertNull(TimeTakerCore.headingTitle("nao e cabecalho"));
    }

    @Test
    void headingLineStartFor() {
        String text = "* A\nlinha\n* B\noutra\n";
        assertEquals(0, TimeTakerCore.headingLineStartFor(text, 0));  // sobre o proprio cabecalho
        assertEquals(0, TimeTakerCore.headingLineStartFor(text, 6));  // linha sob "* A"
        assertEquals(10, TimeTakerCore.headingLineStartFor(text, 15)); // linha sob "* B"
        assertEquals(10, TimeTakerCore.headingLineStartFor(text, 999)); // caret alem do fim: clampa
        assertEquals(0, TimeTakerCore.headingLineStartFor(text, -5));   // caret negativo: clampa

        assertEquals(-1, TimeTakerCore.headingLineStartFor("preambulo\n* A\n", 3)); // acima do 1o cabecalho
        assertEquals(-1, TimeTakerCore.headingLineStartFor("sem projetos", 5));
    }

    @Test
    void nextHeadingStart() {
        String text = "* A\nx\n* B\ny";
        assertEquals(6, TimeTakerCore.nextHeadingStart(text, 3));  // acha "* B"
        assertEquals(text.length(), TimeTakerCore.nextHeadingStart(text, 6)); // depois de "* B" nao ha outro
        assertEquals(3, TimeTakerCore.nextHeadingStart("x\n\n* C\n", 0)); // linha em branco nao e cabecalho; segue ate "* C"
    }

    @Test
    void insertClockLineComCaret_semCabecalhoCaiNoLegado() {
        TimeTakerCore.TextEdit e = TimeTakerCore.insertClockLine("anotacao", 3, cal(2021, 11, 16, 9, 0, 0));
        assertEquals("anotacao\n:LOGBOOK:\nCLOCK: [2021-11-16 ter 09:00] \n:END:", e.text);
        assertEquals(e.text.indexOf("\n:END:"), e.caret);
    }

    @Test
    void insertClockLineComCaret_insereNoFimDaSecaoDoCursor() {
        // Secao sem drawer ainda: cria um novo drawer no fim da secao do cursor.
        String text = "* Projeto A\n"
                + "CLOCK: [2021-11-16 ter 07:00]--[2021-11-16 ter 08:00] =>  1:00\n"
                + "\n"
                + "* Projeto B\n"
                + "notas\n";
        // Caret sobre o titulo "* Projeto A".
        TimeTakerCore.TextEdit e = TimeTakerCore.insertClockLine(text, 3, cal(2021, 11, 16, 9, 0, 0));
        assertEquals("* Projeto A\n"
                + "CLOCK: [2021-11-16 ter 07:00]--[2021-11-16 ter 08:00] =>  1:00\n"
                + ":LOGBOOK:\n"
                + "CLOCK: [2021-11-16 ter 09:00] \n"
                + ":END:\n"
                + "\n"
                + "* Projeto B\n"
                + "notas\n", e.text);
        // Caret logo apos o espaco do novo registro.
        String clock = "CLOCK: [2021-11-16 ter 09:00] ";
        assertEquals(e.text.indexOf(clock) + clock.length(), e.caret);
    }

    @Test
    void insertClockLineComCaret_drawerExistenteNaSecaoRecebeNovoClockNoTopo() {
        // A secao do cursor ja tem um drawer: o novo registro entra no topo do drawer,
        // mesmo havendo outra secao (com seu proprio drawer) abaixo.
        String text = "* Projeto A\n"
                + ":LOGBOOK:\n"
                + "CLOCK: [2021-11-16 ter 07:00]--[2021-11-16 ter 08:00] =>  1:00\n"
                + ":END:\n"
                + "* Projeto B\n"
                + ":LOGBOOK:\n"
                + ":END:\n";
        // Caret sob "* Projeto A".
        TimeTakerCore.TextEdit e = TimeTakerCore.insertClockLine(text, 5, cal(2021, 11, 16, 9, 0, 0));
        assertEquals("* Projeto A\n"
                + ":LOGBOOK:\n"
                + "CLOCK: [2021-11-16 ter 09:00] \n"
                + "CLOCK: [2021-11-16 ter 07:00]--[2021-11-16 ter 08:00] =>  1:00\n"
                + ":END:\n"
                + "* Projeto B\n"
                + ":LOGBOOK:\n"
                + ":END:\n", e.text);
        String clock = "CLOCK: [2021-11-16 ter 09:00] ";
        assertEquals(e.text.indexOf(clock) + clock.length(), e.caret);
    }

    @Test
    void insertClockLineComCaret_naoReaproveitaDrawerDeOutraSecao() {
        // A secao do cursor NAO tem drawer, mas uma secao POSTERIOR tem: a busca e limitada
        // a secao do cursor (findLogbookOpenLine com to=sectionEnd), entao a secao A cria seu
        // proprio drawer novo e o drawer da secao B permanece intacto.
        String text = "* Projeto A\n"
                + "notas\n"
                + "* Projeto B\n"
                + ":LOGBOOK:\n"
                + "CLOCK: [2021-11-16 ter 07:00]--[2021-11-16 ter 08:00] =>  1:00\n"
                + ":END:\n";
        // Caret sob "* Projeto A".
        TimeTakerCore.TextEdit e = TimeTakerCore.insertClockLine(text, 3, cal(2021, 11, 16, 9, 0, 0));
        assertEquals("* Projeto A\n"
                + "notas\n"
                + ":LOGBOOK:\n"
                + "CLOCK: [2021-11-16 ter 09:00] \n"
                + ":END:\n"
                + "* Projeto B\n"
                + ":LOGBOOK:\n"
                + "CLOCK: [2021-11-16 ter 07:00]--[2021-11-16 ter 08:00] =>  1:00\n"
                + ":END:\n", e.text);
        String clock = "CLOCK: [2021-11-16 ter 09:00] ";
        assertEquals(e.text.indexOf(clock) + clock.length(), e.caret);
    }

    @Test
    void insertClockLineComCaret_secaoVaziaInsereLogoAposTitulo() {
        // Cabecalho no fim do documento, sem '\n' final e sem conteudo na secao.
        TimeTakerCore.TextEdit e = TimeTakerCore.insertClockLine("* A", 1, cal(2021, 11, 16, 9, 0, 0));
        assertEquals("* A\n:LOGBOOK:\nCLOCK: [2021-11-16 ter 09:00] \n:END:", e.text);
        assertEquals(e.text.indexOf("\n:END:"), e.caret);
    }

    @Test
    void insertClockLineComCaret_ultimaSecaoComConteudoSemNewlineFinal() {
        // Ultima linha da secao sem '\n' final: cria o drawer no fim do documento.
        TimeTakerCore.TextEdit e = TimeTakerCore.insertClockLine("* A\nnotas", 6, cal(2021, 11, 16, 9, 0, 0));
        assertEquals("* A\nnotas\n:LOGBOOK:\nCLOCK: [2021-11-16 ter 09:00] \n:END:", e.text);
        assertEquals(e.text.indexOf("\n:END:"), e.caret);
    }

    @Test
    void insertClockLineComCaret_fechaAbertoDeOutroProjetoAcima() {
        // Clock em aberto no projeto A (antes do cabecalho do cursor): e fechado e o
        // indice do cabecalho de B e deslocado pelo texto inserido no fechamento.
        String text = "* A\n"
                + "CLOCK: [2021-11-16 ter 09:00] x\n"
                + "\n"
                + "* B";
        TimeTakerCore.TextEdit e = TimeTakerCore.insertClockLine(text, text.length(), cal(2021, 11, 16, 10, 0, 0));
        assertEquals("* A\n"
                + "CLOCK: [2021-11-16 ter 09:00]--[2021-11-16 ter 10:00] =>  1:00 x\n"
                + "\n"
                + "* B\n"
                + ":LOGBOOK:\n"
                + "CLOCK: [2021-11-16 ter 10:00] \n"
                + ":END:", e.text);
        assertEquals(e.text.indexOf("\n:END:"), e.caret);
    }

    @Test
    void insertClockLineComCaret_fechaAbertoDaPropriaSecao() {
        // Clock em aberto na mesma secao (depois do cabecalho): fecha sem deslocar o
        // indice do cabecalho e cria o drawer da nova entrada logo abaixo.
        String text = "* A\n"
                + "CLOCK: [2021-11-16 ter 09:00] tarefa\n";
        TimeTakerCore.TextEdit e = TimeTakerCore.insertClockLine(text, 0, cal(2021, 11, 16, 10, 30, 0));
        assertEquals("* A\n"
                + "CLOCK: [2021-11-16 ter 09:00]--[2021-11-16 ter 10:30] =>  1:30 tarefa\n"
                + ":LOGBOOK:\n"
                + "CLOCK: [2021-11-16 ter 10:30] \n"
                + ":END:\n", e.text);
    }

    // ----------------------------------------------------- clockReport

    @Test
    void clockReport_semRegistros() {
        assertEquals("Nenhum registro CLOCK encontrado.", TimeTakerCore.clockReport(""));
        assertEquals("Nenhum registro CLOCK encontrado.", TimeTakerCore.clockReport("so texto\n"));
    }

    @Test
    void clockReport_formatoDeUmProjeto() {
        String in = "* A\nCLOCK: [2021-11-16 ter 09:00]--[2021-11-16 ter 10:00] =>  1:00\n";
        assertEquals("Tempo por projeto:\n\n"
                + "  A        1:00\n"
                + "  Total    1:00\n", TimeTakerCore.clockReport(in));
    }

    @Test
    void clockReport_variosProjetosAbertosEFallbacks() {
        String in = "preambulo\n"
                // Fora de qualquer projeto; duracao gravada defasada (9:99) e ignorada:
                // o relatorio recalcula 0:30 a partir dos horarios.
                + "CLOCK: [2021-11-16 ter 06:00]--[2021-11-16 ter 06:30] =>  9:99\n"
                + "* Projeto A\n"
                + "CLOCK: [2021-11-16 ter 09:00]--[2021-11-16 ter 10:00] =>  0:01\n"
                // Horarios ilegiveis: usa a duracao gravada (0:45).
                + "CLOCK: [ruim]--[ruim] =>  0:45\n"
                + "* Projeto B\n"
                + "CLOCK: [2021-11-16 ter 11:00] em curso\n"
                + "* Projeto C\n"
                + "CLOCK: [malformado sem fechamento\n";
        String report = TimeTakerCore.clockReport(in);

        assertTrue(report.contains("(sem projeto)"));
        assertTrue(report.contains("0:30"));            // recalculado, nao 9:99
        assertTrue(report.contains("Projeto A"));
        assertTrue(report.contains("1:45"));            // 1:00 recalculado + 0:45 gravado
        assertTrue(report.contains("Projeto B"));
        assertTrue(report.contains("(em andamento)"));  // aberto nao soma, mas e indicado
        assertTrue(report.contains("Projeto C"));       // aparece mesmo sem registro valido
        assertTrue(report.contains("Total"));
        assertTrue(report.contains("2:15"));            // 0:30 + 1:45
    }

    @Test
    void clockReport_entradaValidaSaidaIlegivelUsaDuracaoGravada() {
        String in = "* A\nCLOCK: [2021-11-16 ter 09:00]--[ruim] =>  0:05\n";
        String report = TimeTakerCore.clockReport(in);
        assertTrue(report.contains("0:05"));
    }

    @Test
    void clockReport_projetoSemRegistrosApareceZerado() {
        String in = "* Vazio\n* Cheio\nCLOCK: [2021-11-16 ter 09:00]--[2021-11-16 ter 09:10] =>  0:10\n";
        String report = TimeTakerCore.clockReport(in);
        assertTrue(report.contains("Vazio"));
        assertTrue(report.contains("0:00"));
        assertTrue(report.contains("0:10"));
    }

    @Test
    void clockReport_ignoraLinhasDoDrawer() {
        // As linhas ":LOGBOOK:"/":END:" nao casam o padrao CLOCK e sao ignoradas; o
        // cabecalho continua sendo detectado mesmo com o drawer no meio.
        String in = "* A\n"
                + ":LOGBOOK:\n"
                + "CLOCK: [2021-11-16 ter 09:00]--[2021-11-16 ter 10:00] =>  1:00\n"
                + ":END:\n";
        assertEquals("Tempo por projeto:\n\n"
                + "  A        1:00\n"
                + "  Total    1:00\n", TimeTakerCore.clockReport(in));
    }

    // ----------------------------------------------------- clockReport (hierarquia cumulativa)

    /** Devolve a primeira linha do relatorio que contem {@code label}, ou null se nao houver. */
    private static String reportLine(String report, String label) {
        for (String l : report.split("\n")) {
            if (l.contains(label)) {
                return l;
            }
        }
        return null;
    }

    @Test
    void clockReport_hierarquiaSomaCumulativaEIndenta() {
        // Pai (1:00 proprio) com subsecao Filho (0:30): o Pai deve exibir 1:30 cumulativo.
        String in = "* Pai\n"
                + "CLOCK: [2021-11-16 ter 09:00]--[2021-11-16 ter 10:00] =>  1:00\n"
                + "** Filho\n"
                + "CLOCK: [2021-11-16 ter 10:00]--[2021-11-16 ter 10:30] =>  0:30\n";
        assertEquals("Tempo por projeto:\n\n"
                + "  Pai        1:30\n"
                + "    Filho    0:30\n"
                + "  Total      1:30\n", TimeTakerCore.clockReport(in));
    }

    @Test
    void clockReport_hierarquiaTresNiveis() {
        // Topo (0:10) > Meio (0:20) > Folha (0:30): o cumulativo sobe a cada nivel.
        String in = "* Topo\n"
                + "CLOCK: [2021-11-16 ter 09:00]--[2021-11-16 ter 09:10] =>  0:10\n"
                + "** Meio\n"
                + "CLOCK: [2021-11-16 ter 09:10]--[2021-11-16 ter 09:30] =>  0:20\n"
                + "*** Folha\n"
                + "CLOCK: [2021-11-16 ter 09:30]--[2021-11-16 ter 10:00] =>  0:30\n";
        String report = TimeTakerCore.clockReport(in);
        assertTrue(reportLine(report, "Topo").endsWith("1:00"));   // 0:10 + 0:20 + 0:30
        assertTrue(reportLine(report, "Meio").endsWith("0:50"));   // 0:20 + 0:30
        assertTrue(reportLine(report, "Folha").endsWith("0:30"));  // proprio
        assertTrue(reportLine(report, "Total").endsWith("1:00"));
    }

    @Test
    void clockReport_irmaosNoMesmoNivelSomamNoPai() {
        // Pai sem tempo proprio, com dois filhos irmaos: o Pai exibe a soma dos dois.
        String in = "* Pai\n"
                + "** A\n"
                + "CLOCK: [2021-11-16 ter 09:00]--[2021-11-16 ter 09:20] =>  0:20\n"
                + "** B\n"
                + "CLOCK: [2021-11-16 ter 09:20]--[2021-11-16 ter 10:00] =>  0:40\n";
        String report = TimeTakerCore.clockReport(in);
        assertTrue(reportLine(report, "Pai").endsWith("1:00"));  // 0:20 + 0:40
        assertTrue(reportLine(report, "  A").endsWith("0:20"));  // indentada (filha)
        assertTrue(reportLine(report, "  B").endsWith("0:40"));
        assertTrue(reportLine(report, "Total").endsWith("1:00"));
    }

    @Test
    void clockReport_subsecaoVoltaParaNivelMaisRaso() {
        // Apos a subsecao S1 de P1, um novo cabecalho de nivel 1 (P2) fecha P1: P2 e raiz
        // (nao filho de P1) e o tempo de S1 nao vaza para P2.
        String in = "* P1\n"
                + "** S1\n"
                + "CLOCK: [2021-11-16 ter 09:00]--[2021-11-16 ter 09:30] =>  0:30\n"
                + "* P2\n"
                + "CLOCK: [2021-11-16 ter 10:00]--[2021-11-16 ter 11:00] =>  1:00\n";
        String report = TimeTakerCore.clockReport(in);
        assertTrue(reportLine(report, "P1").endsWith("0:30"));   // so a subsecao S1
        assertTrue(reportLine(report, "S1").endsWith("0:30"));
        assertTrue(reportLine(report, "P2").endsWith("1:00"));   // proprio, sem heranca de P1
        // P2 e raiz: aparece sem indentacao (dois espacos do gabarito + titulo).
        assertTrue(reportLine(report, "P2").startsWith("  P2"));
        assertTrue(reportLine(report, "Total").endsWith("1:30")); // 0:30 + 1:00
    }

    @Test
    void clockReport_semProjetoComHierarquia() {
        // Clock antes do primeiro cabecalho entra em "(sem projeto)" e nao herda subsecoes.
        String in = "preambulo\n"
                + "CLOCK: [2021-11-16 ter 08:00]--[2021-11-16 ter 08:15] =>  0:15\n"
                + "* P\n"
                + "CLOCK: [2021-11-16 ter 09:00]--[2021-11-16 ter 09:45] =>  0:45\n"
                + "** Sub\n"
                + "CLOCK: [2021-11-16 ter 10:00]--[2021-11-16 ter 10:05] =>  0:05\n";
        String report = TimeTakerCore.clockReport(in);
        assertTrue(reportLine(report, "(sem projeto)").endsWith("0:15"));
        assertTrue(reportLine(report, "  P ").endsWith("0:50")); // 0:45 + 0:05 da subsecao
        assertTrue(reportLine(report, "Sub").endsWith("0:05"));
        assertTrue(reportLine(report, "Total").endsWith("1:05")); // 0:15 + 0:50
    }

    @Test
    void clockReport_clockAbertoEmSubsecaoMarcaAncestrais() {
        // Clock em aberto numa subsecao: tanto a subsecao quanto o ancestral sao marcados
        // "(em andamento)", mesmo o ancestral nao tendo registro aberto proprio.
        String in = "* Pai\n"
                + "CLOCK: [2021-11-16 ter 09:00]--[2021-11-16 ter 09:30] =>  0:30\n"
                + "** Filho\n"
                + "CLOCK: [2021-11-16 ter 11:00] em curso\n";
        String report = TimeTakerCore.clockReport(in);
        assertTrue(reportLine(report, "Pai").contains("(em andamento)"));
        assertTrue(reportLine(report, "Filho").contains("(em andamento)"));
        assertTrue(reportLine(report, "Pai").contains("0:30")); // aberto nao soma tempo
        assertTrue(reportLine(report, "Total").contains("0:30"));
    }

    @Test
    void clockReport_paiComAbertoProprioEFilhoMantemEmAndamento() {
        // O Pai ja tem registro ABERTO proprio (cumOpen vem de ownOpen) e ainda assim
        // possui subsecao: ao acumular o filho, cumOpen ja esta verdadeiro no pai.
        String in = "* Pai\n"
                + "CLOCK: [2021-11-16 ter 09:00] em curso\n"
                + "** Filho\n"
                + "CLOCK: [2021-11-16 ter 10:00]--[2021-11-16 ter 10:30] =>  0:30\n";
        String report = TimeTakerCore.clockReport(in);
        assertTrue(reportLine(report, "Pai").contains("(em andamento)")); // aberto proprio
        assertTrue(reportLine(report, "Pai").contains("0:30"));            // soma do filho
        assertFalse(reportLine(report, "Filho").contains("(em andamento)"));
        assertTrue(reportLine(report, "Total").contains("0:30"));
    }

    @Test
    void clockReport_doisRegistrosNoPreambuloReusamSemProjeto() {
        // Dois clocks antes do primeiro cabecalho: o segundo reaproveita o no
        // "(sem projeto)" ja criado (ramo semProjeto != null).
        String in = "preambulo\n"
                + "CLOCK: [2021-11-16 ter 08:00]--[2021-11-16 ter 08:15] =>  0:15\n"
                + "CLOCK: [2021-11-16 ter 08:30]--[2021-11-16 ter 08:45] =>  0:15\n";
        String report = TimeTakerCore.clockReport(in);
        assertTrue(reportLine(report, "(sem projeto)").endsWith("0:30")); // 0:15 + 0:15
        assertTrue(reportLine(report, "Total").endsWith("0:30"));
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
    void recalculate_ignoraLinhasDoDrawer() {
        // As linhas ":LOGBOOK:"/":END:" nao casam o padrao e ficam intactas; apenas as
        // duracoes dos registros CLOCK dentro do drawer sao recalculadas.
        String in = ":LOGBOOK:\n"
                + "CLOCK: [2021-11-16 ter 09:00]--[2021-11-16 ter 10:30] =>  0:01\n"
                + ":END:\n";
        String out = ":LOGBOOK:\n"
                + "CLOCK: [2021-11-16 ter 09:00]--[2021-11-16 ter 10:30] =>  1:30\n"
                + ":END:\n";
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

        TimeTakerCore.CloseResult r = new TimeTakerCore.CloseResult(TimeTakerCore.CloseStatus.NO_CLOCK, "t", -1, -1);
        assertEquals("t", r.text);
        assertEquals(-1, r.caret);
        assertEquals(-1, r.lineStart);
        assertFalse(r.closed());
    }
}
