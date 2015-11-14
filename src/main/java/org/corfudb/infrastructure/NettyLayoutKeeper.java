/**
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.corfudb.infrastructure;

import io.netty.channel.ChannelHandlerContext;
import lombok.Getter;
import lombok.Setter;
import org.corfudb.infrastructure.wireprotocol.NettyCorfuMsg;
import org.corfudb.infrastructure.wireprotocol.NettyLayoutConfigMsg;
import org.corfudb.infrastructure.wireprotocol.NettyLayoutBooleanMsg;
import org.corfudb.runtime.protocols.IServerProtocol;
import org.corfudb.runtime.protocols.configmasters.ILayoutKeeper;
import org.corfudb.runtime.view.CorfuDBView;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.json.JsonObject;

/*
import com.esotericsoftware.kryonet.Server;
import com.esotericsoftware.kryonet.Listener;
import com.esotericsoftware.kryonet.Connection;
*/

/**
 * This class participates in keeping consensus about meta-data.
 *
 * There are two parts, a passive meta-data keeper server, and an active layout-monitor that drives changes to the layout.
 *
 * - A NettyLayoutKeeper server stores the latest committed meta-data.
 *   It participates as a Paxos 'acceptor' in voting on proposals for meta-data changes.
 *
 * - A NettyLayoutKeeper monitor becomes activated upon storing a committed Corfu layout for the first time.
 *   It then instantiates a local CorfuDBView which has connection endpoints to all the layout components.
 *   It spawns a thread that constantly monitors the health of other components.
 *
 *   The monitor may initiate change proposals and may also receive requests from clients to drive changes.
 *   To drive a layout change, the monitor picks a rank and tries to become a Paxos leader and affect the change via a consensus decision.
 */
public class NettyLayoutKeeper extends AbstractNettyServer implements ICorfuDBServer {
    private static Logger log = LoggerFactory.getLogger(NettyLayoutKeeper.class);

    ConsensusKeeper<ConsensusLayoutObject> commitLayout = new ConsensusKeeper(new ConsensusLayoutObject());
    CorfuDBView currentView = null;
    JsonObject newProposal = null;

    Thread monitorThread = monitor();

    public NettyLayoutKeeper() {
    }

    @Override
    void parseConfiguration(Map<String, Object> configuration) {
        log.info("NettyLayoutKeeper configuration {}", configuration);
    }

    @Override
    public void processMessage(NettyCorfuMsg corfuMsg, ChannelHandlerContext ctx) {

        // todo: if not bootstrapped yet, ignore all by bootsrapLayout requests

        log.info("Received request of type {}", corfuMsg.getMsgType());
        switch (corfuMsg.getMsgType())
        {
            case META_PROPOSE_REQ: {
                NettyLayoutConfigMsg m = (NettyLayoutConfigMsg)corfuMsg;

                commitLayout.commitProposal(m.getJo()); // TODO this should use propose, eventually, unless rank == -1 ?
                reconfig(m.getJo());

                NettyLayoutBooleanMsg resp = new NettyLayoutBooleanMsg(NettyCorfuMsg.NettyCorfuMsgType.META_PROPOSE_RES, true);
                sendResponse(resp, corfuMsg, ctx);
                break;
            }

            case META_COLLECT_REQ: {
                NettyLayoutConfigMsg resp = null;
                if (commitLayout.getCommitState() == null)
                    resp = new NettyLayoutConfigMsg(NettyCorfuMsg.NettyCorfuMsgType.META_COLLECT_RES, -1, null);
                else
                    resp = new NettyLayoutConfigMsg(
                            NettyCorfuMsg.NettyCorfuMsgType.META_COLLECT_RES,
                            commitLayout.getEpoch(),
                            commitLayout.getCommitState().getCurrentLayout()
                    );
                log.info("layout response {}", resp);
                sendResponse(resp, corfuMsg, ctx);
                break;
            }

            case META_LEADER_REQ: {
                // todo: enforce a delay (300 millisecs?) between leader requests

                synchronized (monitorThread) {
                    if (newProposal != null) { // reject; handle leader roles one at a time
                        NettyLayoutBooleanMsg resp = new NettyLayoutBooleanMsg(NettyCorfuMsg.NettyCorfuMsgType.META_LEADER_RES, false);
                        sendResponse(resp, corfuMsg, ctx);
                    } else {
                        NettyLayoutConfigMsg m = (NettyLayoutConfigMsg)corfuMsg;
                        newProposal = m.getJo();
                        monitorThread.interrupt();
                    }
                }
            }

            default:
                break;
        }
    }


    @Override
    public void reset() {
        log.info("RESET requested, resetting all nodes and incrementing epoch");
        // TODO reset to which configuration??
    }

    private void reconfig(JsonObject newLayout) {
        // instantiate new CorfuDB view
        if (currentView != null) {
            currentView.invalidate();
        }
        currentView = new CorfuDBView(newLayout);

        // TODO the next part should be done by proposer or consensus engine?
        UUID logID =  UUID.randomUUID();
        log.info("New log instance id= " + logID.toString());
        currentView.setLogID(logID);

    }

    private Thread monitor() {
        return new Thread(() -> {
            for (;;) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                if (currentView == null) continue;
                // todo CorfuDB.checkconfiguration ..

                if (newProposal == null) continue;

                // loop through the MetaDataKeeper servers in currentView and send requests to all
                //

                CountDownLatch l = new CountDownLatch((currentView.getLayouts().size()+1)/2);
                AtomicBoolean proposalAccepted = new AtomicBoolean(true);

                for (IServerProtocol s : currentView.getLayouts()) {
                    ILayoutKeeper ss = (ILayoutKeeper) s;
                    ss.proposeNewView(-1, newProposal).thenAccept((bool) -> {
                        if (!bool) proposalAccepted.set(false);
                        l.countDown();
                    });
                }

                boolean normalCompletion = true;
                try {
                    normalCompletion = l.await(3000, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                log.info("monitor acceptProposal={} normalCompletion={}", proposalAccepted.get(), normalCompletion);

                newProposal = null;
            }
        });
    }

    public interface ConsensusObject {
        abstract void apply(Object proposal) ;
    }

    @Setter
    @Getter
    public class ConsensusLayoutObject implements ConsensusObject {
        JsonObject currentLayout = null;

        @Override
        public void apply(Object proposal) {
            // TODO check epoch successsion
            setCurrentLayout((JsonObject) proposal);
        }
    }

    @Getter
    public class ConsensusKeeper<T extends ConsensusObject> {

        private int highPhase1Rank;
        int highPhase2Rank;
        Object highPhase2Proposal;
        int epoch;

        T commitState = null;

        ConsensusKeeper(T initialState) { this.commitState = initialState; }

        Object getHighPhase2Proposal(int rank) {
            if (rank > highPhase1Rank) {
                highPhase1Rank = rank;
                return highPhase2Proposal;
            } else {
                return null;
            }
        }


        /**
         * @param rank
         * @param proposal
         * @return 0 means proposal accepted, -1 means it is rejected
         */
        int putHighPhase2Proposal(int epoch, int rank, Object proposal) {
            if (rank >= highPhase1Rank) {
                // accept proposal
                highPhase2Rank = highPhase1Rank = rank;
                highPhase2Proposal = proposal;
                // todo should we learn from the proposal if a higher epoch has been installed by other layout servers already?
                return 0;
            } else {
                return -1;
            }
        }

        void commitProposal(Object proposal) {
            commitState.apply(proposal);
        }
    }
}