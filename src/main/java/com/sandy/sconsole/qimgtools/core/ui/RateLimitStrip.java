package com.sandy.sconsole.qimgtools.core.ui;

import com.sandy.sconsole.qimgtools.core.model.RateLimitNotice;
import com.sandy.sconsole.qimgtools.core.util.ModelNames;

import javax.swing.BorderFactory;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import java.awt.Color;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * A thin amber strip shown while an AI target or model is held back by a
 * rate limit, one line per limit with why and when it lifts. Wraps, and is
 * read-only. Use on the EDT.
 */
@SuppressWarnings( "serial" )
public final class RateLimitStrip extends JTextArea {

    private static final Color BG = new Color( 0xFFE0B2 ) ;
    private static final Color FG = new Color( 0x6D3A00 ) ;

    /** Clock time shown for a rate limit that lifts in an hour or more. */
    private static final DateTimeFormatter RESUME_TIME =
            DateTimeFormatter.ofPattern( "HH:mm" ) ;

    /** The names models are shown by. */
    private final ModelNames names ;

    public RateLimitStrip( ModelNames names ) {

        this.names = names ;
        setEditable( false ) ;
        setFocusable( false ) ;
        setLineWrap( true ) ;
        setWrapStyleWord( true ) ;
        setOpaque( true ) ;
        setBackground( BG ) ;
        setForeground( FG ) ;
        setFont( UIManager.getFont( "Label.font" ).deriveFont( 11f ) ) ;
        setBorder( BorderFactory.createEmptyBorder( 3, 8, 3, 8 ) ) ;
        setVisible( false ) ;
    }

    /**
     * Show {@code notices}; hide the strip when there are none, or when
     * {@code enabled} is false.
     */
    public void update( List<RateLimitNotice> notices, boolean enabled ) {

        if( notices.isEmpty() || !enabled ) {
            setVisible( false ) ;
            return ;
        }

        long          now  = System.currentTimeMillis() ;
        StringBuilder text = new StringBuilder( "Rate limit encountered" ) ;

        for( RateLimitNotice n : notices ) {
            text.append( "\n" ).append( n.agent().getDisplayName() ) ;
            if( n.model() != null ) text.append( " " ).append( names.of( n.model() ) ) ;
            text.append( ": " ).append( n.reason().replaceAll( "[.\\s]+$", "" ) )
                .append( " - resumes " ).append( resumesIn( n.until(), now ) ) ;
        }
        if( !text.toString().equals( getText() ) ) setText( text.toString() ) ;
        setVisible( true ) ;
    }

    /**
     * "in 4m 05s", or "at 05:30 (in 12h 44m)" when an hour or more away.
     */
    private static String resumesIn( long until, long now ) {

        long secs = Math.max( 0, ( until - now + 999 ) / 1000 ) ;
        if( secs < 60 ) return "in " + secs + "s" ;
        if( secs < 3600 ) {
            return String.format( "in %dm %02ds", secs / 60, secs % 60 ) ;
        }
        String at = RESUME_TIME.format( Instant.ofEpochMilli( until )
                                               .atZone( ZoneId.systemDefault() ) ) ;
        return String.format( "at %s (in %dh %02dm)", at, secs / 3600,
                              ( secs / 60 ) % 60 ) ;
    }
}
