package jmri.jmrit.ctc.topology;

import java.util.*;
import jmri.*;
import jmri.jmrit.ctc.ctcserialdata.CTCSerialData;

/**
 * This class contains all of the information needed (in lists) for the higher level
 * "TRL_Rules" to generate all of the entries in "_mTRL_TrafficLockingRulesSSVList"
 * 
 * @author Gregory J. Bedlek Copyright (C) 2018, 2019, 2020
 */

public class TopologyInfo {
    private final CTCSerialData _mCTCSerialData;    // Needed to look up a turnout in order to return an O.S. section text.
    private final String _mNormal;                  // Bundle.getMessage("TLE_Normal")
    private final String _mReverse;                 // Bundle.getMessage("TLE_Reverse")
    public TopologyInfo(CTCSerialData CTCSerialData, String normal, String reverse) {
        _mCTCSerialData = CTCSerialData;
        _mNormal = normal;
        _mReverse = reverse;
    }
    /**
     * Simple class to contain simple info about a turnout.
     */
    private static class TurnoutInfo {
        public final String _mOSSectionText;
        public final String _mNormalReversed;
        public final int    _mUniqueID;
        private TurnoutInfo() { _mOSSectionText = null; _mNormalReversed = "Normal"; _mUniqueID = -1;}  // To support constructor "TrafficLockingEntry(int ruleNumber, TopologyInfo topologyInfo)"
        public TurnoutInfo(String OSSectionText, String normalReversed, int uniqueID) {
            _mOSSectionText = OSSectionText;
            _mNormalReversed = normalReversed;
            _mUniqueID = uniqueID;
        }
    }
    private final ArrayList<Sensor> _mSensors = new ArrayList<>();
//  private final LinkedList<String> _mSensorNamesDebug = new LinkedList<>();    //????
    private final ArrayList<TurnoutInfo> _mTurnoutInfos = new ArrayList<>();
//  private final LinkedList<String> _mOSSectionInfosDebug = new LinkedList<>(); //????
    private final ArrayList<Turnout> _mTurnouts = new ArrayList<>();  // ONLY used for duplicate check (lazy).
    
    /**
     * @return true if any of our lists have anything.
     */
    public boolean nonEmpty() { return !_mSensors.isEmpty() || !_mTurnoutInfos.isEmpty(); }
    
    /**
     * Quick and dirty routine to get O.S. section information.
     * 
     * @param index Index into array
     * @return String null if no information, else text (of form "29/30" for instance)
     */
    public String getOSSectionText(int index) {
        if (index < _mTurnoutInfos.size()) { // Safety: Can return info:
            return _mTurnoutInfos.get(index)._mOSSectionText;
        } else {
            return null;
        }
    }

    
    /**
     * Quick and dirty routine to get "Normal"/"Reverse" information.
     * 
     * @param index Index into array
     * @return String "Normal" if no information, else text (of form "Normal" for instance)
     */
    public String getNormalReversed(int index) {
        if (index < _mTurnoutInfos.size()) { // Safety: Can return info:
            return _mTurnoutInfos.get(index)._mNormalReversed;
        } else {
            return "Normal";    // Doesn't hurt to return this for a turnout that has no information.
        }
    }

    
    /**
     * Quick and dirty routine to get the Display Name of the sensor.
     * 
     * @param index Index into array
     * @return String "" if no information, else text (of form "SW31-OS" for instance)
     */
    public String getSensorDisplayName(int index) {
        if (index < _mSensors.size()) { // Safety: Can return info:
            return _mSensors.get(index).getDisplayName();
        } else {
            return "";
        }
    }
    
    
    /**
     * Quick and dirty routine to get the unique id..
     * 
     * @param index Index into array
     * @return String null if no information, else uniqueID as String of the O.S. section.
     */
    public String getUniqueID(int index) {
        if (index < _mTurnoutInfos.size()) { // Safety: Can return info:
            return Integer.toString(_mTurnoutInfos.get(index)._mUniqueID);
        } else {
            return null;
        }
    }
    
    
    /**    
     * Quick and dirty routine to all all of the sensors in the passed blocks to
     * our internal lists.  Duplicates are ignored.
     * @param blocks    List of Blocks to add.
     */
    public void addBlocks(List<Block> blocks) {
        for (Block block : blocks) {
            Sensor sensor = block.getSensor();
            if (!_mSensors.contains(sensor)) { //  VERIFY not in list already for some reason (safety, shouldn't happen):
                _mSensors.add(sensor);
//              _mSensorNamesDebug.add(sensor.getDisplayName());
            }
        }
    }
    
    
    /**
     * Quick and dirty routine to add all of the turnouts in SML to our internal lists.
     * Duplicates are ignored.
     * 
     * @param signalMastLogic   SML to work against.
     * @param signalMast        Destination mast in SML.
     */
    public void addTurnouts(SignalMastLogic signalMastLogic, SignalMast signalMast) {
//  Right now, I cannot make a subroutine call out of this, because I have to call two different
//  routines at the lowest level: "signalMastLogic.getTurnoutState" and "signalMastLogic.getAutoTurnoutState"
//  depending on which it is.  In Java method reference is a way.  But I'm lazy and in a hurry:
        for (Turnout turnout : signalMastLogic.getTurnouts(signalMast)) {
            if (!_mTurnouts.contains(turnout)) {    // VERIFY not in list already for some reason (safety, shouldn't happen):
                _mTurnouts.add(turnout);            // For above if statement dup check.
//  Need to convert the turnout to an O.S. section text:                
                CTCSerialData.CTCTurnoutData turnoutData = _mCTCSerialData.getCTCTurnoutData(turnout);
                if (null != turnoutData) { // Safety:
//  ToDo someday: Reverse "isNormal" if feedback different?                    
                    boolean isNormal = signalMastLogic.getTurnoutState(turnout, signalMast) == Turnout.CLOSED;
                    _mTurnoutInfos.add(new TurnoutInfo(turnoutData._mOSSectionText, isNormal ? _mNormal : _mReverse, turnoutData._mUniqueID));
//                  _mOSSectionInfosDebug.add(OSSectionText);
                }
            }
        }
        for (Turnout turnout : signalMastLogic.getAutoTurnouts(signalMast)) {
            if (!_mTurnouts.contains(turnout)) {    // VERIFY not in list already for some reason (safety, shouldn't happen):
                _mTurnouts.add(turnout);            // For above if statement dup check.
//  Need to convert the turnout to an O.S. section text:                
                CTCSerialData.CTCTurnoutData turnoutData = _mCTCSerialData.getCTCTurnoutData(turnout);
                if (null != turnoutData) { // Safety:
//  ToDo someday: Reverse "isNormal" if feedback different?                    
                    boolean isNormal = signalMastLogic.getAutoTurnoutState(turnout, signalMast) == Turnout.CLOSED;
                    _mTurnoutInfos.add(new TurnoutInfo(turnoutData._mOSSectionText, isNormal ? _mNormal : _mReverse, turnoutData._mUniqueID));
//                  _mOSSectionInfosDebug.add(OSSectionText);
                }
            }
        }
    }
}
