package org.voany.android;

import android.net.*;
import org.junit.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import org.voany.android.net.*;

public class NetworkPathsTest {
    private NetworkCapabilities capability(boolean physical){NetworkCapabilities c=mock(NetworkCapabilities.class);when(c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true);when(c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)).thenReturn(physical);return c;}
    @Test public void skipsVpnAndLostPhysicalNetworkDuringOverlap(){
        ConnectivityManager cm=mock(ConnectivityManager.class);Network vpn=mock(Network.class),wifi=mock(Network.class),other=mock(Network.class);
        NetworkCapabilities virtual=capability(false),physical=capability(true);
        when(cm.getAllNetworks()).thenReturn(new Network[]{vpn,wifi,other});when(cm.getActiveNetwork()).thenReturn(vpn);
        when(cm.getNetworkCapabilities(vpn)).thenReturn(virtual);when(cm.getNetworkCapabilities(wifi)).thenReturn(physical);when(cm.getNetworkCapabilities(other)).thenReturn(physical);
        assertSame(wifi,NetworkPaths.select(cm,null,wifi));assertSame(other,NetworkPaths.select(cm,wifi,wifi));
    }
    @Test public void prefersNewDefaultPhysicalPathAndWaitsIfNoneRemain(){
        ConnectivityManager cm=mock(ConnectivityManager.class);Network first=mock(Network.class),next=mock(Network.class);
        NetworkCapabilities physical=capability(true);
        when(cm.getAllNetworks()).thenReturn(new Network[]{first,next});when(cm.getNetworkCapabilities(first)).thenReturn(physical);when(cm.getNetworkCapabilities(next)).thenReturn(physical);when(cm.getActiveNetwork()).thenReturn(next);
        assertSame(next,NetworkPaths.select(cm,null,first));when(cm.getAllNetworks()).thenReturn(new Network[]{next});assertNull(NetworkPaths.select(cm,next,first));
    }
}
