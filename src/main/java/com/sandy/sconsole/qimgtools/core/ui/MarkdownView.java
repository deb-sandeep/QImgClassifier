package com.sandy.sconsole.qimgtools.core.ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import lombok.extern.slf4j.Slf4j;

import java.net.URL;

/**
 * A transcription rendered as the question will read (the transcriber's
 * feature-design.md §8.1): a {@link JFXPanel} holding a {@link WebView} that
 * loads a page from the classpath once and draws each markdown given to
 * {@link #show} by calling the page's {@code show( markdown )}. The page is
 * the owning app's: QTranscriber's {@code web/view.html} (markdown-it, MathJax
 * with mhchem, SmilesDrawer, all bundled). Make it on the EDT; {@link #show}
 * may be called from any thread, before the page has loaded too.
 */
@Slf4j
@SuppressWarnings( "serial" )
public final class MarkdownView extends JFXPanel {

    private static final ObjectMapper JSON = new ObjectMapper() ;

    /** Classpath location of the page that renders. */
    private final String page ;

    // Touched only on the FX thread.
    private WebEngine engine ;
    private boolean   ready ;
    private String    shown = "" ;

    /** @param page classpath location of the page that renders */
    public MarkdownView( String page ) {

        this.page = page ;

        // The view comes and goes with its tab; FX must outlive that.
        Platform.setImplicitExit( false ) ;
        Platform.runLater( this::build ) ;
    }

    /** Render {@code markdown}; null or blank shows nothing. */
    public void show( String markdown ) {

        String text = markdown == null ? "" : markdown ;
        Platform.runLater( () -> {
            shown = text ;
            if( ready ) render() ;
        } ) ;
    }

    private void build() {

        URL      url  = MarkdownView.class.getResource( page ) ;
        WebView  view = new WebView() ;

        engine = view.getEngine() ;
        view.setContextMenuEnabled( false ) ;
        engine.getLoadWorker().stateProperty().addListener(
                ( o, before, state ) -> {
            if( state == Worker.State.SUCCEEDED ) {
                ready = true ;
                render() ;
            }
            else if( state == Worker.State.FAILED ) {
                log.error( "Could not load the rendered view {}: {}", page,
                           engine.getLoadWorker().getException() ) ;
            }
        } ) ;
        setScene( new Scene( view ) ) ;
        if( url == null ) {
            log.error( "{} is not on the classpath", page ) ;
            engine.loadContent( "<p style='color:red'>" + page +
                                " not found</p>" ) ;
            return ;
        }
        engine.load( url.toExternalForm() ) ;
    }

    /** Draw {@link #shown}, on the FX thread, once the page is loaded. */
    private void render() {

        try {
            engine.executeScript( "show(" + literal( shown ) + ")" ) ;
        }
        catch( RuntimeException e ) {
            log.error( "Could not render a transcription", e ) ;
        }
    }

    /** {@code s} as a JavaScript string literal. */
    private static String literal( String s ) {

        try {
            return JSON.writeValueAsString( s )
                       .replace( " ", "\\u2028" )
                       .replace( " ", "\\u2029" ) ;
        }
        catch( JsonProcessingException e ) {
            throw new IllegalStateException( e ) ;
        }
    }
}
