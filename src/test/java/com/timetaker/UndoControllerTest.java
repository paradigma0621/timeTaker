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
    void discardHistoryLimpaUndoERedo() throws Exception {
        Document doc = new PlainDocument();
        UndoController uc = new UndoController(doc);
        doc.insertString(0, "abc", null);

        uc.discardHistory();
        assertFalse(uc.undo()); // historico limpo: nada para desfazer
        assertEquals("abc", text(doc)); // documento inalterado
    }
}
