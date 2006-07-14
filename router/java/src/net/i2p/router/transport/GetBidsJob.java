package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.List;
import net.i2p.data.Hash;
import net.i2p.router.JobImpl;
import net.i2p.router.MessageSelector;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Retrieve a set of bids for a particular outbound message, and if any are found
 * that meet the message's requirements, register the message as in process and
 * pass it on to the transport for processing
 *
 */
public class GetBidsJob extends JobImpl {
    private Log _log;
    private CommSystemFacadeImpl _facade;
    private OutNetMessage _msg;
    
    public GetBidsJob(RouterContext ctx, CommSystemFacadeImpl facade, OutNetMessage msg) {
        super(ctx);
        _log = ctx.logManager().getLog(GetBidsJob.class);
        _facade = facade;
        _msg = msg;
    }
    
    public String getName() { return "Fetch bids for a message to be delivered"; }
    public void runJob() {
        getBids(getContext(), _facade, _msg);
    }
    
    static void getBids(RouterContext context, CommSystemFacadeImpl facade, OutNetMessage msg) {
        Log log = context.logManager().getLog(GetBidsJob.class);
        Hash to = msg.getTarget().getIdentity().getHash();
        msg.timestamp("bid");
        
        if (context.shitlist().isShitlisted(to)) {
            if (log.shouldLog(Log.WARN))
                log.warn("Attempt to send a message to a shitlisted peer - " + to);
            //context.messageRegistry().peerFailed(to);
            fail(context, msg);
            return;
        }
        
        Hash us = context.routerHash();
        if (to.equals(us)) {
            if (log.shouldLog(Log.ERROR))
                log.error("wtf, send a message to ourselves?  nuh uh. msg = " + msg);
            fail(context, msg);
            return;
        }
        
        TransportBid bid = facade.getNextBid(msg);
        if (bid == null) {
            int failedCount = msg.getFailedTransports().size();
            if (failedCount == 0) {
                context.shitlist().shitlistRouter(to, "We share no common transports with them");
            } else if (failedCount >= facade.getTransportCount()) {
                // fail after all transports were unsuccessful
                context.netDb().fail(to);
            }
            fail(context, msg);
        } else {
            if (log.shouldLog(Log.INFO))
                log.info("Attempting to send on transport " + bid.getTransport().getStyle() + ": " + bid);
            bid.getTransport().send(msg);
        }
    }
    
    
    private static void fail(RouterContext context, OutNetMessage msg) {
        if (msg.getOnFailedSendJob() != null) {
            context.jobQueue().addJob(msg.getOnFailedSendJob());
        }
        if (msg.getOnFailedReplyJob() != null) {
            context.jobQueue().addJob(msg.getOnFailedReplyJob());
        }
        MessageSelector selector = msg.getReplySelector();
        if (selector != null) {
            context.messageRegistry().unregisterPending(msg);
        }
        
        context.profileManager().messageFailed(msg.getTarget().getIdentity().getHash());
        
        msg.discardData();
    }
}
