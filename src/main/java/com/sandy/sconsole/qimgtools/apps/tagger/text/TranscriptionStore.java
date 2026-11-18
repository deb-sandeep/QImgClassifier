package com.sandy.sconsole.qimgtools.apps.tagger.text;

import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Reads questions' transcriptions from the local {@code sconsolenxt} database
 * ({@code question_xcription}), the text the regression models were trained
 * on and the LLM is shown. The database is the owner's copy, kept in sync
 * with production; this only reads it. {@code content} is returned exactly as
 * stored: an LCT question's already holds its passage, then the question
 * marker, then its own part. A question with no row has no transcription
 * here, whatever the server says. Thread-safe: each read opens its own
 * connection.
 */
@Slf4j
public final class TranscriptionStore {

    /** Ids per query, so the statement stays small. */
    private static final int CHUNK = 500 ;

    private static final String QUERY =
            "SELECT question_id, content FROM question_xcription " +
            "WHERE question_id IN (" ;

    private final String     url ;
    private final Properties props ;

    public TranscriptionStore( String host, int port, String database,
                               String user, String password ) {

        this.url   = "jdbc:mysql://" + host + ":" + port + "/" + database ;
        this.props = new Properties() ;

        props.setProperty( "user", user ) ;
        props.setProperty( "password", password ) ;
        props.setProperty( "characterEncoding", "UTF-8" ) ;
        props.setProperty( "readOnly", "true" ) ;
        props.setProperty( "connectTimeout", "5000" ) ;
    }

    /**
     * The transcription of each of {@code ids} (question table ids) that has
     * one, by id.
     *
     * @throws SQLException if the database could not be read
     */
    public Map<Integer,String> read( Collection<Integer> ids )
            throws SQLException {

        Map<Integer,String> found = new HashMap<>() ;
        if( ids.isEmpty() ) return found ;

        List<Integer> all = List.copyOf( ids ) ;
        try( Connection c = DriverManager.getConnection( url, props ) ) {
            c.setReadOnly( true ) ;
            for( int from = 0 ; from < all.size() ; from += CHUNK ) {
                List<Integer> part = all.subList(
                        from, Math.min( from + CHUNK, all.size() ) ) ;
                read( c, part, found ) ;
            }
        }
        log.debug( "Read {} transcriptions for {} questions", found.size(),
                   all.size() ) ;
        return found ;
    }

    private static void read( Connection c, List<Integer> ids,
                              Map<Integer,String> into ) throws SQLException {

        String sql = QUERY + "?,".repeat( ids.size() - 1 ) + "?)" ;
        try( PreparedStatement st = c.prepareStatement( sql ) ) {
            for( int i = 0 ; i < ids.size() ; i++ ) {
                st.setInt( i + 1, ids.get( i ) ) ;
            }
            try( ResultSet rs = st.executeQuery() ) {
                while( rs.next() ) into.put( rs.getInt( 1 ), rs.getString( 2 ) ) ;
            }
        }
    }
}
