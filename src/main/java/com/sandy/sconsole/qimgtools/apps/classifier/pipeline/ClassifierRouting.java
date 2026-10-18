package com.sandy.sconsole.qimgtools.apps.classifier.pipeline;

import com.sandy.sconsole.ai.Candidate;
import com.sandy.sconsole.ai.HubView;
import com.sandy.sconsole.ai.JobContext;
import com.sandy.sconsole.ai.ModelSlot;
import com.sandy.sconsole.ai.RoutingPolicy;
import com.sandy.sconsole.qimgtools.core.model.QTask;
import com.sandy.sconsole.qimgtools.core.model.Subject;
import com.sandy.sconsole.qimgtools.core.scheduler.TaskScheduler;

import java.util.List;

/**
 * Which question a free model slot of the classifier takes. The app config
 * has two roots: {@link #LOCAL} (models on the local server) and
 * {@link #CLOUD}. Every question may start on either; this policy shares
 * them out:
 * <ul>
 *   <li>Chemistry goes to the cloud. A local model takes it only while
 *       every cloud model is held back (a rate limit, the daily limit, the
 *       gateway unreachable; not per-minute pacing).</li>
 *   <li>Physics and Maths go to the local models. A cloud model takes them
 *       only when no Chemistry or second opinion waits for it and no local
 *       model can take them right now.</li>
 *   <li>Retries, fallbacks and second opinions (escalations to the cloud)
 *       go where the hub offers them; they rank ahead of new questions.</li>
 * </ul>
 * With no local model available (none configured, all switched off, or
 * held back), nothing needs sharing out: every question ranks the same and
 * they are taken in submission order. Ties go by submission order.
 */
public class ClassifierRouting implements RoutingPolicy {

    /** The root of the models on the local server. */
    public static final String LOCAL = "local" ;

    /** The root of the hosted models. */
    public static final String CLOUD = "cloud" ;

    /** The roots a question may start on, preferred first. */
    public static List<String> roots( QTask task ) {
        return task.getSubject() == Subject.CHEMISTRY
               ? List.of( CLOUD, LOCAL ) : List.of( LOCAL, CLOUD ) ;
    }

    @Override
    public Candidate pick( ModelSlot slot, List<Candidate> eligible,
                           HubView view ) {

        boolean onLocal = view.rootModels( LOCAL )
                              .contains( slot.model().id() ) ;
        boolean localUp = view.isRootAvailable( LOCAL ) ;
        boolean cloudUp = view.isRootAvailable( CLOUD ) ;

        Candidate best     = null ;
        int       bestRank = Integer.MAX_VALUE ;

        for( Candidate c : eligible ) {
            int rank = localUp ? rank( c, onLocal, cloudUp, view ) : 0 ;
            if( rank < 0 ) continue ;
            if( rank < bestRank
                || ( rank == bestRank && c.order() < best.order() ) ) {
                best     = c ;
                bestRank = rank ;
            }
        }
        return best ;
    }

    /**
     * Priority of {@code c} on the slot, lower first; -1 if the slot should
     * not take it. A local slot: continuations, then new questions. A cloud
     * slot: Chemistry continuations, new Chemistry, second opinions, other
     * continuations, then new Physics / Maths.
     */
    private static int rank( Candidate c, boolean onLocal, boolean cloudUp,
                             HubView view ) {

        boolean chemistry = isChemistry( c.job() ) ;

        if( c.via() == Candidate.Via.ESCALATION ) return 2 ;
        if( c.isContinuation() ) {
            return chemistry || onLocal ? 0 : 3 ;
        }
        if( onLocal ) return chemistry && cloudUp ? -1 : 1 ;
        if( chemistry ) return 1 ;
        return view.canRootSendNow( LOCAL ) ? -1 : 4 ;
    }

    private static boolean isChemistry( JobContext<?> job ) {
        QTask task = TaskScheduler.taskOf( job ) ;
        return task != null && task.getSubject() == Subject.CHEMISTRY ;
    }
}
