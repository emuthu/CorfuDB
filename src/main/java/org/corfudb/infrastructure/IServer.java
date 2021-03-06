package org.corfudb.infrastructure;

import io.netty.channel.ChannelHandlerContext;
import org.corfudb.protocols.wireprotocol.CorfuMsg;

/**
 * Created by mwei on 12/4/15.
 */
public interface IServer {

    /** Handle a incoming Netty message.
     *
     * @param msg   An incoming message.
     * @param ctx   The channel handler context.
     * @param r     The router that took in the message.
     */
    void handleMessage(CorfuMsg msg, ChannelHandlerContext ctx, IServerRouter r);

    /** Reset the server.
     *
     */
    void reset();

    /** Shutdown the server.
     *
     */
    default void shutdown() {

    }
}
