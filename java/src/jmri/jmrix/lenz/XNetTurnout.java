package jmri.jmrix.lenz;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.Queue;
import jmri.implementation.AbstractTurnout;
import javax.annotation.concurrent.GuardedBy;

/**
 * Extend jmri.AbstractTurnout for XNet layouts
 * <p>
 * Turnout opperation on XpressNet based systems goes through the following
 * sequence:
 * <ul>
 * <li> set the commanded state, and, Send request to command station to start
 * sending DCC operations packet to track</li>
 * <li> Wait for response message from command station. (valid response list
 * follows)</li>
 * <li> Send request to command station to stop sending DCC operations packet to
 * track</li>
 * <li> Wait for response from command station
 * <ul>
 * <li>If Success Message, set Known State to Commanded State</li>
 * <li>If error message, repeat previous step</li>
 * </ul>
 * </li>
 * </ul>
 * <p>
 * NOTE: Some XpressNet Command stations take no action when the message
 * generated during the third step is received.
 * <p>
 * Valid response messages are command station dependent, but there are 4
 * possibilities:
 * <ul>
 * <li> a "Command Successfully Received..." (aka "OK") message</li>
 * <li> a "Feedback Response Message" indicating the message is for a turnout
 * with feedback</li>
 * <li> a "Feedback Response Message" indicating the message is for a turnout
 * without feedback</li>
 * <li> The XpressNet protocol allows for no response. </li>
 * </ul>
 * <p>
 * Response NOTE 1: The "Command Successfully Received..." message is generated
 * by the lenz LIxxx interfaces when it successfully transfers the command to
 * the command station. When this happens, the command station generates no
 * useable response message.
 * <p>
 * Response NOTE 2: Currently the only command stations known to generate
 * Feedback response messages are the Lenz LZ100 and LZV100.
 * <p>
 * Response NOTE 3: Software version 3.2 and above LZ100 and LZV100 may send
 * either a Feedback response or no response at all. All other known command
 * stations generate no response.
 * <p>
 * Response NOTE 4: The Feedback response messages may be generated
 * asynchronously
 * <p>
 * Response NOTE 5: Feedback response messages may contain feedback for more
 * than one device. The devices included in the response may or may not be
 * stationary decoders (they can also be feedback encoders see
 * {@link XNetSensor}).
 * <p>
 * Response NOTE 6: The last situation situation is not currently handled. The
 * supported interfaces garantee at least an "OK" message will be sent to the
 * computer
 * <p>
 * What is done with each of the response messages depends on which feedback
 * mode is in use. "DIRECT,"MONITORING", and "EXACT" feedback mode are supported
 * directly by this class.
 * <p>
 * "DIRECT" mode instantly triggers step 3 when any valid response message for
 * this turnout is received from the command station or computer interface.
 * <p>
 * "SIGNAL" mode is identical to "DIRECT" mode, except it skips step 2. i.e. it
 * triggers step 3 without receiving any reply from the command station.
 * <p>
 * "MONITORING" mode is an extention to direct mode. In monitoring mode, a
 * feedback response message (for a turnout with or without feedback) is
 * interpreted to set the known state of the turnout based on information
 * provided by the command station.
 * <p>
 * "MONITORING" mode will interpret the feedback response messages when they are
 * generated by external sources (fascia controls or other XpressNet devices)
 * and that information is received by the computer.
 * <p>
 * "EXACT" mode is an extention of "MONITORING" mode. In addition to
 * interpretting all feedback messages from the command station, "EXACT" mode
 * will monitor the "motion complete" bit of the feedback response.
 * <p>
 * For turnouts without feedback, the motion complete bit is always set, so
 * "EXACT" mode handles these messages as though the specified feedback mode is
 * "MONITORING" mode.
 * <p>
 * For turnouts with feedback, "EXACT" mode polls the command station until the
 * motion complete bit is set before triggering step 3 of the turnout operation
 * sequence.
 * <p>
 * "EXACT" mode will interpret the feedback response messages when they are
 * generated by external sources (fascia controls or other XpressNet devices)
 * and that information is received by the computer.
 * <p>
 * NOTE: For LZ100 and LZV100 command stations prior to version 3.2, it may be
 * necessary to poll for the feedback response data.
 *
 * @author Bob Jacobsen Copyright (C) 2001
 * @author      Paul Bender Copyright (C) 2003-2010
 */
