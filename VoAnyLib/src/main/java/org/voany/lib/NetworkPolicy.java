package org.voany.lib;

/** Fail closed whenever WWAN registration cannot be established. */
public final class NetworkPolicy {
    public static boolean mayConnect(String registration,boolean ready){return ready && "NOT_REGISTERED".equals(registration);}
    public static boolean registered(String registration){return "HOME".equals(registration) || "ROAMING".equals(registration) || "HOME/ROAMING".equals(registration);}
    private NetworkPolicy(){}
}
