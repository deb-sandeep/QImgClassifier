package com.sandy.sconsole.qimgtools.apps.transcriber.ui;

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
 * A transcription rendered as the question will read (feature-design.md
 * §8.1): a {@link JFXPanel} holding a {@link WebView} that loads
 * {@code web/view.html} from the classpath once (markdown-it, MathJax with
 * mhchem, SmilesDrawer, all bundled) and draws each markdown given to
 * {@link #show}. Make it on the EDT; {@link #show} may be called from any
 * thread, before the page has loaded too.
 */
@Slf4j
@SuppressWarnings( "serial" )
final class MarkdownView extends JFXPanel {

    private static final String PAGE = "/apps/transcriber/web/view.html" ;

    private static final ObjectMapper JSON = new ObjectMapper() ;

    // Touched only on the FX thread.
    private WebEngine engine ;
    private boolean   ready ;
    private String    shown = "" ;

    MarkdownView() {

        // The view comes and goes with its tab; FX must outlive that.
        Platform.setImplicitExit( false ) ;
        Platform.runLater( this::build ) ;
    }

    /** Render {@code markdown}; null or blank shows nothing. */
    void show( String markdown ) {

        String text = markdown == null ? "" : markdown ;
        Platform.runLater( () -> {
            shown = text ;
            if( ready ) render() ;
        } ) ;
    }

    private void build() {

        URL      page = MarkdownView.class.getResource( PAGE ) ;
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
        if( page == null ) {
            log.error( "{} is not on the classpath", PAGE ) ;
            engine.loadContent( "<p style='color:red'>" + PAGE +
                                " not found</p>" ) ;
            return ;
        }
        engine.load( page.toExternalForm() ) ;
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
