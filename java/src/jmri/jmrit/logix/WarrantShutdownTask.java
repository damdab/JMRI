package jmri.jmrit.logix;

import java.util.HashMap;
import java.util.Iterator;
import jmri.InstanceManager;
import jmri.implementation.AbstractShutDownTask;
import jmri.jmrit.roster.Roster;
import jmri.jmrit.roster.RosterEntry;
import jmri.jmrit.roster.RosterSpeedProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 
 * Allows user to decide if (and which) SpeedProfiles to write to the Roster at 
 * the end of a session.  Locos running warrants have had their speeds measured
 * and this new data may or may not be merged into any existing SpeedProfiles
 * in the Roster.
 * <p>
 *
 * @author Pete cressman Copyright (C) 2017
 */
public class WarrantShutdownTask extends AbstractShutDownTask {

    HashMap<String, Boolean> _mergeCandidates;
    HashMap<String, RosterSpeedProfile> _mergeProfiles;
    /**
     * Constructor specifies the warning message and action to take
     *
     * @param name      the name of the task (used in logs)
     */
    public WarrantShutdownTask(String name) {
        super(name);
    }

    /**
     * Take the necessary action.
     *
     * @return true if the shutdown should continue, false to abort.
     */
    @Override
    public boolean execute() {
        WarrantPreferences preferences = WarrantPreferences.getDefault();
        switch (preferences.getShutdown()) {
            case MERGE_ALL:
                if (makeMergeCandidates()) {
                    merge();
                }
                break;
            case PROMPT:
                if (makeMergeCandidates()) {
                    makeMergeWindow();
                    merge();
                }
                break;
            case NO_MERGE:
                // do nothing
                break;
            default:
                log.warn("No choice made for warrant shutdown");
                break;
        }
        return true;
    }
    
    private boolean makeMergeCandidates() {
        WarrantManager manager = InstanceManager.getDefault(WarrantManager.class);
        _mergeProfiles = manager.getMergeProfiles();
        if (_mergeProfiles == null || _mergeProfiles.size() == 0) {
            return false;
        }
        _mergeCandidates = new HashMap<String, Boolean> ();
        Iterator <java.util.Map.Entry <String, RosterSpeedProfile>> iter = _mergeProfiles.entrySet().iterator(); 
        while (iter.hasNext()) {
            java.util.Map.Entry <String, RosterSpeedProfile> entry = iter.next();
            MergePrompt.validateSpeedProfile(entry.getValue(), entry.getKey());  // clean up
            _mergeCandidates.put(entry.getKey(), new Boolean(true));
        }
        return  true;
    }
    
    private void makeMergeWindow() {
        new MergePrompt(Bundle.getMessage("MergeTitle"), _mergeCandidates);
    }
    
    private void merge() {
        Iterator <java.util.Map.Entry <String, Boolean>> iter = _mergeCandidates.entrySet().iterator(); 
        while (iter.hasNext()) {
            java.util.Map.Entry <String, Boolean> entry = iter.next();
            String id = entry.getKey();
            if (entry.getValue().booleanValue()) {
                RosterEntry rosterEntry = Roster.getDefault().entryFromTitle(id);
                if (rosterEntry != null) {
                    rosterEntry.setSpeedProfile(_mergeProfiles.get(id));
                    if (log.isDebugEnabled()) log.debug("Write SpeedProfile to Roster. id= {}", id);
                } else {
                    if (log.isDebugEnabled()) log.debug("Unable to Write SpeedProfile to Roster. No RosterEntry for {}", id);
                }
            } else {
                if (log.isDebugEnabled()) log.debug("SpeedProfile not merged to Roster. id= {}", id);
            }
        }
        Roster.getDefault().writeRoster();
    }

    private final static Logger log = LoggerFactory.getLogger(WarrantShutdownTask.class.getName());

}