public class XNetTurnout extends AbstractTurnout implements XNetListener {

    /* State information */
    protected static final int OFFSENT = 1;
    protected static final int COMMANDSENT = 2;
    protected static final int STATUSREQUESTSENT = 4;
    protected static final int QUEUEDMESSAGE = 8;
    protected static final int IDLE = 0;
    protected int internalState = IDLE;

    /* Static arrays to hold Lenz specific feedback mode information */
    static String[] modeNames = null;
    static int[] modeValues = null;

    @GuardedBy("this")
    protected int _mThrown = jmri.Turnout.THROWN;
    @GuardedBy("this")
    protected int _mClosed = jmri.Turnout.CLOSED;

    protected int mNumber;   // XpressNet turnout number
    final XNetTurnoutStateListener _stateListener;  // Internal class object

    // A queue to hold outstanding messages
    @GuardedBy("this")
    protected final Queue<RequestMessage> requestList;

    @GuardedBy("this")
    protected RequestMessage lastMsg = null;

    protected final String _prefix; // default
    protected final XNetTrafficController tc;

    public XNetTurnout(String prefix, int pNumber, XNetTrafficController controller) {  // a human-readable turnout number must be specified!
        super(prefix + "T" + pNumber);
        tc = controller;
        _prefix = prefix;
        mNumber = pNumber;

        requestList = new LinkedList<>();

        /* Add additional feedback types information */
        _validFeedbackTypes |= MONITORING | EXACT | SIGNAL;

        // Default feedback mode is MONITORING
        _activeFeedbackType = MONITORING;

        setModeInformation(_validFeedbackNames, _validFeedbackModes);

        // set the mode names and values based on the static values.
        _validFeedbackNames = getModeNames();
        _validFeedbackModes = getModeValues();
        
        // Register to get property change information from the superclass
        _stateListener = new XNetTurnoutStateListener(this);
        this.addPropertyChangeListener(_stateListener);
        // Finally, request the current state from the layout.
        tc.getFeedbackMessageCache().requestCachedStateFromLayout(this);
    }
    
    /**
     * Set the mode information for XpressNet Turnouts.
     */
    private static synchronized void setModeInformation(String[] feedbackNames, int[] feedbackModes) {
        // if it hasn't been done already, create static arrays to hold
        // the Lenz specific feedback information.
        if (modeNames == null) {
            if (feedbackNames.length != feedbackModes.length) {
                log.error("int and string feedback arrays different length");
            }
            modeNames = Arrays.copyOf(feedbackNames, feedbackNames.length + 3);
            modeValues = Arrays.copyOf(feedbackModes, feedbackNames.length + 3);
            modeNames[feedbackNames.length] = "MONITORING";
            modeValues[feedbackNames.length] = MONITORING;
            modeNames[feedbackNames.length + 1] = "EXACT";
            modeValues[feedbackNames.length + 1] = EXACT;
            modeNames[feedbackNames.length + 2] = "SIGNAL";
            modeValues[feedbackNames.length + 2] = SIGNAL;
        }
    }

    static int[] getModeValues() {
        return modeValues;
    }

    static String[] getModeNames() {
        return modeNames;
    }

    public int getNumber() {
        return mNumber;
    }

    /**
     * Set the Commanded State.
     * This method overides {@link jmri.implementation.AbstractTurnout#setCommandedState(int)}.
     */
    @Override
    public void setCommandedState(int s) {
        if (log.isDebugEnabled()) {
            log.debug("set commanded state for XNet turnout {} to {}", getSystemName(), s);
        }
        synchronized (this) {
            newCommandedState(s);
        }
        myOperator = getTurnoutOperator(); // MUST set myOperator before starting the thread
        if (myOperator == null) {
            forwardCommandChangeToLayout(s);
            synchronized (this) {
                newKnownState(INCONSISTENT);
            }
        } else {
            myOperator.start();
        }
    }

