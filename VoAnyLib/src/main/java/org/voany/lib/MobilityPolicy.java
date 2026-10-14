package org.voany.lib;

/** A network outage pauses migration; its timeout starts once a new path exists. */
public final class MobilityPolicy {
    public enum Result { WAIT_FOR_NETWORK, WAIT_FOR_REPLY, VERIFIED, REBUILD }
    public static Result check(boolean network,boolean supported,long before,long replies,long now,long deadline){
        if(!network)return Result.WAIT_FOR_NETWORK;
        if(!supported)return Result.REBUILD;
        if(replies>before)return Result.VERIFIED;
        return now>=deadline ? Result.REBUILD : Result.WAIT_FOR_REPLY;
    }
    private MobilityPolicy(){}
}
