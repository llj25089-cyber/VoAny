package org.voany.android;
import android.os.Bundle;
import org.voany.android.ISimStateListener;
interface ISimBridge {
    List<Bundle> getSubscriptions() = 0;
    Bundle getSim(int subscriptionId) = 1;
    byte[] authenticate(int subscriptionId, in byte[] rand, in byte[] autn) = 2;
    byte[] authenticateIms(int subscriptionId, in byte[] rand, in byte[] autn) = 3;
    void watchRegistration(int subscriptionId, ISimStateListener listener) = 4;
    Bundle getRegistration(int subscriptionId) = 5;
    void destroy() = 16777114;
}