    /**
     * Handle a request to change state by sending an XpressNet command.
     */
    @Override
    protected synchronized void forwardCommandChangeToLayout(int s) {
        if (s != _mClosed && s != _mThrown) {
            log.warn("Turnout {}: state {} not forwarded to layout.", mNumber, s);
            return;
        }
        // get the right packet
        XNetMessage msg = XNetMessage.getTurnoutCommandMsg(mNumber,
                (s & _mClosed) != 0,
                (s & _mThrown) != 0,
                true);
        if (getFeedbackMode() == SIGNAL) {
            msg.setTimeout(0); // Set the timeout to 0, so the off message can
            // be sent immediately.
            // leave the next line commented out for now.
            // It may be enabled later to allow SIGNAL mode to ignore
            // directed replies, which lets the traffic controller move on
            // to the next message without waiting.
            //msg.setBroadcastReply();
            tc.sendXNetMessage(msg, null);
            sendOffMessage();
        } else {
            queueMessage(msg, COMMANDSENT, this);
        }
    }

    @Override
    protected void turnoutPushbuttonLockout(boolean _pushButtonLockout) {
        log.debug("Send command to {} Pushbutton {}T{}", (_pushButtonLockout ? "Lock" : "Unlock"), _prefix, mNumber);
    }

    /**
     * Request an update on status by sending an XpressNet message.
     */
    @Override
    public void requestUpdateFromLayout() {
        // This will handle ONESENSOR and TWOSENSOR feedback modes.
        super.requestUpdateFromLayout();

        // To do this, we send an XpressNet Accessory Decoder Information
        // Request.
        // The generated message works for Feedback modules and turnouts
        // with feedback, but the address passed is translated as though it
        // is a turnout address.  As a result, we substitute our base
        // address in for the address. after the message is returned.
        XNetMessage msg = XNetMessage.getFeedbackRequestMsg(mNumber,
                ((mNumber - 1) % 4) < 2);
        queueMessage(msg,IDLE,null); //status is returned via the manager.

    }

    @Override
    public synchronized void setInverted(boolean inverted) {
        log.debug("Inverting Turnout State for turnout {}T{}", _prefix, mNumber);
        _inverted = inverted;
        if (inverted) {
            _mThrown = jmri.Turnout.CLOSED;
            _mClosed = jmri.Turnout.THROWN;
        } else {
            _mThrown = jmri.Turnout.THROWN;
            _mClosed = jmri.Turnout.CLOSED;
        }
        super.setInverted(inverted);
    }

    @Override
    public boolean canInvert() {
        return true;
    }

    /**
     * Package protected class which allows the Manger to send
     * a feedback message at initilization without changing the state of the
     * turnout with respect to whether or not a feedback request was sent. This
     * is used only when the turnout is created by on layout feedback.
     * @param l Message to initialize
     */
    synchronized void initmessage(XNetReply l) {
        int oldState = internalState;
        message(l);
        internalState = oldState;
    }

    /**
     * Handle an incoming message from the XpressNet.
     */
    @Override
    public synchronized void message(XNetReply l) {
        log.debug("received message: {}", l);
        if (internalState == OFFSENT) {
            if (l.isOkMessage() && !l.isUnsolicited()) {
                /* the command was successfully received */
                synchronized (this) {
                    newKnownState(getCommandedState());
                }
                sendQueuedMessage();
                return;
            } else if (l.isRetransmittableErrorMsg()) {
                return; // don't do anything, the Traffic
                // Controller is handling retransmitting
                // this one.
            } else {
                /* Default Behavior: If anything other than an OK message
                 is received, Send another OFF message. */
                log.debug("Message is not OK message. Message received was: {}", l);
                sendOffMessage();
            }
        }

        switch (getFeedbackMode()) {
            case EXACT:
                handleExactModeFeedback(l);
                break;
            case MONITORING:
                handleMonitoringModeFeedback(l);
                break;
            case DIRECT:
            default:
                // Default is direct mode
                handleDirectModeFeedback(l);
        }
    }

    /**
     * Listen for the messages to the LI100/LI101.
     */
    @Override
    public void message(XNetMessage l) {
        log.debug("received outgoing message {} for turnout {}",l,getSystemName());
        // we want to verify this is the last message we sent
        // so use == not .equals
        if(lastMsg!=null && l == lastMsg.msg){
            //if this is the last message we sent, set the state appropriately
            internalState = lastMsg.getState();
            // and set lastMsg to null
            lastMsg = null;
        }
    }

