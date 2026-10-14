package org.voany.lib;
import org.junit.Test;
import static org.junit.Assert.*;
public class NetworkPolicyTest {
    @Test public void onlyReadyUnregisteredSimMayConnect(){assertTrue(NetworkPolicy.mayConnect("NOT_REGISTERED",true));assertFalse(NetworkPolicy.mayConnect("NOT_REGISTERED",false));}
    @Test public void homeAndRoamingAlwaysBlock(){for(String s:new String[]{"HOME","ROAMING","HOME/ROAMING"}){assertTrue(NetworkPolicy.registered(s));assertFalse(NetworkPolicy.mayConnect(s,true));}}
    @Test public void unknownAndAbsentStatesFailClosed(){for(String s:new String[]{"UNKNOWN","",null})assertFalse(NetworkPolicy.mayConnect(s,true));}
}
