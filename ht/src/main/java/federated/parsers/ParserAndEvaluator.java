/*
 *    Copyright (C) 2026 Cyprus University of Technology, Limassol, Cyprus
 *    @author George Savva (gec.savva@edu.cut.ac.cy)
 *
 *    This program is free software; you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation; either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package federated.parsers;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileReader;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.yahoo.labs.samoa.instances.Attribute;
import com.yahoo.labs.samoa.instances.DenseInstance;
import com.yahoo.labs.samoa.instances.Instance;
import com.yahoo.labs.samoa.instances.Instances;
import com.yahoo.labs.samoa.instances.InstancesHeader;

import federated.ht.FederatedHoeffdingTree;
import moa.core.TimingUtils;

public class ParserAndEvaluator {
    private static boolean USE_DIRICHLET = false;
    private static double DIRICHLET_ALPHA = 1;
    private static int REPORT_EVERY = 0;
    private static final boolean ROUND_LOG = false;
    private static final boolean WRITE_PREDICTIONS_CSV = false;
    private static final int WINDOW_SIZE = 100;
    private static boolean FEDERATION_ENABLED = true;
    private static FederationMode FEDERATION_MODE = FederationMode.GLOBAL;
    private static TriggerPolicy TRIGGER_POLICY = TriggerPolicy.LOCAL_THRESHOLD;
    private static long GLOBAL_MERGE_INTERVAL = 1000;
    private static boolean IS_BACK_AND_FORTH_MERGE = true;
    private static long MERGE_THRESHOLD_BASE = 200;
    private static long MERGE_THRESHOLD_STEP = 100;
    private static long MERGE_THRESHOLD_CAP = 1000;

    public enum FederationMode {
        INDEPENDENT,
        GLOBAL,
        P2P,
        VOTING
    }

    enum TriggerPolicy {
        LOCAL_THRESHOLD, // per-node threshold
        GLOBAL_INTERVAL // every X global instances
    }

    enum SimMessageType {
        SUBMISSION, COMPLETION
    }

    enum FederatedMessages {
        INITIATE, MERGE, UPDATE, QUORUM_START, UNLOCK
    }

    interface Request {
    }

    static final class FederatedRequest implements Request {
        private final long ts;
        private final FederatedMessages type;
        private FederatedHoeffdingTree model;
        private final long roundId;

        private List<Integer> visitList;
        private final int forwardHopsCount;
        private long roundBytes = 0;

        long getRoundBytes() {
            return roundBytes;
        }

        void addRoundBytes(long bytes) {
            this.roundBytes += bytes;
        }

        FederatedRequest(long ts, FederatedMessages type,
                FederatedHoeffdingTree model, long roundId) {
            this.ts = ts;
            this.type = type;
            this.model = model;
            this.roundId = roundId;
            this.visitList = null;
            this.forwardHopsCount = 0;
        }

        FederatedRequest(long ts, FederatedMessages type) {
            this.ts = ts;
            this.type = type;
            this.model = null;
            this.roundId = -1L;
            this.visitList = null;
            this.forwardHopsCount = 0;
        }

        FederatedRequest(long ts, FederatedMessages type,
                FederatedHoeffdingTree modelRef, List<Integer> visitList) {
            this.ts = ts;
            this.type = type;
            this.model = modelRef;
            this.roundId = -1L;
            this.visitList = new ArrayList<>(visitList);
            this.forwardHopsCount = this.visitList.size() / 2;
        }

        FederatedMessages getFederatedMessageType() {
            return type;
        }

        FederatedHoeffdingTree getModel() {
            return model;
        }

        void setModel(FederatedHoeffdingTree m) {
            this.model = m;
        }

        long getRoundId() {
            return roundId;
        }

        List<Integer> getVisitList() {
            return visitList;
        }

        int getForwardHopsCount() {
            return forwardHopsCount;
        }
    }

    static final class SimMessage {
        private long timestamp;
        private SimMessageType type;
        private Request request;
        private SimActor source;

        SimMessage(long ts, SimMessageType type, Request request, SimActor source) {
            this.timestamp = ts;
            this.type = type;
            this.request = request;
            this.source = source;
        }

        long getTimestamp() {
            return timestamp;
        }

        SimMessageType getMessageType() {
            return type;
        }

        void setMessageType(SimMessageType t) {
            this.type = t;
        }

        Request getRequest() {
            return request;
        }

        SimActor getSource() {
            return source;
        }
    }

    interface SimActor {
        default int getLocationID() {
            return -1;
        }

        default void processSubmissionMessage(SimMessage message, SimMessageQueue queue) {
        }

        default void processCompletionMessage(SimMessage message, SimMessageQueue queue) {
        }
    }

    static final class SimMessageQueue {
        void scheduleMessageForward(SimActor from, SimActor to, SimMessage msg) {
            SimMessage hop = new SimMessage(msg.getTimestamp(), msg.getMessageType(), msg.getRequest(), from);
            to.processSubmissionMessage(hop, this);
        }

        void scheduleCompletionForward(SimActor from, SimActor to, SimMessage msg) {
            SimMessage hop = new SimMessage(msg.getTimestamp(), msg.getMessageType(), msg.getRequest(), from);
            to.processCompletionMessage(hop, this);
        }
    }

    static final class LocalNode implements SimActor {
        private final int id;
        private FederatedHoeffdingTree model;
        private int sinceLastFed = 0;
        private boolean requestedThisCycle = false;
        private final PrintWriter logger;
        private long trainingCounter = 0L;
        private long mergeThreshold = MERGE_THRESHOLD_BASE;
        private GlobalOrchestrator global;
        private long mergeTimeNano;
        private long totalBytesSent = 0L;
        private long totalBytesReceived = 0L;
        private int mergeRoundCount = 0;

        void setGlobal(GlobalOrchestrator g) {
            this.global = g;
        }

        // P2P state
        private List<LocalNode> peerNodes = new ArrayList<>();
        private final Map<Integer, LocalNode> idToActor = new HashMap<>();
        private final Map<Integer, Integer> idToIndex = new HashMap<>();
        private final List<Integer> visits = new ArrayList<>();
        // P2P lock
        private boolean distributedRequestInProgress = false;

        // VOTING: frozen copies of the other nodes' trees, replaced at every exchange
        private List<FederatedHoeffdingTree> peerModels = new ArrayList<>();

        void receivePeerModels(List<FederatedHoeffdingTree> models) {
            this.peerModels = models;
        }

        /**
         * Soft vote: own live tree plus the peer copies. Each tree contributes its
         * class distribution normalised to sum 1 (MOA returns raw counts for
         * majority-class
         * leaves and posteriors for Naive Bayes leaves, so without normalisation a big
         * leaf
         * would outweigh a small one regardless of correctness). Argmax of the sum is
         * the
         * prediction.
         */
        double[] ensembleVotes(Instance inst, int numClasses) {
            double[] sum = new double[numClasses];
            addNormalizedVotes(sum, this.model.getVotesForInstance(inst));
            for (FederatedHoeffdingTree peer : peerModels) {
                addNormalizedVotes(sum, peer.getVotesForInstance(inst));
            }
            return sum;
        }

        private static void addNormalizedVotes(double[] sum, double[] votes) {
            if (votes == null || votes.length == 0)
                return;
            double s = 0.0;
            for (double v : votes)
                s += v;
            if (s <= 0.0)
                return;
            int n = Math.min(sum.length, votes.length);
            for (int c = 0; c < n; c++)
                sum[c] += votes[c] / s;
        }

        LocalNode(int id, FederatedHoeffdingTree model, PrintWriter logger) {
            this.id = id;
            this.model = model;
            this.logger = logger;
            this.trainingCounter = 0L;
            this.mergeTimeNano = 0L;
            this.mergeThreshold = MERGE_THRESHOLD_BASE;

            // P2P state
            this.distributedRequestInProgress = false;
        }

        @Override
        public int getLocationID() {
            return id;
        }

        FederatedHoeffdingTree getModel() {
            return model;
        }

        private long getCounter() {
            return trainingCounter;
        }

        private void incCounter(long by) {
            trainingCounter += by;
        }

        private void resetCounter() {
            trainingCounter = 0;
        }

        long getMergeTimeNano() {
            return mergeTimeNano;
        }

        private long getThreshold() {
            return mergeThreshold;
        }

        private void bumpThreshold() {
            mergeThreshold = Math.min(mergeThreshold + MERGE_THRESHOLD_STEP, MERGE_THRESHOLD_CAP);
        }

        void setPeers(List<LocalNode> all) {
            this.peerNodes = new ArrayList<>(all);
            idToActor.clear();
            idToIndex.clear();
            for (int i = 0; i < peerNodes.size(); i++) {
                LocalNode n = peerNodes.get(i);
                idToActor.put(n.id, n);
                idToIndex.put(n.id, i);
            }
        }

        void onLocalInstanceProcessed(long ts, boolean federationEnabled, FederationMode mode,
                GlobalOrchestrator global, SimMessageQueue sMsg) {
            sinceLastFed++;
            incCounter(1);
            if (!federationEnabled)
                return;

            long count = getCounter();
            long need = getThreshold();

            if (mode == FederationMode.GLOBAL) {
                if (requestedThisCycle)
                    return; // already asked this round
                if (count >= need) {
                    bumpThreshold();
                    requestedThisCycle = true;
                    // Ask Global to start a round
                    FederatedRequest initReq = new FederatedRequest(ts, FederatedMessages.INITIATE);
                    SimMessage msg = new SimMessage(ts, SimMessageType.SUBMISSION, initReq, this);
                    if (global != null) {
                        sMsg.scheduleMessageForward(this, global, msg);
                        trace(logger, "[Node #%d] Initiate sent at ts=%d (local count=%d)%n", id, ts, sinceLastFed);
                    } else {
                        trace(logger, "[Node #%d][GLOBAL][WARN] Global not set; cannot initiate.%n", id);
                        requestedThisCycle = false;
                    }
                }
                return;
            }
            if (mode == FederationMode.VOTING) {
                if (count >= need) {
                    bumpThreshold();
                    votingExchange(ts, peerNodes, logger);
                }
                return;
            }

            if (mode == FederationMode.P2P) {
                // need peers wired and at least 2 nodes
                if (peerNodes == null || peerNodes.size() <= 1) {
                    if (count >= need) {
                        requestedThisCycle = false;
                        resetCounter();
                        sinceLastFed = 0;
                        trace(logger, "[Node #%d][P2P] Trigger reached, but cluster size=%d; skipping.%n",
                                id, (peerNodes == null ? 0 : peerNodes.size()));
                    }
                    return;
                }
                if (distributedRequestInProgress) {
                    if (count >= need) {
                        trace(logger, "[Node #%d][P2P] Trigger reached, but round in progress - waiting.%n", id);
                    }
                    return;
                }
                if (count >= need && !requestedThisCycle) {
                    bumpThreshold();
                    requestedThisCycle = true;
                    startP2P(ts, sMsg);
                }
                return;
            }
        }

        // P2P: initiation entry
        private void startP2P(long ts, SimMessageQueue queue) {
            if (peerNodes == null || peerNodes.isEmpty()) {
                trace(logger, "[Node #%d][P2P] startP2P aborted - peers not wired.%n", id);
                this.requestedThisCycle = false;
                return;
            }
            if (peerNodes.size() == 1) {
                trace(logger, "[Node #%d][P2P] startP2P aborted - only one node.%n", id);
                this.requestedThisCycle = false;
                this.sinceLastFed = 0;
                return;
            }

            distributedRequestInProgress = true;

            LocalNode firstPeer = peerNodes.get(0);
            if (firstPeer.getLocationID() == this.id) {
                // We are the first peer; build visits and start
                buildVisits();
                if (visits.isEmpty()) {
                    trace(logger, "[Node #%d][P2P] INITIATOR is FIRST, but visit list empty - abort.%n", id);
                    distributedRequestInProgress = false;
                    this.requestedThisCycle = false;
                    return;
                }
                trace(logger, "[Node #%d][P2P] INITIATOR is FIRST peer - visits=%s, forwardHops=%d%n",
                        id, visits, visits.size() / 2);
                dispatchMerge(ts, queue);
            } else {
                // Ask the first peer to start
                FederatedRequest quorumReq = new FederatedRequest(ts, FederatedMessages.QUORUM_START);
                SimMessage msg = new SimMessage(ts, SimMessageType.SUBMISSION, quorumReq, this);
                trace(logger, "[Node #%d][P2P] INITIATOR is NOT first. Sending QUORUM_START -> Node #%d%n",
                        id, firstPeer.getLocationID());
                queue.scheduleMessageForward(this, firstPeer, msg);
            }
        }

        private void buildVisits() {
            visits.clear();
            if (peerNodes == null || peerNodes.isEmpty() || !idToIndex.containsKey(id)) {
                trace(logger, "[Node #%d][P2P] buildVisits aborted - peers not wired yet.%n", id);
                return;
            }
            int size = peerNodes.size();
            int idx = idToIndex.get(id);

            if (IS_BACK_AND_FORTH_MERGE) {
                // forward 1...size-1, then backward size-2...1, then return to originator
                for (int hop = 1; hop < 2 * (size - 1); hop++) {
                    int offset = (hop <= size - 1) ? hop : 2 * (size - 1) - hop;
                    int peerIdx = (idx + offset) % size;
                    visits.add(peerNodes.get(peerIdx).getLocationID());
                }
                visits.add(id);
            } else {
                // random ring: all others once, then return
                for (LocalNode node : peerNodes) {
                    if (node.id != id)
                        visits.add(node.id);
                }
                Collections.shuffle(visits, new Random(42));
                visits.add(id);
            }
        }

        // P2P: send the traveling model along the visit path
        private void dispatchMerge(long ts, SimMessageQueue queue) {
            if (visits.isEmpty()) {
                trace(logger, "[Node #%d][P2P] dispatchMerge aborted - empty visit path.%n", id);
                distributedRequestInProgress = false;
                this.requestedThisCycle = false;
                return;
            }

            // snapshot our current model to start the accumulation
            FederatedHoeffdingTree snap = (FederatedHoeffdingTree) model.copy();
            long snapSize = getModelSizeBytes(snap);
            totalBytesSent += snapSize;
            mergeRoundCount++;
            FederatedRequest req = new FederatedRequest(ts, FederatedMessages.MERGE, snap, visits);
            req.addRoundBytes(snapSize);

            Integer firstId = visits.get(0);
            LocalNode first = idToActor.get(firstId);
            if (first == null) {
                trace(logger, "[Node #%d][P2P] dispatchMerge aborted - Peer %d not found.%n", id, firstId);
                distributedRequestInProgress = false;
                this.requestedThisCycle = false;
                return;
            }

            trace(logger, "[Node #%d][P2P] DISPATCH MERGE → Node #%d | path=%s | forwardHops=%d%n",
                    id, firstId, req.getVisitList(), req.getForwardHopsCount());

            // Reset our running stats after taking the snapshot
            model.treeRoot.resetBlockStats();

            SimMessage msg = new SimMessage(ts, SimMessageType.SUBMISSION, req, this);
            queue.scheduleMessageForward(this, first, msg);
        }

        // P2P: notify peers that the round is finished & clear local flags
        private void broadcastUnlock(long ts, SimMessageQueue queue) {
            trace(logger, "[Node #%d][P2P] Final hop reached - Broadcast unlock to all peers.%n", id);

            distributedRequestInProgress = false;
            requestedThisCycle = false;
            sinceLastFed = 0;

            for (LocalNode node : peerNodes) {
                if (node.getLocationID() == this.id)
                    continue;
                FederatedRequest unlock = new FederatedRequest(ts, FederatedMessages.UNLOCK);
                SimMessage m = new SimMessage(ts, SimMessageType.SUBMISSION, unlock, this);
                queue.scheduleMessageForward(this, node, m);
            }
        }

        @Override
        public void processSubmissionMessage(SimMessage message, SimMessageQueue queue) {
            Request r = message.getRequest();
            if (!(r instanceof FederatedRequest fr))
                return;

            long ts = message.getTimestamp();

            switch (fr.getFederatedMessageType()) {
                case MERGE: {
                    long rid = fr.getRoundId();
                    if (FEDERATION_MODE == FederationMode.GLOBAL && rid >= 0) {
                        trace(logger, "  [Node #%d] Merge request received (Round #%d). Sending local model copy.%n",
                                id, rid);
                        FederatedHoeffdingTree copyModel = (FederatedHoeffdingTree) this.model.copy();
                        totalBytesSent += getModelSizeBytes(copyModel);
                        mergeRoundCount++;
                        FederatedRequest reply = new FederatedRequest(
                                ts, FederatedMessages.MERGE,
                                copyModel, rid);
                        SimMessage completion = new SimMessage(ts, SimMessageType.COMPLETION, reply, this);
                        queue.scheduleCompletionForward(this, message.getSource(), completion);
                        break;
                    }

                    // P2P forward
                    if (FEDERATION_MODE == FederationMode.P2P) {
                        if (peerNodes == null || peerNodes.isEmpty())
                            break;

                        List<Integer> v = fr.getVisitList();
                        if (v == null || v.isEmpty() || !v.get(0).equals(this.id))
                            return;

                        v.remove(0); // consume this node
                        trace(logger, "  [Node #%d][P2P] Merge received. remaining=%s (forwardHops=%d)%n",
                                id, v, fr.getForwardHopsCount());

                        // merge incoming with our local snapshot, then adopt
                        FederatedHoeffdingTree incoming = fr.getModel();
                        long incomingSize = getModelSizeBytes(incoming);
                        totalBytesReceived += incomingSize;
                        trace(logger, "    [Node #%d][P2P] Merge step: Incoming <- Node #%d local copy%n", id, id);
                        long start = TimingUtils.getNanoCPUTimeOfCurrentThread();
                        incoming.merge((FederatedHoeffdingTree) this.model.copy());
                        long duration = TimingUtils.getNanoCPUTimeOfCurrentThread() - start;
                        mergeTimeNano += duration;
                        fr.setModel(incoming);
                        this.model = (FederatedHoeffdingTree) incoming.copy();
                        resetCounter();
                        this.sinceLastFed = 0;
                        this.model.treeRoot.resetBlockStats();

                        if (v.isEmpty()) {
                            trace(logger, "[ROUND-COMM] P2PRound | RoundBytes=%d KB%n",
                                    fr.getRoundBytes() / 1024);
                            broadcastUnlock(ts, queue);
                        } else if (v.size() > fr.getForwardHopsCount()) {
                            // still moving forward
                            int nextId = v.get(0);
                            LocalNode next = idToActor.get(nextId);
                            if (next == null) {
                                trace(logger, "    [Node #%d][P2P] Forward abort - next Peer %d not found.%n", id,
                                        nextId);
                                trace(logger, "[ROUND-COMM] P2PRound | RoundBytes=%d KB%n",
                                        fr.getRoundBytes() / 1024);
                                broadcastUnlock(ts, queue);
                                break;
                            }
                            long outgoingSize = getModelSizeBytes(incoming);
                            totalBytesSent += outgoingSize;
                            fr.addRoundBytes(outgoingSize);
                            trace(logger, "    [Node #%d][P2P] Forward → Node #%d | hopBytes=%d KB%n", id, nextId,
                                    outgoingSize / 1024);
                            message.setMessageType(SimMessageType.SUBMISSION);
                            queue.scheduleMessageForward(this, next, message);
                        } else {
                            // backward toward originator
                            int nextId = v.get(0);
                            LocalNode next = idToActor.get(nextId);
                            if (next == null) {
                                trace(logger, "    [Node #%d][P2P] Backward abort - next Peer %d not found.%n", id,
                                        nextId);
                                trace(logger, "[ROUND-COMM] P2PRound | RoundBytes=%d KB%n",
                                        fr.getRoundBytes() / 1024);
                                broadcastUnlock(ts, queue);
                                break;
                            }
                            long outgoingSize = getModelSizeBytes(incoming);
                            totalBytesSent += outgoingSize;
                            fr.addRoundBytes(outgoingSize);
                            trace(logger, "    [Node #%d][P2P] Backward <- to Node #%d | hopBytes=%d KB%n", id, nextId,
                                    outgoingSize / 1024);
                            message.setMessageType(SimMessageType.COMPLETION);
                            queue.scheduleCompletionForward(this, next, message);
                        }
                        break;
                    }
                    // INDEPENDENT: ignore
                    break;
                }

                case UPDATE: {
                    // GLOBAL UPDATE
                    long rid = fr.getRoundId();
                    if (FEDERATION_MODE == FederationMode.GLOBAL && rid >= 0) {
                        resetCounter();
                        long incomingSize = getModelSizeBytes(fr.getModel());
                        totalBytesReceived += incomingSize;
                        trace(logger,
                                "  [Node #%d] Update received (Round #%d). Replacing local model and resetting counters...%n",
                                id, rid);
                        FederatedHoeffdingTree merged = fr.getModel();
                        this.model = (FederatedHoeffdingTree) merged.copy();
                        this.sinceLastFed = 0;
                        this.requestedThisCycle = false;

                        SimMessage ack = new SimMessage(ts, SimMessageType.COMPLETION, fr, this);
                        queue.scheduleCompletionForward(this, message.getSource(), ack);
                        trace(logger, "  [Node #%d] Update applied; counters reset. Ack sent (Round #%d).%n", id, rid);
                    }
                    break;
                }

                // P2P first-peer start
                case QUORUM_START: {
                    if (FEDERATION_MODE != FederationMode.P2P)
                        break;
                    if (peerNodes == null || peerNodes.isEmpty()) {
                        trace(logger, "[Node #%d][P2P] QUORUM_START ignored - peers not wired.%n", id);
                        break;
                    }
                    LocalNode firstPeer = peerNodes.get(0);
                    if (firstPeer.getLocationID() != this.id) {
                        break;
                    }

                    if (distributedRequestInProgress) {
                        trace(logger, "[Node #%d][P2P] QUORUM_START Denied (merge in progress).%n", id);
                        break;
                    }
                    distributedRequestInProgress = true;
                    buildVisits();
                    if (visits.isEmpty()) {
                        trace(logger, "[Node #%d][P2P] QUORUM_START aborted - empty visit path.%n", id);
                        distributedRequestInProgress = false;
                        break;
                    }
                    trace(logger, "[Node #%d][P2P] QUORUM_START accepted - start merge. visits=%s forwardHops=%d%n",
                            id, visits, visits.size() / 2);
                    dispatchMerge(ts, queue);
                    break;
                }

                case UNLOCK: {
                    if (FEDERATION_MODE == FederationMode.P2P) {
                        distributedRequestInProgress = false;
                        this.requestedThisCycle = false;
                        this.sinceLastFed = 0;
                        mergeRoundCount++;
                        trace(logger,
                                "  [Node #%d][P2P] Unlock received - local state cleared. Ready for next round.%n", id);
                        SimMessage ack = new SimMessage(ts, SimMessageType.COMPLETION, fr, this);
                        queue.scheduleCompletionForward(this, message.getSource(), ack);
                    }
                    break;
                }

                case INITIATE:
                    // Initiate is only for global; in P2P we use QUORUM_START
                    if (FEDERATION_MODE == FederationMode.GLOBAL) {
                        throw new IllegalStateException("Initiate must be sent to Global, not Local.");
                    }
                    break;
            }
        }

        @Override
        public void processCompletionMessage(SimMessage message, SimMessageQueue queue) {
            Request r = message.getRequest();
            if (!(r instanceof FederatedRequest fr))
                return;

            long ts = message.getTimestamp();

            // P2P backward hops
            if (FEDERATION_MODE == FederationMode.P2P) {
                if (peerNodes == null || peerNodes.isEmpty())
                    return;
                if (fr.getFederatedMessageType() == FederatedMessages.MERGE) {
                    List<Integer> v = fr.getVisitList();
                    if (v == null || v.isEmpty() || !v.get(0).equals(this.id))
                        return;
                    v.remove(0);

                    trace(logger, "  [Node #%d][P2P] Merge completion hop. remainingBack=%s%n", id, v);

                    FederatedHoeffdingTree incoming = fr.getModel();
                    long incomingSize = getModelSizeBytes(incoming);
                    totalBytesReceived += incomingSize;
                    trace(logger, "    [Node #%d][P2P] Merge(step-back): Incoming <- Node #%d local copy%n", id, id);
                    long start = TimingUtils.getNanoCPUTimeOfCurrentThread();
                    incoming.merge((FederatedHoeffdingTree) this.model.copy());
                    long duration = TimingUtils.getNanoCPUTimeOfCurrentThread() - start;
                    mergeTimeNano += duration;
                    fr.setModel(incoming);
                    // adopt merged model
                    this.model = (FederatedHoeffdingTree) incoming.copy();
                    resetCounter();
                    this.sinceLastFed = 0;
                    this.model.treeRoot.resetBlockStats();

                    if (!v.isEmpty()) {
                        int nextId = v.get(0);
                        LocalNode next = idToActor.get(nextId);
                        if (next == null) {
                            trace(logger, "    [Node #%d][P2P] Backward abort - next Peer %d not found.%n", id, nextId);
                            trace(logger, "[ROUND-COMM] P2PRound | RoundBytes=%d KB%n",
                                    fr.getRoundBytes() / 1024);
                            broadcastUnlock(ts, queue);
                            return;
                        }
                        long outgoingSize = getModelSizeBytes(incoming);
                        totalBytesSent += outgoingSize;
                        fr.addRoundBytes(outgoingSize);
                        trace(logger, "    [Node #%d][P2P] Backward <- to Node #%d | hopBytes=%d KB%n", id, nextId,
                                outgoingSize / 1024);
                        queue.scheduleCompletionForward(this, next, message);
                    } else {
                        // final hop - originator keeps the model, nothing is sent onward
                        this.model.treeRoot.resetBlockStats();
                        trace(logger, "[ROUND-COMM] P2PRound | RoundBytes=%d KB%n",
                                fr.getRoundBytes() / 1024);
                        broadcastUnlock(ts, queue);
                    }
                    return;
                }
            }
            // GLOBAL completions handled by GlobalOrchestrator
        }

        int debugPeerCount() {
            return (peerNodes == null) ? 0 : peerNodes.size();
        }

        boolean distributedBusy() {
            return distributedRequestInProgress;
        }

        void forceStartP2P(long ts, SimMessageQueue queue) {
            if (peerNodes == null || peerNodes.size() <= 1)
                return;
            if (distributedRequestInProgress)
                return;
            requestedThisCycle = true;
            distributedRequestInProgress = false;
            startP2P(ts, queue);
            trace(logger, "[Node #%d][GLOBAL-INTERVAL][P2P] Forced P2P start at ts=%d%n", id, ts);
        }

    }

    static final class GlobalOrchestrator implements SimActor {

        static final class RoundInfo {
            final long id;
            final int initiatorId;
            final long startedAtTs;
            final int expectedNodes;
            final LinkedHashMap<Integer, FederatedHoeffdingTree> collected = new LinkedHashMap<>();
            int updatesAcks = 0;
            long roundBytes = 0;

            RoundInfo(long id, int initiatorId, long startedAtTs, int expectedNodes) {
                this.id = id;
                this.initiatorId = initiatorId;
                this.startedAtTs = startedAtTs;
                this.expectedNodes = expectedNodes;
            }
        }

        private long roundSeq = 0L;
        private RoundInfo active = null;
        private final List<LocalNode> nodes;
        private long mergeTimeNano = 0L;

        private final PrintWriter logger;

        GlobalOrchestrator(List<LocalNode> nodes, PrintWriter logger) {
            this.nodes = nodes;
            this.logger = logger;
        }

        long getMergeTimeNano() {
            return mergeTimeNano;
        }

        @Override
        public void processSubmissionMessage(SimMessage message, SimMessageQueue queue) {
            Request r = message.getRequest();
            if (!(r instanceof FederatedRequest fr))
                return;

            long ts = message.getTimestamp();

            // GLOBAL federation orchestration
            if (fr.getFederatedMessageType() == FederatedMessages.INITIATE) {
                final int requesterId = (message.getSource() instanceof LocalNode ln) ? ln.id : -1;

                // Allow only the first initiate
                if (active != null) {
                    trace(logger,
                            "[Global] Initiate denied by Node #%d at ts=%d (lock held by Round #%d, initiator Node #%d).%n",
                            requesterId, ts, active.id, active.initiatorId);
                    return;
                }

                // ACCEPT: acquire lock & create round
                long roundId = ++roundSeq;
                RoundInfo ri = new RoundInfo(roundId, requesterId, ts, nodes.size());
                active = ri;

                trace(logger, "[Global] Initiate accepted by Node #%d at ts=%d - LOCK ACQUIRED (Round #%d).%n",
                        requesterId, ts, roundId);

                // Broadcast merge to all nodes
                for (LocalNode node : nodes) {
                    FederatedRequest mergeReq = new FederatedRequest(ts, FederatedMessages.MERGE, null, roundId);
                    SimMessage mergeMsg = new SimMessage(ts, SimMessageType.SUBMISSION, mergeReq, this);
                    trace(logger, "  [Global] -> Merge sent to Node #%d (Round #%d).%n", node.id, roundId);
                    queue.scheduleMessageForward(this, node, mergeMsg);
                }
                return;
            }

            // MERGE/UPDATE should not arrive at Global as SUBMISSION in GLOBAL mode
            throw new IllegalStateException("Unexpected submission to Global: " + fr.getFederatedMessageType());
        }

        @Override
        public void processCompletionMessage(SimMessage message, SimMessageQueue queue) {
            Request r = message.getRequest();
            if (!(r instanceof FederatedRequest fr))
                return;

            // No active round
            RoundInfo ri = active;
            if (ri == null) {
                trace(logger, "[Global][WARN] Completion with no active round. Type=%s%n",
                        fr.getFederatedMessageType());
                return;
            }

            switch (fr.getFederatedMessageType()) {
                case MERGE: {
                    int fromNode = (message.getSource() instanceof LocalNode ln) ? ln.id : -1;
                    ri.collected.put(fromNode, fr.getModel());
                    long incomingSize = getModelSizeBytes(fr.getModel());
                    ri.roundBytes += incomingSize;
                    trace(logger, "  [Global] <- Merge model received from Node #%d (Round #%d) - collected %d/%d.%n",
                            fromNode, ri.id, ri.collected.size(), ri.expectedNodes);

                    if (ri.collected.size() == ri.expectedNodes) {
                        // All models are in, start merging
                        trace(logger, "[Global] All %d models collected - BEGIN MERGE (Round #%d). Order: %s%n",
                                ri.expectedNodes, ri.id, ri.collected.keySet());

                        Iterator<Map.Entry<Integer, FederatedHoeffdingTree>> it = ri.collected.entrySet().iterator();
                        Map.Entry<Integer, FederatedHoeffdingTree> first = it.next();
                        int seedNode = first.getKey();
                        FederatedHoeffdingTree merged = (FederatedHoeffdingTree) first.getValue().copy();
                        trace(logger, "    [Global] Seed = Node #%d%n", seedNode);

                        while (it.hasNext()) {
                            Map.Entry<Integer, FederatedHoeffdingTree> e = it.next();
                            int srcNode = e.getKey();
                            trace(logger, "    [Global] Merge step: Seed <- Node #%d (Round #%d)%n", srcNode, ri.id);
                            long start = TimingUtils.getNanoCPUTimeOfCurrentThread();
                            merged.merge((FederatedHoeffdingTree) e.getValue().copy());
                            long duration = TimingUtils.getNanoCPUTimeOfCurrentThread() - start;
                            mergeTimeNano += duration;
                        }

                        merged.treeRoot.resetBlockStats();
                        trace(logger,
                                "[Global] Merge complete - resetBlockStats done - Broadcase Update (Round #%d).%n",
                                ri.id);
                        long mergedSize = getModelSizeBytes(merged);
                        trace(logger, "[Global] Merged model size = %d KB (Round #%d)%n",
                                mergedSize / 1024, ri.id);
                        ri.roundBytes += mergedSize * nodes.size();

                        // Broadcast update with merged copy per node
                        for (LocalNode node : nodes) {
                            FederatedRequest upd = new FederatedRequest(message.getTimestamp(),
                                    FederatedMessages.UPDATE, (FederatedHoeffdingTree) merged.copy(), ri.id);
                            SimMessage updMsg = new SimMessage(message.getTimestamp(), SimMessageType.SUBMISSION, upd,
                                    this);
                            trace(logger, "  [Global] -> Update sent to Node #%d (Round #%d).%n", node.id, ri.id);
                            queue.scheduleMessageForward(this, node, updMsg);
                        }
                    }
                    break;
                }

                case UPDATE: {
                    int nodeId = (message.getSource() instanceof LocalNode ln) ? ln.id : -1;
                    ri.updatesAcks++;
                    trace(logger, "  [Global] <- Update ack from Node #%d (Round #%d) - %d/%d acks.%n",
                            nodeId, ri.id, ri.updatesAcks, ri.expectedNodes);

                    if (ri.updatesAcks == ri.expectedNodes) {
                        long finished = message.getTimestamp();
                        long duration = Math.max(0, finished - ri.startedAtTs);
                        trace(logger,
                                "[Global] Update round complete - Lock Released (Round #%d). Duration=%d instances. Initiator=Node #%d%n",
                                ri.id, duration, ri.initiatorId);
                        trace(logger, "[ROUND-COMM] Round=%d | RoundBytes=%d KB%n",
                                ri.id, ri.roundBytes / 1024);
                        active = null;
                    }
                    break;
                }

                case INITIATE:
                    throw new IllegalStateException("Initiate should not arrive here as completion.");
                case QUORUM_START:
                case UNLOCK:
                    throw new IllegalStateException("Unimplemented cases.");
            }
        }
    }

    /**
     * Sample from a Gamma(shape, scale) distribution using:
     * Marsaglia & Tsang (2000) algorithm
     */
    private static double sampleGamma(double shape, double scale, Random rng) {
        if (shape <= 0.0 || scale <= 0.0) {
            throw new IllegalArgumentException("Gamma shape and scale must be > 0");
        }

        // For shape < 1, use the standard boosting trick:
        // Gamma(a) = Gamma(a+1) * U^{1/a}
        if (shape < 1.0) {
            double u = rng.nextDouble();
            double g = sampleGamma(shape + 1.0, 1.0, rng); // shape+1, scale=1
            return scale * g * Math.pow(u, 1.0 / shape);
        }

        // Marsaglia & Tsang’s method for shape >= 1
        double d = shape - 1.0 / 3.0;
        double c = 1.0 / Math.sqrt(9.0 * d);

        while (true) {
            double x = rng.nextGaussian(); // N(0,1)
            double v = 1.0 + c * x;
            if (v <= 0.0) {
                // try again
                continue;
            }
            v = v * v * v; // v^3
            double u = rng.nextDouble(); // U(0,1)

            // Squeeze step (fast acceptance region)
            if (u < 1.0 - 0.0331 * x * x * x * x) {
                return scale * d * v;
            }

            // Full acceptance condition
            if (Math.log(u) < 0.5 * x * x + d * (1.0 - v + Math.log(v))) {
                return scale * d * v;
            }
        }
    }

    /**
     * Generates a Dirichlet vector by sampling K Gamma variables and normalizing.
     * X_i = Y_i / sum(Y) where Y_i ~ Gamma(alpha, 1)
     */
    private static double[] sampleSymmetricDirichlet(int dim, double alpha, Random rng) {
        double[] result = new double[dim];
        double sum = 0.0;

        for (int i = 0; i < dim; i++) {
            double g = sampleGamma(alpha, 1.0, rng);
            result[i] = g;
            sum += g;
        }

        // Normalize so sum = 1.0
        for (int i = 0; i < dim; i++) {
            result[i] /= sum;
        }
        return result;
    }

    /**
     * Standard Roulette Wheel selection from a probability array.
     */
    private static int sampleCategorical(double[] probabilities, Random rng) {
        double u = rng.nextDouble();
        double cumulative = 0.0;
        for (int i = 0; i < probabilities.length; i++) {
            cumulative += probabilities[i];
            if (u <= cumulative) {
                return i;
            }
        }
        return probabilities.length - 1; // Fallback for floating point errors
    }

    public static void main(String[] args) throws Exception {
        final long programStartNs = System.nanoTime();
        double totalRuntimeMs = 0.0;
        // Inputs
        if (args.length < 1) {
            System.err.println(
                    "No dataset specified. Usage: <dataset path> [report path] [numModels] [seed] [mode] [backAndForth] [fedEnabled] [thrBase] [thrStep] [thrCap] [triggerPolicy] [interval] [dirichlet] [alpha] [report: off|on|N]");
            System.err.println(
                    "Supported datasets: Airline ARFF, CoverType ARFF, Poker ARFF, KDD99 CSV, Gas Sensor Drift");
            System.exit(1);
        }
        String argPath = args[0];
        String outPath = (args.length >= 2) ? args[1] : "prequential_report.txt";
        int numModels = (args.length >= 3) ? Integer.parseInt(args[2]) : 3;
        long assignSeed = (args.length >= 4) ? Long.parseLong(args[3]) : 42L;
        if (args.length >= 5) {
            String modeStr = args[4].trim().toUpperCase();
            try {
                FEDERATION_MODE = FederationMode.valueOf(modeStr);
            } catch (IllegalArgumentException e) {
                System.err.println("Invalid federation mode: " + args[4]);
                System.err.println("Valid values: INDEPENDENT, GLOBAL, P2P, VOTING");
                System.exit(1);
            }
        }
        if (args.length >= 6) {
            IS_BACK_AND_FORTH_MERGE = Boolean.parseBoolean(args[5]);
        }

        if (args.length >= 7) {
            FEDERATION_ENABLED = Boolean.parseBoolean(args[6]);
        }
        if (args.length >= 8)
            MERGE_THRESHOLD_BASE = Long.parseLong(args[7]);
        if (args.length >= 9)
            MERGE_THRESHOLD_STEP = Long.parseLong(args[8]);
        if (args.length >= 10)
            MERGE_THRESHOLD_CAP = Long.parseLong(args[9]);
        if (args.length >= 12) {
            String trig = args[10].trim().toUpperCase(); // policy
            try {
                TRIGGER_POLICY = TriggerPolicy.valueOf(trig);
            } catch (IllegalArgumentException e) {
                System.err.println("Invalid trigger policy: " + args[10]);
                System.err.println("Valid values: LOCAL_THRESHOLD, GLOBAL_INTERVAL");
                System.exit(1);
            }
            GLOBAL_MERGE_INTERVAL = Long.parseLong(args[11]); // interval
        }
        if (args.length >= 13) {
            USE_DIRICHLET = Boolean.parseBoolean(args[12]);
        }
        if (args.length >= 14) {
            DIRICHLET_ALPHA = Double.parseDouble(args[13]);
            if (DIRICHLET_ALPHA < 0.0) {
                System.err.println("DIRICHLET_ALPHA must be >= 0. Provided: " + DIRICHLET_ALPHA);
                System.exit(1);
            }
        }
        if (args.length >= 15) {
            String rep = args[14].trim().toLowerCase();
            if (rep.equals("off") || rep.equals("0") || rep.equals("false")) {
                REPORT_EVERY = 0;
            } else if (rep.equals("on") || rep.equals("true")) {
                REPORT_EVERY = 1000;
            } else {
                REPORT_EVERY = Integer.parseInt(rep);
                if (REPORT_EVERY < 0) {
                    System.err.println("Report interval must be >= 0. Provided: " + args[14]);
                    System.exit(1);
                }
            }
        }
        if (numModels <= 0) {
            System.err.println("numModels must be >= 1");
            System.exit(1);
        }

        Path dataPath = Path.of(argPath);
        if (!Files.exists(dataPath)) {
            System.err.println("Could not find file: " + dataPath.toAbsolutePath());
            System.err.println("Current working dir is: " + System.getProperty("user.dir"));
            System.exit(1);
        }

        // Load data
        Instances data = loadInstancesAuto(dataPath);

        // the stream is processed in file order
        Instances stream = data;

        // build nodes with identical headers
        InstancesHeader header = new InstancesHeader(stream);

        List<LocalNode> nodes = new ArrayList<>();

        Path reportPath = Path.of(outPath);
        String csvOutPath = outPath.replaceFirst("(\\.\\w+)?$", "_predictions.csv");
        Path csvPath = Path.of(csvOutPath);

        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(reportPath));
                PrintWriter csvOut = WRITE_PREDICTIONS_CSV ? new PrintWriter(Files.newBufferedWriter(csvPath)) : null) {

            // CSV header
            if (csvOut != null)
                csvOut.println("actual,predicted,node");

            for (int m = 0; m < numModels; m++) {
                FederatedHoeffdingTree model = createHT();
                model.setModelContext(header);
                model.prepareForUse();
                nodes.add(new LocalNode(m, model, out));
            }
            // peers for P2P
            for (LocalNode n : nodes)
                n.setPeers(nodes);

            trace(out, "[BOOT] Wired P2P peers: numNodes=%d%n", nodes.size());
            for (LocalNode n : nodes)
                trace(out, "  [BOOT] Node #%d peerCount=%d%n", n.getLocationID(), n.debugPeerCount());

            writeDatasetSection(out, data, dataPath);
            out.println();
            out.println("=== Model & Evaluation ===");
            FederatedHoeffdingTree ref = nodes.get(0).getModel();
            out.printf("Model type: %s%n", ref.getClass().getSimpleName());
            out.printf(
                    "Hyperparams: gracePeriod=%d, splitCriterion=%s, splitConfidence=%.3f, tieThreshold=%.3f, leafPrediction=%s, nbThreshold=%d%n",
                    ref.gracePeriodOption.getValue(),
                    ref.splitCriterionOption.getValueAsCLIString(),
                    ref.splitConfidenceOption.getValue(),
                    ref.tieThresholdOption.getValue(),
                    ref.leafpredictionOption.getValueAsCLIString(),
                    ref.nbThresholdOption.getValue());
            out.printf("Evaluation: Prequential%n");
            String assignStr = USE_DIRICHLET
                    ? ("dirichlet (alpha=" + DIRICHLET_ALPHA + ", seed=" + assignSeed + ")")
                    : ("random (seed=" + assignSeed + ")");
            out.printf("Models: %d, Assignment = %s%n", nodes.size(), assignStr);
            out.printf("WindowSize=%d, ReportEvery=%d%n", WINDOW_SIZE, REPORT_EVERY);
            out.printf(
                    "Federation: enabled=%s, mode=%s, back_and_forth_enabled=%s, MERGE_THRESHOLD_BASE=%d, MERGE_THRESHOLD_STEP=%d, MERGE_THRESHOLD_CAP=%d%n",
                    FEDERATION_ENABLED, FEDERATION_MODE, IS_BACK_AND_FORTH_MERGE, MERGE_THRESHOLD_BASE,
                    MERGE_THRESHOLD_STEP, MERGE_THRESHOLD_CAP);
            out.printf("TriggerPolicy=%s, GlobalMergeInterval=%d%n", TRIGGER_POLICY, GLOBAL_MERGE_INTERVAL);

            out.println("=== Multi-Model Streaming Log ===");
            out.flush();

            // message sMsg + global orchestrator
            SimMessageQueue sMsg = new SimMessageQueue();
            GlobalOrchestrator global = new GlobalOrchestrator(nodes, out);
            for (LocalNode n : nodes)
                n.setGlobal(global);

            // run
            prequentialEvaluateMulti(stream, nodes, WINDOW_SIZE, REPORT_EVERY, assignSeed,
                    FEDERATION_ENABLED, FEDERATION_MODE, global, sMsg, out, csvOut);

            long programEndNs = System.nanoTime();
            totalRuntimeMs = ns2ms(programEndNs - programStartNs);
            out.printf("%nOverall runtime: %.3f ms%n", totalRuntimeMs);
            out.println();
            out.println("Report complete.");
            out.flush();
        }

        if (WRITE_PREDICTIONS_CSV)
            System.out.println("Wrote predictions CSV to: " + csvPath.toAbsolutePath());
    }

    private static FederatedHoeffdingTree createHT() {
        FederatedHoeffdingTree clf = new FederatedHoeffdingTree();
        clf.gracePeriodOption.setValue(100);
        clf.splitCriterionOption.setValueViaCLIString("InfoGainSplitCriterion");
        clf.splitConfidenceOption.setValue(0.1);
        clf.tieThresholdOption.setValue(0.1);
        clf.leafpredictionOption.setValueViaCLIString("NBAdaptive");
        clf.nbThresholdOption.setValue(0);
        return clf;
    }

    /**
     * Convert Nanoseconds to Milliseconds
     *
     * @param nanoseconds
     * @return
     */
    public static double ns2ms(long nanoseconds) {
        return nanoseconds * 0.000001d;
    }

    private static String csvEscape(String s) {
        if (s == null)
            return "";
        boolean needsQuote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        if (!needsQuote)
            return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    /**
     * VOTING round: every node takes a snapshot of its tree and receives the other
     * M-1 snapshots.
     * No merging. Upload per node is one model, as in Global FL; download is M-1
     * models.
     */
    private static void votingExchange(long ts, List<LocalNode> nodes, PrintWriter out) {
        if (nodes == null || nodes.size() <= 1)
            return;
        int M = nodes.size();
        List<FederatedHoeffdingTree> snaps = new ArrayList<>(M);
        List<Long> sizes = new ArrayList<>(M);
        long roundBytes = 0L;
        for (LocalNode n : nodes) {
            FederatedHoeffdingTree snap = (FederatedHoeffdingTree) n.getModel().copy();
            long size = getModelSizeBytes(snap);
            snaps.add(snap);
            sizes.add(size);
            n.totalBytesSent += size;
            n.mergeRoundCount++;
            roundBytes += size;
        }
        for (int k = 0; k < M; k++) {
            List<FederatedHoeffdingTree> peers = new ArrayList<>(M - 1);
            long received = 0L;
            for (int j = 0; j < M; j++) {
                if (j == k)
                    continue;
                peers.add(snaps.get(j));
                received += sizes.get(j);
            }
            LocalNode n = nodes.get(k);
            n.receivePeerModels(peers);
            n.totalBytesReceived += received;
            n.resetCounter(); // same as the update handler in GLOBAL mode: one round serves all nodes
            n.sinceLastFed = 0;
            roundBytes += received;
        }
        trace(out, "[VOTING] exchange at ts=%d: %d snapshots distributed%n", ts, M);
        trace(out, "[ROUND-COMM] VotingRound | RoundBytes=%d KB%n", roundBytes / 1024);
    }

    private static void triggerGlobalIntervalMerge(
            long ts,
            List<LocalNode> nodes,
            FederationMode mode,
            GlobalOrchestrator global,
            SimMessageQueue sMsg,
            PrintWriter out) {

        if (nodes == null || nodes.isEmpty())
            return;

        // For P2P modes, avoid kicking off a new round if anyone is busy.
        if (mode == FederationMode.P2P) {
            for (LocalNode n : nodes) {
                if (n.distributedBusy()) {
                    trace(out, "[GLOBAL-INTERVAL] Skip trigger at ts=%d - P2P merge in progress.%n", ts);
                    return;
                }
            }
        }

        LocalNode initiator = nodes.get(0); // first node is the initiator
        switch (mode) {
            case INDEPENDENT:
                // nothing to do
                break;
            case VOTING: {
                votingExchange(ts, nodes, out);
                break;
            }
            case GLOBAL: {
                FederatedRequest initReq = new FederatedRequest(ts, FederatedMessages.INITIATE);
                SimMessage msg = new SimMessage(ts, SimMessageType.SUBMISSION, initReq, initiator);
                trace(out, "[GLOBAL-INTERVAL][GLOBAL] Initiate sent by Node #%d at ts=%d%n",
                        initiator.getLocationID(), ts);
                if (global != null)
                    sMsg.scheduleMessageForward(initiator, global, msg);
                break;
            }

            case P2P: {
                initiator.forceStartP2P(ts, sMsg);
                break;
            }
        }
    }

    private static void prequentialEvaluateMulti(Instances stream,
            List<LocalNode> nodes,
            int windowSize, int reportEvery,
            long assignmentSeed,
            boolean federationEnabled,
            FederationMode mode,
            GlobalOrchestrator global,
            SimMessageQueue sMsg,
            PrintWriter out,
            PrintWriter csvOut) {

        int M = nodes.size();
        int C = stream.classAttribute().numValues();
        long[][] tp = new long[M][C]; // true positives per class
        long[][] predPos = new long[M][C]; // predicted positives per class
        long[][] actPos = new long[M][C]; // actual positives per class

        Random rng = new Random(assignmentSeed);
        Map<Integer, double[]> classDistributions = new HashMap<>();
        Random partitionRng = new Random(assignmentSeed);
        if (USE_DIRICHLET) {
            trace(out, "Dirichlet Strategy Enabled (Alpha=%.2f) - Distributions will be generated lazily.%n",
                    DIRICHLET_ALPHA);
        }
        int[] seen = new int[M];
        int[] correctCumulative = new int[M];
        @SuppressWarnings("unchecked")
        Deque<Boolean>[] windows = new Deque[M];
        int[] correctWindow = new int[M];
        for (int m = 0; m < M; m++)
            windows[m] = new ArrayDeque<>(windowSize);

        int[] assignedCount = new int[M];
        long globalSeen = 0L;

        for (int i = 0; i < stream.numInstances(); i++) {
            Instance inst = stream.instance(i);

            int m;
            if (USE_DIRICHLET) {
                // Get the actual class of this instance
                int trueClass = (int) inst.classValue();

                if (trueClass < 0) {
                    m = rng.nextInt(M); // Missing class -> Random
                } else {
                    // If we haven't seen this class yet, generate its distribution now
                    if (!classDistributions.containsKey(trueClass)) {
                        double[] newDist = sampleSymmetricDirichlet(M, DIRICHLET_ALPHA, partitionRng);
                        classDistributions.put(trueClass, newDist);
                        trace(out, "[New Class Discovered] Class %d assigned dist: %s%n", trueClass,
                                Arrays.toString(newDist));
                    }

                    // Sample from the cached distribution
                    double[] probs = classDistributions.get(trueClass);
                    m = sampleCategorical(probs, rng);
                }

            } else {
                m = rng.nextInt(M); // random assignment
            }
            assignedCount[m]++;

            FederatedHoeffdingTree model = nodes.get(m).getModel();

            // predict before train (VOTING: soft vote over own tree + peer copies, no
            // merge)
            double[] votes = (federationEnabled && mode == FederationMode.VOTING)
                    ? nodes.get(m).ensembleVotes(inst, C)
                    : model.getVotesForInstance(inst);
            int pred = argmax(votes);
            int truth = (int) inst.classValue();
            if (csvOut != null) {
                Attribute classAttr = stream.classAttribute();
                String actualLabel = (truth >= 0 && truth < C) ? classAttr.value(truth) : "";
                String predLabel = (pred >= 0 && pred < C) ? classAttr.value(pred) : "";
                csvOut.println(csvEscape(actualLabel) + "," + csvEscape(predLabel) + "," + m);
            }
            if (pred >= 0 && pred < C)
                predPos[m][pred]++;
            if (truth >= 0 && truth < C)
                actPos[m][truth]++;
            if (pred == truth && truth >= 0 && truth < C)
                tp[m][truth]++;

            boolean isCorrect = (pred == truth);

            seen[m]++;
            if (isCorrect)
                correctCumulative[m]++;

            if (windows[m].size() == windowSize) {
                boolean oldest = windows[m].removeFirst();
                if (oldest)
                    correctWindow[m]--;
            }
            windows[m].addLast(isCorrect);
            if (isCorrect)
                correctWindow[m]++;

            // train
            model.trainOnInstance(inst);

            globalSeen++;
            if (federationEnabled) {
                if (TRIGGER_POLICY == TriggerPolicy.LOCAL_THRESHOLD) {
                    nodes.get(m).onLocalInstanceProcessed(globalSeen, true, mode, global, sMsg);
                } else if (TRIGGER_POLICY == TriggerPolicy.GLOBAL_INTERVAL) {
                    nodes.get(m).onLocalInstanceProcessed(globalSeen, false, mode, global, sMsg);

                    if (GLOBAL_MERGE_INTERVAL > 0 && (globalSeen % GLOBAL_MERGE_INTERVAL == 0)) {
                        triggerGlobalIntervalMerge(globalSeen, nodes, mode, global, sMsg, out);
                    }
                } else {
                    System.err.println("Invalid trigger Policy");
                    System.exit(1);
                }
            } else {
                nodes.get(m).onLocalInstanceProcessed(globalSeen, false, mode, global, sMsg);
            }

            // periodic report
            if (reportEvery > 0 && (globalSeen % reportEvery == 0)) {
                out.println();
                log(out, "GLOBAL Seen=%d%n", globalSeen);

                for (int k = 0; k < M; k++) {
                    double accCum = (seen[k] == 0) ? Double.NaN : (double) correctCumulative[k] / seen[k];
                    double accWin = (windows[k].isEmpty()) ? Double.NaN : (double) correctWindow[k] / windows[k].size();
                    double mergeMs = ns2ms(nodes.get(k).getMergeTimeNano());
                    boolean hasData = seen[k] > 0;

                    double pSum = 0.0, rSum = 0.0, f1Sum = 0.0;
                    int cnt = 0;
                    for (int c = 0; c < C; c++) {
                        long TPc = tp[k][c];
                        long FPc = predPos[k][c] - TPc;
                        long FNc = actPos[k][c] - TPc;

                        boolean hasPred = (TPc + FPc) > 0;
                        boolean hasActual = (TPc + FNc) > 0;
                        if (!(hasPred || hasActual))
                            continue;

                        double Pc = hasPred ? (double) TPc / (TPc + FPc) : 0.0;
                        double Rc = hasActual ? (double) TPc / (TPc + FNc) : 0.0;
                        double F1c = (Pc + Rc == 0.0) ? 0.0 : (2.0 * Pc * Rc / (Pc + Rc));

                        pSum += Pc;
                        rSum += Rc;
                        f1Sum += F1c;
                        cnt++;
                    }
                    double macroPrec = (cnt == 0) ? Double.NaN : pSum / cnt;
                    double macroRec = (cnt == 0) ? Double.NaN : rSum / cnt;
                    double macroF1 = (cnt == 0) ? Double.NaN : f1Sum / cnt;

                    // micro
                    long sumTP = 0L, sumPred = 0L, sumAct = 0L;
                    for (int c = 0; c < C; c++) {
                        sumTP += tp[k][c];
                        sumPred += predPos[k][c];
                        sumAct += actPos[k][c];
                    }
                    Double microPrec = hasData ? ((sumPred == 0) ? 0.0 : (double) sumTP / sumPred) : Double.NaN;
                    Double microRec = hasData ? ((sumAct == 0) ? 0.0 : (double) sumTP / sumAct) : Double.NaN;
                    Double microF1 = hasData ? ((microPrec + microRec == 0.0) ? 0.0
                            : (2.0 * microPrec * microRec) / (microPrec + microRec)) : Double.NaN;

                    // weighted
                    double wPrecSum = 0.0, wRecSum = 0.0, wF1Sum = 0.0;
                    long supportSum = 0L;
                    for (int c = 0; c < C; c++) {
                        long TPc = tp[k][c];
                        long FPc = predPos[k][c] - TPc;
                        long FNc = actPos[k][c] - TPc;
                        long supp = TPc + FNc;
                        if (supp <= 0)
                            continue;

                        double Pc = (TPc + FPc) > 0 ? (double) TPc / (TPc + FPc) : 0.0;
                        double Rc = (double) TPc / supp;
                        double F1c = (Pc + Rc == 0.0) ? 0.0 : (2.0 * Pc * Rc / (Pc + Rc));

                        wPrecSum += Pc * supp;
                        wRecSum += Rc * supp;
                        wF1Sum += F1c * supp;
                        supportSum += supp;
                    }
                    Double wPrec = (hasData && supportSum > 0) ? (wPrecSum / supportSum) : Double.NaN;
                    Double wRec = (hasData && supportSum > 0) ? (wRecSum / supportSum) : Double.NaN;
                    Double wF1 = (hasData && supportSum > 0) ? (wF1Sum / supportSum) : Double.NaN;

                    int correct = correctCumulative[k];
                    int wrong = seen[k] - correct;

                    log(out,
                            "  M#%d | Seen=%4d | Correct=%4d | Wrong=%4d | CumAcc=%.4f%% | " +
                                    "MacroPrec=%.4f%% | MacroRec=%.4f%% | MacroF1=%.4f%% | " +
                                    "MicroPrec=%.4f%% | MicroRec=%.4f%% | MicroF1=%.4f%% | " +
                                    "WeightedPrec=%.4f%% | WeightedRec=%.4f%% | WeightedF1=%.4f%% | " +
                                    "WinAcc(%d)=%.4f%% | MergeCPU=%.3f ms%n",
                            k, seen[k], correct, wrong,
                            100.0 * accCum,
                            100.0 * macroPrec, 100.0 * macroRec, 100.0 * macroF1,
                            100.0 * microPrec, 100.0 * microRec, 100.0 * microF1,
                            100.0 * wPrec, 100.0 * wRec, 100.0 * wF1,
                            windows[k].size(), 100.0 * accWin, mergeMs);
                }
                log(out, "  Assignments: %s%n", Arrays.toString(assignedCount));
            }
        }

        // final report
        log(out, "FINAL%n");
        for (int k = 0; k < M; k++) {
            double accCum = (seen[k] == 0) ? Double.NaN : (double) correctCumulative[k] / seen[k];
            double accWin = (windows[k].isEmpty()) ? Double.NaN : (double) correctWindow[k] / windows[k].size();
            double mergeMs = ns2ms(nodes.get(k).getMergeTimeNano());
            boolean hasData = seen[k] > 0;

            double pSum = 0.0, rSum = 0.0, f1Sum = 0.0;
            int cnt = 0;
            for (int c = 0; c < C; c++) {
                long TPc = tp[k][c];
                long FPc = predPos[k][c] - TPc;
                long FNc = actPos[k][c] - TPc;

                boolean hasPred = (TPc + FPc) > 0;
                boolean hasActual = (TPc + FNc) > 0;
                if (!(hasPred || hasActual))
                    continue;

                double Pc = hasPred ? (double) TPc / (TPc + FPc) : 0.0;
                double Rc = hasActual ? (double) TPc / (TPc + FNc) : 0.0;
                double F1c = (Pc + Rc == 0.0) ? 0.0 : (2.0 * Pc * Rc / (Pc + Rc));

                pSum += Pc;
                rSum += Rc;
                f1Sum += F1c;
                cnt++;
            }
            double macroPrec = (cnt == 0) ? Double.NaN : pSum / cnt;
            double macroRec = (cnt == 0) ? Double.NaN : rSum / cnt;
            double macroF1 = (cnt == 0) ? Double.NaN : f1Sum / cnt;

            // micro
            long sumTP = 0L, sumPred = 0L, sumAct = 0L;
            for (int c = 0; c < C; c++) {
                sumTP += tp[k][c];
                sumPred += predPos[k][c];
                sumAct += actPos[k][c];
            }
            Double microPrec = hasData ? ((sumPred == 0) ? 0.0 : (double) sumTP / sumPred) : Double.NaN;
            Double microRec = hasData ? ((sumAct == 0) ? 0.0 : (double) sumTP / sumAct) : Double.NaN;
            Double microF1 = hasData
                    ? ((microPrec + microRec == 0.0) ? 0.0 : (2.0 * microPrec * microRec) / (microPrec + microRec))
                    : Double.NaN;

            // weighted
            double wPrecSum = 0.0, wRecSum = 0.0, wF1Sum = 0.0;
            long supportSum = 0L;
            for (int c = 0; c < C; c++) {
                long TPc = tp[k][c];
                long FPc = predPos[k][c] - TPc;
                long FNc = actPos[k][c] - TPc;
                long supp = TPc + FNc;
                if (supp <= 0)
                    continue;

                double Pc = (TPc + FPc) > 0 ? (double) TPc / (TPc + FPc) : 0.0;
                double Rc = (double) TPc / supp;
                double F1c = (Pc + Rc == 0.0) ? 0.0 : (2.0 * Pc * Rc / (Pc + Rc));

                wPrecSum += Pc * supp;
                wRecSum += Rc * supp;
                wF1Sum += F1c * supp;
                supportSum += supp;
            }
            Double wPrec = (hasData && supportSum > 0) ? (wPrecSum / supportSum) : Double.NaN;
            Double wRec = (hasData && supportSum > 0) ? (wRecSum / supportSum) : Double.NaN;
            Double wF1 = (hasData && supportSum > 0) ? (wF1Sum / supportSum) : Double.NaN;

            int correct = correctCumulative[k];
            int wrong = seen[k] - correct;

            log(out,
                    "  M#%d | Seen=%d | Correct=%d | Wrong=%d | CumAcc=%.4f%% | " +
                            "MacroPrec=%.4f%% | MacroRec=%.4f%% | MacroF1=%.4f%% | " +
                            "MicroPrec=%.4f%% | MicroRec=%.4f%% | MicroF1=%.4f%% | " +
                            "WeightedPrec=%.4f%% | WeightedRec=%.4f%% | WeightedF1=%.4f%% | " +
                            "WinAcc(%d)=%.4f%% | Assigned=%d | MergeCPU=%.3f ms%n",
                    k, seen[k], correct, wrong,
                    100.0 * accCum,
                    100.0 * macroPrec, 100.0 * macroRec, 100.0 * macroF1,
                    100.0 * microPrec, 100.0 * microRec, 100.0 * microF1,
                    100.0 * wPrec, 100.0 * wRec, 100.0 * wF1,
                    windows[k].size(), 100.0 * accWin, assignedCount[k], mergeMs);
            log(out, "  M#%d | BytesSent=%d KB | BytesReceived=%d KB | MergeRounds=%d%n",
                    k, nodes.get(k).totalBytesSent / 1024,
                    nodes.get(k).totalBytesReceived / 1024,
                    nodes.get(k).mergeRoundCount);
            log(out, "  M#%d | FinalModelSize=%d KB%n",
                    k, getModelSizeBytes(nodes.get(k).getModel()) / 1024);

        }

        log(out, "ClassByNode | rows = nodes, columns = classes%n");
        StringBuilder hdr = new StringBuilder("          ");
        for (int c = 0; c < C; c++)
            hdr.append(String.format("%10s", "C" + c));
        hdr.append(String.format("%10s%10s", "present", "total"));
        log(out, "%s%n", hdr);
        for (int k = 0; k < M; k++) {
            StringBuilder row = new StringBuilder(String.format("  Node#%-3d", k));
            int present = 0;
            long tot = 0L;
            for (int c = 0; c < C; c++) {
                row.append(String.format("%10d", actPos[k][c]));
                if (actPos[k][c] > 0)
                    present++;
                tot += actPos[k][c];
            }
            row.append(String.format("%10d%10d", present, tot));
            log(out, "%s%n", row);
        }
        log(out, "ClassByNodeShare | fraction of each class received by each node%n");
        for (int k = 0; k < M; k++) {
            StringBuilder row = new StringBuilder(String.format("  Node#%-3d", k));
            for (int c = 0; c < C; c++) {
                long classTotal = 0L;
                for (int j = 0; j < M; j++)
                    classTotal += actPos[j][c];
                row.append(String.format("%10.3f", classTotal == 0 ? 0.0 : (double) actPos[k][c] / classTotal));
            }
            log(out, "%s%n", row);
        }

        // Overall totals across all nodes
        long totalSeen = 0L, totalCorrect = 0L, totalAssigned = 0L;
        long[] totTP = new long[C];
        long[] totPredPos = new long[C];
        long[] totActPos = new long[C];

        for (int k = 0; k < M; k++) {
            totalSeen += seen[k];
            totalCorrect += correctCumulative[k];
            totalAssigned += assignedCount[k];
            for (int c = 0; c < C; c++) {
                totTP[c] += tp[k][c];
                totPredPos[c] += predPos[k][c];
                totActPos[c] += actPos[k][c];
            }
        }

        long totalWrong = totalSeen - totalCorrect;
        double totalAcc = (totalSeen == 0) ? Double.NaN : (double) totalCorrect / totalSeen;

        double pSum = 0.0, rSum = 0.0, f1Sum = 0.0;
        int cnt = 0;
        for (int c = 0; c < C; c++) {
            long TPc = totTP[c];
            long FPc = totPredPos[c] - TPc;
            long FNc = totActPos[c] - TPc;

            boolean hasPred = (TPc + FPc) > 0;
            boolean hasActual = (TPc + FNc) > 0;
            if (!(hasPred || hasActual))
                continue;

            double Pc = hasPred ? (double) TPc / (TPc + FPc) : 0.0;
            double Rc = hasActual ? (double) TPc / (TPc + FNc) : 0.0;
            double F1c = (Pc + Rc == 0.0) ? 0.0 : (2.0 * Pc * Rc / (Pc + Rc));

            pSum += Pc;
            rSum += Rc;
            f1Sum += F1c;
            cnt++;
        }
        double totalMacroPrec = (cnt == 0) ? Double.NaN : pSum / cnt;
        double totalMacroRec = (cnt == 0) ? Double.NaN : rSum / cnt;
        double totalMacroF1 = (cnt == 0) ? Double.NaN : f1Sum / cnt;

        // micro totals
        boolean totalsHaveData = totalSeen > 0;
        long tSumTP = 0L, tSumPred = 0L, tSumAct = 0L;
        for (int c = 0; c < C; c++) {
            tSumTP += totTP[c];
            tSumPred += totPredPos[c];
            tSumAct += totActPos[c];
        }
        Double totalMicroPrec = totalsHaveData ? ((tSumPred == 0) ? 0.0 : (double) tSumTP / tSumPred) : Double.NaN;
        Double totalMicroRec = totalsHaveData ? ((tSumAct == 0) ? 0.0 : (double) tSumTP / tSumAct) : Double.NaN;
        Double totalMicroF1 = totalsHaveData ? ((totalMicroPrec + totalMicroRec == 0.0) ? 0.0
                : (2.0 * totalMicroPrec * totalMicroRec) / (totalMicroPrec + totalMicroRec)) : Double.NaN;

        // weighted totals
        double twPrecSum = 0.0, twRecSum = 0.0, twF1Sum = 0.0;
        long tSupportSum = 0L;
        for (int c = 0; c < C; c++) {
            long TPc = totTP[c];
            long FPc = totPredPos[c] - TPc;
            long FNc = totActPos[c] - TPc;
            long supp = TPc + FNc;
            if (supp <= 0)
                continue;

            double Pc = (TPc + FPc) > 0 ? (double) TPc / (TPc + FPc) : 0.0;
            double Rc = (double) TPc / supp;
            double F1c = (Pc + Rc == 0.0) ? 0.0 : (2.0 * Pc * Rc / (Pc + Rc));

            twPrecSum += Pc * supp;
            twRecSum += Rc * supp;
            twF1Sum += F1c * supp;
            tSupportSum += supp;
        }
        Double totalWPrec = (totalsHaveData && tSupportSum > 0) ? (twPrecSum / tSupportSum) : Double.NaN;
        Double totalWRec = (totalsHaveData && tSupportSum > 0) ? (twRecSum / tSupportSum) : Double.NaN;
        Double totalWF1 = (totalsHaveData && tSupportSum > 0) ? (twF1Sum / tSupportSum) : Double.NaN;

        logTotal(out,
                "TOTAL | Seen=%d | Correct=%d | Wrong=%d | Acc=%.4f%% | " +
                        "MacroPrec=%.4f%% | MacroRec=%.4f%% | MacroF1=%.4f%% | " +
                        "MicroPrec=%.4f%% | MicroRec=%.4f%% | MicroF1=%.4f%% | " +
                        "WeightedPrec=%.4f%% | WeightedRec=%.4f%% | WeightedF1=%.4f%% | Assigned=%d%n",
                totalSeen, totalCorrect, totalWrong, 100.0 * totalAcc,
                100.0 * totalMacroPrec, 100.0 * totalMacroRec, 100.0 * totalMacroF1,
                100.0 * totalMicroPrec, 100.0 * totalMicroRec, 100.0 * totalMicroF1,
                100.0 * totalWPrec, 100.0 * totalWRec, 100.0 * totalWF1,
                totalAssigned);
        double globalMergeMs = (global != null) ? ns2ms(global.getMergeTimeNano()) : 0.0;
        double nodesMergeMs = 0.0;
        for (int k = 0; k < M; k++)
            nodesMergeMs += ns2ms(nodes.get(k).getMergeTimeNano());
        logTotal(out, "Global merge CPU time: %.3f ms%n", globalMergeMs);
        logTotal(out, "Nodes merge CPU time (sum): %.3f ms%n", nodesMergeMs);
        logTotal(out, "Total merge CPU time (global + nodes): %.3f ms%n", globalMergeMs + nodesMergeMs);
        long totalBytesSent = 0L, totalBytesReceived = 0L, totalMergeRounds = 0;
        for (int k = 0; k < M; k++) {
            totalBytesSent += nodes.get(k).totalBytesSent;
            totalBytesReceived += nodes.get(k).totalBytesReceived;
            totalMergeRounds += nodes.get(k).mergeRoundCount;
        }
        logTotal(out, "Total Communication | BytesSent=%d KB | BytesReceived=%d KB | MergeRounds=%d%n",
                totalBytesSent / 1024, totalBytesReceived / 1024, totalMergeRounds);
        logTotal(out, "Total instances=%d | Models=%d%n", stream.numInstances(), M);
    }

    private static void writeDatasetSection(PrintWriter out, Instances data, Path sourcePath) {
        out.println("=== Dataset ===");
        out.printf("Name (relation): %s%n", safeRelationName(data, sourcePath));
        out.printf("Source file: %s%n", sourcePath.toAbsolutePath());
        out.printf("Instances: %d%n", data.numInstances());
        out.printf("Attributes (total): %d%n", data.numAttributes());

        int classIdx = data.classIndex();
        List<String> featureNames = new ArrayList<>();
        for (int i = 0; i < data.numAttributes(); i++) {
            if (i != classIdx)
                featureNames.add(data.attribute(i).name());
        }
        out.printf("Features (%d): %s%n", featureNames.size(), String.join(", ", featureNames));

        Attribute cls = data.classAttribute();
        out.printf("Target: %s (index=%d)%n", cls.name(), classIdx);
        List<String> classVals = new ArrayList<>();
        for (int i = 0; i < cls.numValues(); i++)
            classVals.add(cls.value(i));
        out.printf("Class values (%d): %s%n", classVals.size(), String.join(", ", classVals));
    }

    private static String safeRelationName(Instances data, Path sourcePath) {
        try {
            return (String) data.getClass().getMethod("getRelationName").invoke(data);
        } catch (Throwable ignored) {
        }
        try {
            return (String) data.getClass().getMethod("relationName").invoke(data);
        } catch (Throwable ignored) {
        }
        String fn = sourcePath.getFileName().toString();
        int dot = fn.lastIndexOf('.');
        return (dot > 0) ? fn.substring(0, dot) : fn;
    }

    private static Instances loadInstancesAuto(Path path) throws Exception {

        try (BufferedReader br = new BufferedReader(new FileReader(path.toFile()))) {
            String line;
            while ((line = br.readLine()) != null) {
                String l = line.trim();
                if (l.isEmpty() || l.startsWith("%") || l.startsWith("#"))
                    continue;

                if (l.startsWith("@") || l.toLowerCase().contains("@relation")) {
                    String all = Files.readString(path);
                    String allLower = all.toLowerCase();
                    if (allLower.contains("@attribute airline") || allLower.contains("@attribute airportfrom")) {
                        System.out.println("[loader] Using airline ARFF loader");
                        return loadAirlineArff(path);
                    } else if (allLower.contains("@relation covtype") ||
                            allLower.contains("wilderness_area1") ||
                            allLower.contains("soil_type1")) {
                        System.out.println("[loader] Using CoverType ARFF loader");
                        return loadCoverTypeArff(path);
                    } else if (allLower.contains("@relation poker")
                            && allLower.contains("@attribute s1 {1,2,3,4}")
                            && allLower.contains("@attribute class {0,1,2,3,4,5,6,7,8,9}")) {
                        System.out.println("[loader] Using poker ARFF loader");
                        return loadPokerInstances(path);
                    }
                    break;
                }
                String lower = l.toLowerCase();
                // KD99
                if (lower.contains("protocol_type")
                        && lower.contains("dst_host_srv_rerror_rate")
                        && lower.contains("label")) {
                    System.out.println("[loader] Using KD99 CSV loader");
                    return loadKD99Csv(path);
                }
                if (l.matches("^\\s*[1-6]\\s*;\\s*[-+]?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?\\s+1:.*")) {
                    System.out.println("[loader] Using Gas Sensor Drift (Different Concentrations) loader");
                    return loadGasSensorDriftDifferentConcentrations(path);
                }
                break;
            }
        }
        throw new IllegalArgumentException("Unrecognised dataset: " + path.getFileName()
                + ". Supported: Airline ARFF, CoverType ARFF, Poker ARFF, KDD99 CSV, Gas Sensor Drift.");

    }

    private static Instances loadGasSensorDriftDifferentConcentrations(Path file) throws Exception {

        final boolean INCLUDE_CONCENTRATION_AS_FEATURE = false;

        // Gas mapping (UCI):
        // 1 Ethanol, 2 Ethylene, 3 Ammonia, 4 Acetaldehyde, 5 Acetone, 6 Toluene
        List<String> classValues = Arrays.asList(
                "Ethanol", "Ethylene", "Ammonia", "Acetaldehyde", "Acetone", "Toluene");

        int numFeatures = 128;
        int totalAttrs = (INCLUDE_CONCENTRATION_AS_FEATURE ? 1 : 0) + numFeatures + 1; // +1 for class

        ArrayList<Attribute> atts = new ArrayList<>(totalAttrs);

        int offset = 0;
        if (INCLUDE_CONCENTRATION_AS_FEATURE) {
            atts.add(new Attribute("concentration_ppmv"));
            offset = 1;
        }

        for (int i = 1; i <= numFeatures; i++) {
            atts.add(new Attribute("f" + i));
        }

        Attribute clsAttr = new Attribute("gas", classValues);
        atts.add(clsAttr);

        Instances data = new Instances("GasSensorArrayDriftDifferentConcentrations", atts, 0);
        data.setClassIndex(atts.size() - 1);

        try (BufferedReader br = new BufferedReader(new FileReader(file.toFile()))) {
            String line;
            int lineNum = 0;

            while ((line = br.readLine()) != null) {
                lineNum++;
                line = line.trim();

                if (line.isEmpty() || line.startsWith("%") || line.startsWith("#"))
                    continue;

                String[] parts = line.split("\\s+");
                if (parts.length < 2) {
                    System.err.println("Warning: Line " + lineNum + " malformed (too few tokens). Skipping.");
                    continue;
                }

                String head = parts[0];
                int semi = head.indexOf(';');
                if (semi < 0) {
                    System.err.println("Warning: Line " + lineNum + " missing ';' label delimiter. Skipping.");
                    continue;
                }

                String clsStr = head.substring(0, semi).trim();
                String concStr = head.substring(semi + 1).trim();

                int gasId;
                double concentration;
                try {
                    gasId = Integer.parseInt(clsStr);
                    concentration = Double.parseDouble(concStr);
                } catch (NumberFormatException nfe) {
                    System.err.println(
                            "Warning: Line " + lineNum + " invalid class/concentration: " + head + ". Skipping.");
                    continue;
                }

                if (gasId < 1 || gasId > 6) {
                    System.err.println(
                            "Warning: Line " + lineNum + " gasId out of range [1..6]: " + gasId + ". Skipping.");
                    continue;
                }

                double[] vals = new double[atts.size()];
                Arrays.fill(vals, Double.NaN);

                if (INCLUDE_CONCENTRATION_AS_FEATURE) {
                    vals[0] = concentration;
                }

                for (int t = 1; t < parts.length; t++) {
                    String tok = parts[t];
                    int colon = tok.indexOf(':');
                    if (colon < 0)
                        continue;

                    String idxStr = tok.substring(0, colon);
                    String valStr = tok.substring(colon + 1);

                    int idx;
                    double v;
                    try {
                        idx = Integer.parseInt(idxStr);
                        v = Double.parseDouble(valStr);
                    } catch (NumberFormatException nfe) {
                        continue;
                    }

                    if (idx < 1 || idx > numFeatures) {
                        continue;
                    }

                    int pos = (INCLUDE_CONCENTRATION_AS_FEATURE ? idx : (idx - 1));
                    if (INCLUDE_CONCENTRATION_AS_FEATURE) {
                        pos = offset + (idx - 1);
                    }

                    vals[pos] = v;
                }

                vals[data.classIndex()] = gasId - 1;

                DenseInstance inst = new DenseInstance(1.0, vals);
                inst.setDataset(data);
                data.add(inst);
            }
        }

        return data;
    }

    private static Instances loadCoverTypeArff(Path file) throws Exception {

        List<Attribute> attrs = new ArrayList<>(55);

        // 10 numeric
        attrs.add(new Attribute("Elevation"));
        attrs.add(new Attribute("Aspect"));
        attrs.add(new Attribute("Slope"));
        attrs.add(new Attribute("Horizontal_Distance_To_Hydrology"));
        attrs.add(new Attribute("Vertical_Distance_To_Hydrology"));
        attrs.add(new Attribute("Horizontal_Distance_To_Roadways"));
        attrs.add(new Attribute("Hillshade_9am"));
        attrs.add(new Attribute("Hillshade_Noon"));
        attrs.add(new Attribute("Hillshade_3pm"));
        attrs.add(new Attribute("Horizontal_Distance_To_Fire_Points"));

        // binary nominal labels once
        List<String> bin = Arrays.asList("0", "1");

        // Wilderness_Area1..4 (nominal {0,1})
        for (int i = 1; i <= 4; i++) {
            attrs.add(new Attribute("Wilderness_Area" + i, bin));
        }

        // Soil_Type1..40 (nominal {0,1})
        for (int i = 1; i <= 40; i++) {
            attrs.add(new Attribute("Soil_Type" + i, bin));
        }

        // class {1..7}
        List<String> cls = Arrays.asList("1", "2", "3", "4", "5", "6", "7");
        attrs.add(new Attribute("class", cls));

        Instances data = new Instances("covtype-weka.filters.unsupervised.attribute.Normalize-S1.0-T0.0", attrs, 0);
        data.setClassIndex(attrs.size() - 1);

        try (BufferedReader br = new BufferedReader(new FileReader(file.toFile()))) {
            String line;
            boolean inData = false;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("%") || line.startsWith("#"))
                    continue;

                if (!inData) {
                    if (line.regionMatches(true, 0, "@data", 0, 5))
                        inData = true;
                    continue;
                }

                String[] parts = line.split("\\s*,\\s*");
                double[] v = new double[55];

                // 0..9 numeric
                for (int i = 0; i < 10; i++) {
                    v[i] = Double.parseDouble(parts[i]);
                }

                // 10..13 Wilderness_Area{1..4} -> nominal index 0/1
                for (int i = 10; i < 14; i++) {
                    v[i] = (parts[i].charAt(0) == '1') ? 1 : 0;
                }

                // 14..53 Soil_Type{1..40} -> nominal index 0/1
                for (int i = 14; i < 54; i++) {
                    v[i] = (parts[i].charAt(0) == '1') ? 1 : 0;
                }

                // 54 class {1..7} -> index 0..6
                int clsInt = Integer.parseInt(parts[54]); // 1..7
                v[54] = clsInt - 1;

                Instance inst = new DenseInstance(1.0, v);
                inst.setDataset(data);
                data.add(inst);
            }
        }
        return data;
    }

    private static Instances loadAirlineArff(Path file) throws Exception {
        final List<String> names = new ArrayList<>();
        final List<List<String>> nominals = new ArrayList<>();
        String relation = "dataset";

        try (BufferedReader br = new BufferedReader(new FileReader(file.toFile()))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("%") || line.startsWith("#"))
                    continue;

                String lower = line.toLowerCase();
                if (lower.startsWith("@relation")) {
                    relation = line.substring(9).trim();
                    continue;
                }
                if (lower.startsWith("@attribute")) {
                    String rest = line.substring("@attribute".length()).trim();
                    int space = rest.indexOf(' ');
                    if (space <= 0)
                        throw new IllegalArgumentException("Bad @attribute line: " + line);
                    String name = rest.substring(0, space).trim();
                    String type = rest.substring(space + 1).trim();

                    if (type.toLowerCase().startsWith("numeric")) {
                        names.add(name);
                        nominals.add(null);
                    } else if (type.startsWith("{") && type.endsWith("}")) {
                        String inside = type.substring(1, type.length() - 1);
                        List<String> vals = new ArrayList<>();
                        for (String raw : inside.split(",")) {
                            String v = raw.trim();
                            if ((v.startsWith("'") && v.endsWith("'")) || (v.startsWith("\"") && v.endsWith("\""))) {
                                v = v.substring(1, v.length() - 1);
                            }
                            vals.add(v);
                        }
                        names.add(name);
                        nominals.add(vals);
                    } else {
                        throw new IllegalArgumentException("Unsupported attribute type: " + line);
                    }
                    continue;
                }
                if (lower.startsWith("@data")) {
                    break;
                }
            }
        }

        // Build attributes/Instances
        List<Attribute> attrs = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            List<String> vals = nominals.get(i);
            attrs.add(vals == null ? new Attribute(names.get(i)) : new Attribute(names.get(i), vals));
        }
        Instances data = new Instances(relation, attrs, 0);

        int cls = names.indexOf("Delay");
        data.setClassIndex(cls >= 0 ? cls : attrs.size() - 1);

        try (BufferedReader br = new BufferedReader(new FileReader(file.toFile()))) {
            String line;
            boolean inData = false;
            int ln = 0;
            while ((line = br.readLine()) != null) {
                ln++;
                line = line.trim();
                if (line.isEmpty() || line.startsWith("%") || line.startsWith("#"))
                    continue;

                String lower = line.toLowerCase();
                if (!inData) {
                    if (lower.startsWith("@data"))
                        inData = true;
                    continue;
                }

                String[] parts = line.split("\\s*,\\s*");
                if (parts.length != attrs.size()) {
                    throw new IllegalArgumentException("Line " + ln + " has " + parts.length +
                            " fields (expected " + attrs.size() + "): " + line);
                }

                double[] vals = new double[attrs.size()];
                for (int i = 0; i < attrs.size(); i++) {
                    Attribute a = attrs.get(i);
                    String tok = parts[i].trim();
                    if (a.isNominal()) {
                        int idx = a.indexOfValue(tok);
                        if (idx < 0) {
                            throw new IllegalArgumentException("Unknown nominal '" + tok + "' for attribute '" +
                                    a.name() + "' at line " + ln);
                        }
                        vals[i] = idx;
                    } else {
                        vals[i] = Double.parseDouble(tok);
                    }
                }
                Instance inst = new DenseInstance(1.0, vals);
                inst.setDataset(data);
                data.add(inst);
            }
        }

        return data;
    }

    /**
     * Loader for the UCI Poker Hand dataset in simple ARFF/CSV form.
     * Expected schema:
     * s1,r1,s2,r2,s3,r3,s4,r4,s5,r5,class
     * s* nominal in {1,2,3,4}; r* numeric; class nominal in {0..9}
     * Ignores ARFF header lines starting with '@' and comments '%' / '#'.
     */
    private static Instances loadPokerInstances(Path file) throws Exception {
        List<Attribute> attrs = new ArrayList<>();

        List<String> suits = Arrays.asList("1", "2", "3", "4");
        attrs.add(new Attribute("s1", suits));
        attrs.add(new Attribute("r1"));
        attrs.add(new Attribute("s2", suits));
        attrs.add(new Attribute("r2"));
        attrs.add(new Attribute("s3", suits));
        attrs.add(new Attribute("r3"));
        attrs.add(new Attribute("s4", suits));
        attrs.add(new Attribute("r4"));
        attrs.add(new Attribute("s5", suits));
        attrs.add(new Attribute("r5"));

        List<String> classVals = new ArrayList<>();
        for (int c = 0; c <= 9; c++)
            classVals.add(Integer.toString(c));
        attrs.add(new Attribute("class", classVals));

        Instances data = new Instances("poker", attrs, 0);
        data.setClassIndex(attrs.size() - 1);

        try (BufferedReader br = new BufferedReader(new FileReader(file.toFile()))) {
            String line;
            int ln = 0;
            boolean inData = false;

            while ((line = br.readLine()) != null) {
                ln++;
                line = line.trim();
                if (line.isEmpty() || line.startsWith("%") || line.startsWith("#"))
                    continue;

                if (!inData) {
                    String lower = line.toLowerCase();
                    if (lower.startsWith("@data")) {
                        inData = true;
                        continue;
                    }
                    if (line.startsWith("@")) {
                        continue;
                    }
                    inData = true;
                }
                if (!inData)
                    continue;

                String[] parts = line.split("\\s*,\\s*");
                if (parts.length != 11) {
                    throw new IllegalArgumentException(
                            "Line " + ln + " has " + parts.length + " fields (expected 11): " + line);
                }

                double[] v = new double[11];

                // suits: positions 0,2,4,6,8 (nominal indices)
                int[] suitPos = { 0, 2, 4, 6, 8 };
                for (int p : suitPos) {
                    int idx = attrs.get(p).indexOfValue(parts[p]);
                    if (idx < 0)
                        throw new IllegalArgumentException(
                                "Bad suit value at line " + ln + " pos " + p + ": " + parts[p]);
                    v[p] = idx;
                }

                // ranks: positions 1,3,5,7,9 (numeric)
                int[] rankPos = { 1, 3, 5, 7, 9 };
                for (int p : rankPos) {
                    v[p] = Double.parseDouble(parts[p]);
                }

                // class: position 10 (nominal index 0..9)
                int clsIdx = attrs.get(10).indexOfValue(parts[10]);
                if (clsIdx < 0)
                    throw new IllegalArgumentException("Bad class at line " + ln + ": " + parts[10]);
                v[10] = clsIdx;

                Instance inst = new DenseInstance(1.0, v);
                inst.setDataset(data);
                data.add(inst);
            }
        }
        return data;
    }

    // KD99 CSV loader
    private static Instances loadKD99Csv(Path file) throws Exception {
        final String[] COLS = {
                "duration", "protocol_type", "service", "flag", "src_bytes", "dst_bytes", "land",
                "wrong_fragment", "urgent", "hot", "num_failed_logins", "logged_in", "lnum_compromised",
                "lroot_shell", "lsu_attempted", "lnum_root", "lnum_file_creations", "lnum_shells",
                "lnum_access_files", "lnum_outbound_cmds", "is_host_login", "is_guest_login", "count",
                "srv_count", "serror_rate", "srv_serror_rate", "rerror_rate", "srv_rerror_rate",
                "same_srv_rate", "diff_srv_rate", "srv_diff_host_rate", "dst_host_count",
                "dst_host_srv_count", "dst_host_same_srv_rate", "dst_host_diff_srv_rate",
                "dst_host_same_src_port_rate", "dst_host_srv_diff_host_rate", "dst_host_serror_rate",
                "dst_host_srv_serror_rate", "dst_host_rerror_rate", "dst_host_srv_rerror_rate", "label"
        };
        final Set<String> NOMINAL = new HashSet<>(Arrays.asList(
                "protocol_type", "service", "flag", "land", "logged_in", "is_host_login", "is_guest_login", "label"));

        // 1) read header, detect delimiter (comma or tab), validate columns
        String header;
        try (BufferedReader br = new BufferedReader(new FileReader(file.toFile()))) {
            header = br.readLine();
        }
        if (header == null)
            throw new IllegalArgumentException("Empty KD99 file: " + file);
        header = header.trim();
        final String delimRegex = header.contains("\t") ? "\\t+" : "\\s*,\\s*";
        String[] hdr = header.split(delimRegex, -1);
        if (hdr.length != COLS.length)
            throw new IllegalArgumentException("KD99 header has "
                    + hdr.length + " columns (expected " + COLS.length + ")");
        for (int i = 0; i < COLS.length; i++) {
            if (!COLS[i].equals(hdr[i]))
                throw new IllegalArgumentException("Header mismatch at col " + i + ": '"
                        + hdr[i] + "' vs expected '" + COLS[i] + "'");
        }

        Map<String, LinkedHashSet<String>> dom = new HashMap<>();
        for (String n : NOMINAL)
            dom.put(n, new LinkedHashSet<>());

        try (BufferedReader br = new BufferedReader(new FileReader(file.toFile()))) {
            String line;
            boolean skip = true;
            while ((line = br.readLine()) != null) {
                if (skip) {
                    skip = false;
                    continue;
                }
                line = line.trim();
                if (line.isEmpty())
                    continue;
                String[] p = line.split(delimRegex, -1);
                if (p.length != COLS.length)
                    throw new IllegalArgumentException(
                            "Row has " + p.length + " fields (expected " + COLS.length + "): " + line);
                for (int i = 0; i < COLS.length; i++) {
                    String name = COLS[i];
                    if (NOMINAL.contains(name))
                        dom.get(name).add(p[i].trim());
                }
            }
        }

        List<Attribute> atts = new ArrayList<>(COLS.length);
        for (String name : COLS) {
            if (NOMINAL.contains(name)) {
                List<String> vals = new ArrayList<>(dom.get(name));
                if (vals.isEmpty())
                    throw new IllegalArgumentException("No values for nominal: " + name);
                atts.add(new Attribute(name, vals));
            } else {
                atts.add(new Attribute(name));
            }
        }
        Instances data = new Instances("kd99-csv", atts, 0);
        data.setClassIndex(COLS.length - 1);

        try (BufferedReader br = new BufferedReader(new FileReader(file.toFile()))) {
            String line;
            boolean skip = true;
            while ((line = br.readLine()) != null) {
                if (skip) {
                    skip = false;
                    continue;
                }
                line = line.trim();
                if (line.isEmpty())
                    continue;

                String[] p = line.split(delimRegex, -1);
                if (p.length != COLS.length)
                    throw new IllegalArgumentException(
                            "Row has " + p.length + " fields (expected " + COLS.length + "): " + line);

                double[] v = new double[COLS.length];
                for (int i = 0; i < COLS.length; i++) {
                    Attribute a = atts.get(i);
                    String tok = p[i].trim();
                    if (a.isNominal()) {
                        int idx = a.indexOfValue(tok);
                        if (idx < 0)
                            throw new IllegalArgumentException("Unknown nominal '" + tok + "' for '" + a.name() + "'");
                        v[i] = idx;
                    } else {
                        v[i] = Double.parseDouble(tok);
                    }
                }
                Instance inst = new DenseInstance(1.0, v);
                inst.setDataset(data);
                data.add(inst);
            }
        }
        return data;
    }

    private static void preview(Instances data, int n) {
        System.out.printf(
                "Dataset: %s | instances=%d | attrs=%d | class=%s (index=%d)%n",
                data.getRelationName(),
                data.numInstances(),
                data.numAttributes(),
                (data.classIndex() >= 0 ? data.classAttribute().name() : "<none>"),
                data.classIndex());

        // header
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.numAttributes(); i++) {
            if (i > 0)
                sb.append(',');
            sb.append(data.attribute(i).name());
        }
        System.out.println(sb);

        // first N rows
        int limit = Math.min(n, data.numInstances());
        for (int r = 0; r < limit; r++) {
            Instance inst = data.instance(r);
            sb.setLength(0);
            for (int c = 0; c < data.numAttributes(); c++) {
                if (c > 0)
                    sb.append(',');
                Attribute a = data.attribute(c);

                if (inst.isMissing(a)) {
                    sb.append('?');
                } else if (a.isNominal()) {
                    int idx = (int) inst.value(c);
                    if (idx >= 0 && idx < a.numValues()) {
                        sb.append(a.value(idx));
                    } else {
                        sb.append(idx);
                    }
                } else {
                    sb.append(inst.value(c));
                }
            }
            System.out.printf("[%d] %s%n", r, sb);
        }
        System.out.println();
    }

    private static int argmax(double[] arr) {
        int best = 0;
        double bestVal = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] > bestVal) {
                bestVal = arr[i];
                best = i;
            }
        }
        return best;
    }

    private static void log(PrintWriter out, String fmt, Object... args) {
        String s = String.format(fmt, args);
        out.print(s);
        out.flush();
    }

    /** Per-round federation events: written only when ROUND_LOG is true. */
    private static void trace(PrintWriter out, String fmt, Object... args) {
        if (ROUND_LOG)
            log(out, fmt, args);
    }

    /**
     * Written to the report and echoed to the console: used for the TOTAL section
     * only.
     */
    private static void logTotal(PrintWriter out, String fmt, Object... args) {
        String s = String.format(fmt, args);
        out.print(s);
        out.flush();
        System.out.print(s);
    }

    private static long getModelSizeBytes(FederatedHoeffdingTree model) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(model);
            oos.flush();
            return baos.size();
        } catch (Exception e) {
            return -1;
        }
    }
}