    /**
     * Handle a timeout notification.
     */
    @Override
    public synchronized void notifyTimeout(XNetMessage msg) {
        log.debug("Notified of timeout on message {}", msg);
        // If we're in the OFFSENT state, we need to send another OFF message.
        if (internalState == OFFSENT) {
            sendOffMessage();
        }
    }

    /**
     *  With Direct Mode feedback, if we see ANY valid response to our
     *  request, we ask the command station to stop sending information
     *  to the stationary decoder.
     *  <p>
     *  No effort is made to interpret feedback when using direct mode.
     *
     *  @param l an {@link XNetReply} message
     */
    private synchronized void handleDirectModeFeedback(XNetReply l) {
        /* If commanded state does not equal known state, we are
         going to check to see if one of the following conditions
         applies:
         1) The received message is a feedback message for a turnout
         and one of the two addresses to which it applies is our
         address
         2) We receive an "OK" message, indicating the command was
         successfully sent

         If either of these two cases occur, we trigger an off message
         */

        log.debug("Handle Message for turnout {} in DIRECT feedback mode   ", mNumber);
        if (getCommandedState() != getKnownState() || internalState == COMMANDSENT) {
            if (l.isOkMessage()) {
                // Finally, we may just receive an OK message.
                log.debug("Turnout {} DIRECT feedback mode - OK message triggering OFF message.", mNumber);
            } else {
                // implicitly checks for isFeedbackBroadcastMessage()
                if (!l.selectTurnoutFeedback(mNumber).isPresent()) {
                    return;
                }
                log.debug("Turnout {} DIRECT feedback mode - directed reply received.", mNumber);
            }
            sendOffMessage();
            // Explicitly send two off messages in Direct Mode
            sendOffMessage();
        }
    }

    /**
     *  With Monitoring Mode feedback, if we see a feedback message, we
     *  interpret that message and use it to display our feedback.
     *  <p>
     *  After we send a request to operate a turnout, We ask the command
     *  station to stop sending information to the stationary decoder
     *  when the either a feedback message or an "OK" message is received.
     *
     *  @param l an {@link XNetReply} message
     */
    private synchronized void handleMonitoringModeFeedback(XNetReply l) {
        /* In Monitoring Mode, We have two cases to check if CommandedState
         does not equal KnownState, otherwise, we only want to check to
         see if the messages we receive indicate this turnout chagned
         state
         */
        log.debug("Handle Message for turnout {} in MONITORING feedback mode ", mNumber);
        if (internalState == IDLE || internalState == STATUSREQUESTSENT) {
            if (l.onTurnoutFeedback(mNumber, this::parseFeedbackMessage)) {
                log.debug("Turnout {} MONITORING feedback mode - state change from feedback.", mNumber);
            }
        } else if (getCommandedState() != getKnownState()
                || internalState == COMMANDSENT) {
            if (l.isOkMessage()) {
                // Finally, we may just receive an OK message.
                log.debug("Turnout {} MONITORING feedback mode - OK message triggering OFF message.", mNumber);
                sendOffMessage();
            } else {
                // In Monitoring mode, treat both turnouts with feedback
                // and turnouts without feedback as turnouts without
                // feedback.  i.e. just interpret the feedback
                // message, don't check to see if the motion is complete
                // implicitly checks for isFeedbackBroadcastMessage()
                if (l.onTurnoutFeedback(mNumber, this::parseFeedbackMessage)) {
                    // We need to tell the turnout to shut off the output.
                    log.debug("Turnout {} MONITORING feedback mode - state change from feedback, CommandedState != KnownState.", mNumber);
                    sendOffMessage();
                }
            }
        }
    }

