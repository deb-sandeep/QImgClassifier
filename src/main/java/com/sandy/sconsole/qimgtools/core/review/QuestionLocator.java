package com.sandy.sconsole.qimgtools.core.review;

import com.sandy.sconsole.qimgtools.core.model.QTask;
import com.sandy.sconsole.qimgtools.core.server.ServerQuestion;
import com.sandy.sconsole.qimgtools.core.server.ServerQuestionImage;
import com.sandy.sconsole.qimgtools.core.model.Subject;
import com.sandy.sconsole.qimgtools.core.util.FileHelper;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds a server question's images in the local question bank, as
 * QImgExtractor keeps them: the source folder is found by name anywhere
 * under the question bank root, and each image the server lists by its short
 * name ({@code C_SCA_74(2).png}) is the file in the folder's
 * {@code question-images} whose name ends with it
 * ({@code <source>.<page>.C_SCA_74(2).png}). The images are taken in the
 * server's {@code sequence} order, which puts LCT context images first.
 * Thread-safe.
 */
@Slf4j
public class QuestionLocator {

    /**
     * Where a question's images were found.
     *
     * @param question the question as the server sent it
     * @param task     its local images, ready for a model; null if they
     *                 could not be found
     * @param problem  why {@code task} is null; null otherwise
     */
    public record Located( ServerQuestion question, QTask task,
                           String problem ) {}

    private final File qBankDir ;

    /**
     * Source name -> its {@code question-images} files; an empty array if the
     * folder was not found. Guarded by {@code this}.
     */
    private final Map<String,File[]> imagesBySource = new HashMap<>() ;

    /** Source name -> its folder, for the sources found. */
    private final Map<String,File> srcDirs = new HashMap<>() ;

    public QuestionLocator( File qBankDir ) {
        this.qBankDir = qBankDir ;
    }

    /** {@code q} with its local images, or with why they weren't found. */
    public Located locate( ServerQuestion q ) {

        List<ServerQuestionImage> images = new ArrayList<>(
                q.getQuestionImages() ) ;
        if( images.isEmpty() ) {
            return missing( q, "the server lists no images" ) ;
        }
        images.sort( Comparator.comparing(
                ServerQuestionImage::getSequence,
                Comparator.nullsLast( Comparator.naturalOrder() ) ) ) ;

        File[] files = sourceImages( q.getSourceId() ) ;
        if( files.length == 0 ) {
            return missing( q, "source " + q.getSourceId() +
                               " not found in the question bank" ) ;
        }

        List<File> found  = new ArrayList<>() ;
        String     baseId = null ;

        for( ServerQuestionImage img : images ) {
            File f = find( files, img.getFileName() ) ;
            if( f == null ) {
                return missing( q, "image " + img.getFileName() +
                                   " not found in " + q.getSourceId() ) ;
            }
            found.add( f ) ;
            if( baseId == null && !Boolean.TRUE.equals( img.getLctCtxImage() ) ) {
                baseId = FileHelper.baseQuestionId(
                        FileHelper.extractQuestionId( f ) ) ;
            }
        }
        if( baseId == null ) {
            return missing( q, "the server lists only context images" ) ;
        }

        QTask task = new QTask( srcDir( q.getSourceId() ), baseId, found ) ;
        Subject subject = task.getSubject() ;
        if( !subject.hasSyllabus()
            || !subject.getSyllabusName().equals( q.getSyllabusName() ) ) {
            return missing( q, baseId + " is not a " + q.getSyllabusName() +
                               " question id" ) ;
        }
        return new Located( q, task, null ) ;
    }

    private Located missing( ServerQuestion q, String problem ) {
        log.warn( "Question {} ({}): {}", q.getId(), q.getQuestionId(), problem ) ;
        return new Located( q, null, problem ) ;
    }

    /** The image file whose name ends with {@code "." + shortName}. */
    private static File find( File[] files, String shortName ) {

        if( shortName == null ) return null ;
        String suffix = "." + shortName ;
        for( File f : files ) {
            if( f.getName().endsWith( suffix ) ) return f ;
        }
        return null ;
    }

    private synchronized File srcDir( String source ) {
        return srcDirs.get( source ) ;
    }

    /** The files in the source's {@code question-images}; cached. */
    private synchronized File[] sourceImages( String source ) {

        return imagesBySource.computeIfAbsent( source, name -> {
            File dir = name == null ? null : FileHelper.findDir( qBankDir, name ) ;
            if( dir == null ) return new File[0] ;

            File[] files = new File( dir, "question-images" ).listFiles(
                    File::isFile ) ;
            if( files == null ) return new File[0] ;

            srcDirs.put( name, dir ) ;
            Arrays.sort( files ) ;
            return files ;
        } ) ;
    }
}
