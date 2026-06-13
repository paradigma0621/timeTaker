package com.timetaker;

import org.junit.jupiter.api.Test;

import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.PlainDocument;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes headless de UndoController (features Undo/Ctrl+Z e Redo/Ctrl+Shift+Z).
 *
 * Usa um {@link PlainDocument} (modelo de texto, sem componente visual) para gerar
 * edicoes desfazi­veis, exercitando undo/redo sem depender de display.
 */
class UndoControllerTest {

    private static String text(Document d) {
        try {
            return d.getText(0, d.getLength());
        } catch (BadLocationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void undoSemHistoricoRetornaFalse() {
        UndoController uc = new UndoController(new PlainDocument());
        assertFalse(uc.undo()); // nada para desfazer
    }

    @Test
    void redoSemHistoricoRetornaFalse() {
        UndoController uc = new UndoController(new PlainDocument());
        assertFalse(uc.redo()); // nada para refazer
    }

    @Test
    void undoDesfazUltimaEdicao() throws Exception {
        Document doc = new PlainDocument();
        UndoController uc = new UndoController(doc);
        doc.insertString(0, "abc", null);
        assertEquals("abc", text(doc));

        assertTrue(uc.undo());
        assertEquals("", text(doc));
    }

    @Test
    void redoRefazEdicaoDesfeita() throws Exception {
        Document doc = new PlainDocument();
        UndoController uc = new UndoController(doc);
        doc.insertString(0, "abc", null);

        assertTrue(uc.undo());
        assertEquals("", text(doc));

        assertTrue(uc.redo());
        assertEquals("abc", text(doc));
    }

    @Test
    void undoEhRedoComMultiplosPassos() throws Exception {
        Document doc = new PlainDocument();
        UndoController uc = new UndoController(doc);
        doc.insertString(0, "a", null);
        doc.insertString(1, "b", null);
        doc.insertString(2, "c", null);
        assertEquals("abc", text(doc));

        // Tres desfazeres consecutivos.
        assertTrue(uc.undo());
        assertTrue(uc.undo());
        assertTrue(uc.undo());
        assertEquals("", text(doc));
        assertFalse(uc.undo()); // historico esgotado

        // Tres refazeres consecutivos.
        assertTrue(uc.redo());
        assertTrue(uc.redo());
        assertTrue(uc.redo());
        assertEquals("abc", text(doc));
        assertFalse(uc.redo()); // nada mais a refazer
    }

    @Test
    void suspendIgnoraEdicoesEResumeRetoma() throws Exception {
        Document doc = new PlainDocument();
        UndoController uc = new UndoController(doc);

        // Edicoes feitas enquanto suspenso nao entram no historico.
        uc.suspend();
        doc.insertString(0, "oculto", null);
        assertFalse(uc.undo()); // nada registrado durante a suspensao

        // Apos resume, novas edicoes voltam a ser desfaziveis (sem registro duplicado).
        uc.resume();
        doc.insertString(doc.getLength(), "X", null);
        assertTrue(uc.undo());
        assertEquals("oculto", text(doc)); // desfez so o "X"
        assertFalse(uc.undo());            // e nada mais
    }

    @Test
    void discardHistoryLimpaUndoERedo() throws Exception {
        Document doc = new PlainDocument();
        UndoController uc = new UndoController(doc);
        doc.insertString(0, "abc", null);

        uc.discardHistory();
        assertFalse(uc.undo()); // historico limpo: nada para desfazer
        assertEquals("abc", text(doc)); // documento inalterado
    }

    // ----------------------------------------------------- applyCompoundEdit (regressao Ctrl+Z apos Ctrl+I)

    /**
     * REGRESSAO: antes, applyEdit usava setText (remove tudo + insere tudo = dois edits);
     * um unico Ctrl+Z apos um Ctrl+I esvaziava o documento. Aqui, uma INSERCAO pura aplicada
     * pela mecanica de applyEdit (diff minimo + edit composto) deve ser revertida por um unico
     * undo() de volta ao texto original — NUNCA para vazio.
     */
    @Test
    void undoAposInsercaoRestauraTextoCompletoNaoVazio() throws Exception {
        Document doc = new PlainDocument();
        String t0 = "linha 1\nlinha 2\n"; // texto inicial nao-vazio (estilo arquivo carregado)
        doc.insertString(0, t0, null);
        UndoController uc = new UndoController(doc); // historico comeca vazio (como apos loadFile)

        String t1 = t0 + ":LOGBOOK:\nCLOCK: [2026-06-13 sex 09:00]\n:END:\n"; // como Ctrl+I
        TimeTakerCore.DiffEdit diff = TimeTakerCore.computeDiff(t0, t1);
        assertEquals(0, diff.removeLen);          // insercao pura
        assertFalse(diff.insertText.isEmpty());
        uc.applyCompoundEdit(diff.offset, diff.removeLen, diff.insertText);
        assertEquals(t1, text(doc));

        assertTrue(uc.undo());
        assertEquals(t0, text(doc)); // volta EXATAMENTE a T0, e nao vazio
        assertTrue(uc.redo());
        assertEquals(t1, text(doc)); // e e refazivel
    }

    /**
     * Edicao que REMOVE e INSERE no meio (ex.: reescrever um timestamp/duracao): um unico
     * undo() deve restaurar o texto original por completo, e nao apenas metade da edicao.
     */
    @Test
    void undoAposSubstituicaoNoMeioRestauraTextoCompleto() throws Exception {
        Document doc = new PlainDocument();
        String t0 = "abc DEF ghi";
        doc.insertString(0, t0, null);
        UndoController uc = new UndoController(doc);

        String t1 = "abc XYZW ghi"; // substitui "DEF" por "XYZW"
        TimeTakerCore.DiffEdit diff = TimeTakerCore.computeDiff(t0, t1);
        assertTrue(diff.removeLen > 0 && !diff.insertText.isEmpty()); // remove E insere
        uc.applyCompoundEdit(diff.offset, diff.removeLen, diff.insertText);
        assertEquals(t1, text(doc));

        assertTrue(uc.undo());
        assertEquals(t0, text(doc)); // um unico undo reverte tudo
        assertTrue(uc.redo());
        assertEquals(t1, text(doc));
    }

    /** Remocao pura (insertText vazio): cobre o ramo que pula o insertString. */
    @Test
    void undoAposRemocaoPuraRestauraTexto() throws Exception {
        Document doc = new PlainDocument();
        String t0 = "abcXYZdef";
        doc.insertString(0, t0, null);
        UndoController uc = new UndoController(doc);

        String t1 = "abcdef";
        TimeTakerCore.DiffEdit diff = TimeTakerCore.computeDiff(t0, t1);
        assertTrue(diff.removeLen > 0 && diff.insertText.isEmpty()); // remocao pura
        uc.applyCompoundEdit(diff.offset, diff.removeLen, diff.insertText);
        assertEquals(t1, text(doc));

        assertTrue(uc.undo());
        assertEquals(t0, text(doc));
    }

    /** Edicao vazia (sem remocao nem insercao) nao deve registrar nada no historico. */
    @Test
    void applyCompoundEditVazioNaoRegistraHistorico() throws Exception {
        Document doc = new PlainDocument();
        doc.insertString(0, "abc", null);
        UndoController uc = new UndoController(doc);

        uc.applyCompoundEdit(0, 0, ""); // no-op
        assertFalse(uc.undo());          // nada foi registrado
        assertEquals("abc", text(doc));
    }

    /** Offset invalido propaga como IllegalStateException (cobre o tratamento de BadLocation). */
    @Test
    void applyCompoundEditComOffsetInvalidoLancaIllegalState() throws Exception {
        Document doc = new PlainDocument();
        doc.insertString(0, "abc", null);
        UndoController uc = new UndoController(doc);

        assertThrows(IllegalStateException.class,
                () -> uc.applyCompoundEdit(100, 0, "x")); // offset alem do fim do documento
    }
}