    /**
     *  With Exact Mode feedback, if we see a feedback message, we
     *  interpret that message and use it to display our feedback.
     *  <p>
     *  After we send a request to operate a turnout, We ask the command
     *  station to stop sending information to the stationary decoder
     *  when the either a feedback message or an "OK" message is received.
     *
     *  @param reply The reply message to process
     */
    private synchronized void handleExactModeFeedback(XNetReply reply) {
        // We have three cases to check if CommandedState does
        // not equal KnownState, otherwise, we only want to check to
        // see if the messages we receive indicate this turnout chagned
        // state
        log.debug("Handle Message for turnout {} in EXACT feedback mode ", mNumber);
        if (getCommandedState() == getKnownState()
                && (internalState == IDLE || internalState == STATUSREQUESTSENT)) {
            // This is a feedback message, we need to check and see if it
            // indicates this turnout is to change state or if it is for
            // another turnout.
            if (reply.onTurnoutFeedback(mNumber, this::parseFeedbackMessage)) {
                log.debug("Turnout {} EXACT feedback mode - state change from feedback.", mNumber);
            }
        } else if (getCommandedState() != getKnownState()
                || internalState == COMMANDSENT
                || internalState == STATUSREQUESTSENT) {
            if (reply.isOkMessage()) {
                // Finally, we may just receive an OK message.
                log.debug("Turnout {} EXACT feedback mode - OK message triggering OFF message.", mNumber);
                sendOffMessage();
            } else {
                // implicitly checks for isFeedbackBroadcastMessage()
                reply.selectTurnoutFeedback(mNumber).ifPresent(l -> {
                    int messageType = l.getType();
                    switch (messageType) {
                        case 1: {
                            // The first case is that we receive a message for
                            // this turnout and this turnout provides feedback.
                            // In this case, we want to check to see if the
                            // turnout has completed its movement before doing
                            // anything else.
                            if (!l.isMotionComplete()) {
                                log.debug("Turnout {} EXACT feedback mode - state change from feedback, CommandedState!=KnownState - motion not complete", mNumber);
                                // If the motion is NOT complete, send a feedback
                                // request for this nibble
                                XNetMessage msg = XNetMessage.getFeedbackRequestMsg(
                                        mNumber, ((mNumber % 4) <= 1));
                                queueMessage(msg,STATUSREQUESTSENT ,null); //status is returned via the manager.
                                return;
                            } else {
                                log.debug("Turnout {} EXACT feedback mode - state change from feedback, CommandedState!=KnownState - motion complete", mNumber);
                            }
                            break;
                        }
                        case 0: 
                            log.debug("Turnout {} EXACT feedback mode - state change from feedback, CommandedState!=KnownState - motion complete", mNumber);
                            // The second case is that we receive a message about
                            // this turnout, and this turnout does not provide
                            // feedback. In this case, we want to check the
                            // contents of the message and act accordingly.
                            break;
                        default: return;
                    }
                    parseFeedbackMessage(l);
                    // We need to tell the turnout to shut off the output.
                    sendOffMessage();
                });
            }
        }
    }
    
    /**
     * Send an "Off" message to the decoder for this output. 
     */
    protected synchronized void sendOffMessage() {
        // We need to tell the turnout to shut off the output.
        if (log.isDebugEnabled()) {
            log.debug("Sending off message for turnout {} commanded state={}", mNumber, getCommandedState());
            log.debug("Current Thread ID: {} Thread Name {}", java.lang.Thread.currentThread().getId(), java.lang.Thread.currentThread().getName());
        }
        XNetMessage msg = getOffMessage();
        lastMsg = new RequestMessage(msg,OFFSENT,this);
        this.internalState = OFFSENT;
        newKnownState(getCommandedState());
        // Then send the message.
        tc.sendHighPriorityXNetMessage(msg, this);
    }

    protected synchronized XNetMessage getOffMessage(){
        return ( XNetMessage.getTurnoutCommandMsg(mNumber,
                getCommandedState() == _mClosed,
                getCommandedState() == _mThrown,
                false) );
    }

    /**
     * Parse the feedback message, and set the status of the turnout
     * accordingly.
     *
     * @param l  turnout feedback item
     * 
     * @return 0 if address matches our turnout -1 otherwise
     */
    private synchronized boolean parseFeedbackMessage(FeedbackItem l) {
        log.debug("Message for turnout {}", mNumber);
        switch (l.getTurnoutStatus()) {
            case THROWN:
                newKnownState(_mThrown);
                return true;
            case CLOSED:
                newKnownState(_mClosed);
                return true;
            default:
                // the state is unknown or inconsistent.  If the command state
                // does not equal the known state, and the command repeat the
                // last command
                if (getCommandedState() != getKnownState()) {
                    forwardCommandChangeToLayout(getCommandedState());
                } else {
                    sendQueuedMessage();
                }
                return false;
        }
    }
    
