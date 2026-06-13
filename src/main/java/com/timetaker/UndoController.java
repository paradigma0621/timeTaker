package com.timetaker;

import javax.swing.text.Document;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;

/**
 * Encapsula o historico de desfazer/refazer (undo/redo) de um {@link Document}.
 *
 * Nao depende de componentes visuais: opera sobre o modelo de texto (Document) e o
 * {@link UndoManager}, ambos utilizaveis sem display. Por isso pode ser exercitado em
 * testes headless, cobrindo a logica das features de Undo (Ctrl+Z) e Redo (Ctrl+Shift+Z).
 */
public final class UndoController {

    private final UndoManager manager = new UndoManager();
    private final Document document;

    /** Passa a acompanhar as edicoes do documento informado. */
    public UndoController(Document document) {
        this.document = document;
        document.addUndoableEditListener(manager);
    }

    /**
     * Suspende o registro de edicoes desfaziveis: enquanto suspenso, mudancas no documento
     * (ex.: aplicacao de cores/atributos por estilo) NAO entram no historico de undo/redo.
     * Use em par com {@link #resume()} ao redor de mutacoes puramente visuais.
     */
    public void suspend() {
        document.removeUndoableEditListener(manager);
    }

    /** Retoma o registro de edicoes desfaziveis apos um {@link #suspend()}. */
    public void resume() {
        document.removeUndoableEditListener(manager); // evita registro duplicado
        document.addUndoableEditListener(manager);
    }

    /**
     * Desfaz a ultima edicao. Retorna true se algo foi desfeito; false se nao havia
     * historico (a excecao do UndoManager e tratada silenciosamente).
     */
    public boolean undo() {
        try {
            manager.undo();
            return true;
        } catch (CannotUndoException ex) {
            return false;
        }
    }

    /**
     * Refaz a ultima edicao desfeita. Retorna true se algo foi refeito; false se nao
     * havia o que refazer.
     */
    public boolean redo() {
        try {
            manager.redo();
            return true;
        } catch (CannotRedoException ex) {
            return false;
        }
    }

    /** Limpa todo o historico de undo/redo (usado ao trocar o conteudo do documento). */
    public void discardHistory() {
        manager.discardAllEdits();
    }
}
