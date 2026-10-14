package org.voany.android;
oneway interface ISimStateListener {
    void onRegistrationChanged(int subscriptionId, String registration, boolean ready, boolean isolated);
}