    @Override
    public void dispose() {
        this.removePropertyChangeListener(_stateListener);
        super.dispose();
    }

    /**
     * Internal class to use for listening to state changes.
     */
    private static class XNetTurnoutStateListener implements java.beans.PropertyChangeListener {

        final XNetTurnout _turnout;

        XNetTurnoutStateListener(XNetTurnout turnout) {
            _turnout = turnout;
        }

        /**
         * If we're  not using DIRECT feedback mode, we need to listen for
         * state changes to know when to send an OFF message after we set the
         * known state.
         * If we're using DIRECT mode, all of this is handled from the
         * XpressNet Messages.
         * @param event The event that causes this operation
         */
        @Override
        public void propertyChange(java.beans.PropertyChangeEvent event) {
            log.debug("propertyChange called");
            // If we're using DIRECT feedback mode, we don't care what we see here
            if (_turnout.getFeedbackMode() != DIRECT) {
                if (log.isDebugEnabled()) {
                    log.debug("propertyChange Not Direct Mode property: {} old value {} new value {}", event.getPropertyName(), event.getOldValue(), event.getNewValue());
                }
                if (event.getPropertyName().equals("KnownState")) {
                    // Check to see if this is a change in the status
                    // triggered by a device on the layout, or a change in
                    // status we triggered.
                    int oldKnownState = (Integer) event.getOldValue();
                    int curKnownState = (Integer) event.getNewValue();
                    log.debug("propertyChange KnownState - old value {} new value {}", oldKnownState, curKnownState);
                    if (curKnownState != INCONSISTENT
                            && _turnout.getCommandedState() == oldKnownState) {
                        // This was triggered by feedback on the layout, change
                        // the commanded state to reflect the new Known State
                        if (log.isDebugEnabled()) {
                            log.debug("propertyChange CommandedState: {}", _turnout.getCommandedState());
                        }
                        _turnout.newCommandedState(curKnownState);
                    } else {
                        // Since we always set the KnownState to
                        // INCONSISTENT when we send a command, If the old
                        // known state is INCONSISTENT, we just want to send
                        // an off message
                        if (oldKnownState == INCONSISTENT) {
                            if (log.isDebugEnabled()) {
                                log.debug("propertyChange CommandedState: {}", _turnout.getCommandedState());
                            }
                            _turnout.sendOffMessage();
                        }
                    }
                }
            }
        }

    }

    /**
     * Send message from queue.
     */
    protected synchronized void sendQueuedMessage() {

        lastMsg = null;
        // check to see if the queue has a message in it, and if it does,
        // remove the first message
        lastMsg = requestList.poll();
        // if the queue is not empty, remove the first message
        // from the queue, send the message, and set the state machine
        // to the required state.
        if (lastMsg != null) {
            log.debug("sending message to traffic controller");
            if(lastMsg.listener!=null) {
                internalState = QUEUEDMESSAGE;
            } else {
                internalState = lastMsg.state;
            }
            tc.sendXNetMessage(lastMsg.getMsg(), lastMsg.getListener());
        } else {
            log.debug("message queue empty");
            // if the queue is empty, set the state to idle.
            internalState = IDLE;
        }
    }
    
    /**
     * Queue a message.
     * @param m Message to send
     * @param s sequence
     * @param l Listener to get notification of completion
     */
    protected synchronized void queueMessage(XNetMessage m, int s, XNetListener l) {
        log.debug("adding message {} to message queue.  Current Internal State {}",m,internalState);
        // put the message in the queue
        RequestMessage msg = new RequestMessage(m, s, l);
        // the queue is unbounded; can't throw exceptions 
        requestList.add(msg);
        // if the state is idle, trigger the message send
        if (internalState == IDLE ) {
            sendQueuedMessage();
        }
    }

    /**
     * Internal class to hold a request message, along with the associated throttle state.
     */
    protected static class RequestMessage {

        private final int state;
        private final XNetMessage msg;
        private final XNetListener listener;

        RequestMessage(XNetMessage m, int s, XNetListener listener) {
            state = s;
            msg = m;
            this.listener = listener;
        }

        int getState() {
            return state;
        }

        XNetMessage getMsg() {
            return msg;
        }

        XNetListener getListener() {
            return listener;
        }
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(XNetTurnout.class);

}
