package org.voany.lib;

import org.junit.Test;
import static org.junit.Assert.*;
import static org.voany.lib.MobilityPolicy.Result.*;

public class MobilityPolicyTest {
    @Test public void losingAllNetworksRetainsSessionEvenAfterDeadline(){
        assertEquals(WAIT_FOR_NETWORK,MobilityPolicy.check(false,true,8,8,20000,10000));
    }
    @Test public void peerWithoutMobikeRebuildsImmediately(){
        assertEquals(REBUILD,MobilityPolicy.check(true,false,8,8,1,10000));
    }
    @Test public void oldRepliesDoNotConfirmANewPath(){
        assertEquals(WAIT_FOR_REPLY,MobilityPolicy.check(true,true,8,8,9999,10000));
        assertEquals(WAIT_FOR_REPLY,MobilityPolicy.check(true,true,8,7,9999,10000));
    }
    @Test public void authenticatedReplyConfirmsMigration(){
        assertEquals(VERIFIED,MobilityPolicy.check(true,true,8,9,500,10000));
    }
    @Test public void unresponsivePathRebuildsAtDeadline(){
        assertEquals(REBUILD,MobilityPolicy.check(true,true,8,8,10000,10000));
    }
}
