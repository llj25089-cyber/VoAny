/*
 * Copyright (C) 2012-2019 Tobias Brunner
 *
 * Copyright (C) secunet Security Networks AG
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.  See <http://www.fsf.org/copyleft/gpl.txt>.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * for more details.
 */

package org.strongswan.android.logic;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.os.Build;

import java.util.LinkedList;

public class NetworkManager extends BroadcastReceiver implements Runnable
{
	private final Context mContext;
	private volatile boolean mRegistered;
	private ConnectivityManager.NetworkCallback mCallback;
	private Thread mEventNotifier;
    private static volatile NetworkManager activeManager;
    public static void requestPathCheck(){
        NetworkManager manager=activeManager;
        if(manager!=null)synchronized(manager){if(manager.mRegistered){manager.mEvents.clear();manager.mEvents.addLast(org.voany.android.net.Lwip.underlying!=null);manager.notifyAll();}}
    }
	private LinkedList<Boolean> mEvents = new LinkedList<>();

	public NetworkManager(Context context)
	{
		mContext = context;

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
		{
			mCallback = new ConnectivityManager.NetworkCallback()
			{
                @Override public void onLinkPropertiesChanged(Network network,LinkProperties properties){queuePathChange(null);}
                @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities capabilities){queuePathChange(null);}
				@Override
				public void onAvailable(Network network)
				{
                    queuePathChange(null);
				}

				@Override
				public void onLost(Network network)
				{
                    queuePathChange(network);
				}
			};
		}
	}

    private void queuePathChange(Network excluded){
        ConnectivityManager cm=mContext.getSystemService(ConnectivityManager.class);
        Network selected=org.voany.android.net.NetworkPaths.select(cm,excluded);
        org.voany.android.net.Lwip.underlying=selected;
        synchronized(this){mEvents.clear();mEvents.addLast(selected!=null);notifyAll();}
    }

	public void Register()
	{
		mEvents.clear();
		mRegistered = true;
        activeManager=this;
		mEventNotifier = new Thread(this);
		mEventNotifier.start();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
		{
			ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);
			/* while we only get events for the VPN network via registerDefaultNetworkCallback,
			 * the default capabilities in the builder include NetworkCapabilities.NET_CAPABILITY_NOT_VPN */
			NetworkRequest.Builder builder = new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
			cm.registerNetworkCallback(builder.build(), mCallback);
		}
		else
		{
			registerLegacyReceiver();
		}
	}

	@SuppressWarnings("deprecation")
	private void registerLegacyReceiver()
	{
		/* deprecated since API level 28 */
		mContext.registerReceiver(this, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
	}

	public void Unregister()
	{
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
		{
			ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);
			cm.unregisterNetworkCallback(mCallback);
		}
		else
		{
			mContext.unregisterReceiver(this);
		}
		mRegistered = false;
        if(activeManager==this)activeManager=null;
		synchronized (this)
		{
			notifyAll();
		}
		try
		{
			mEventNotifier.join();
			mEventNotifier = null;
		}
		catch (InterruptedException e)
		{
			e.printStackTrace();
		}
	}

	@SuppressWarnings("deprecation")
	public boolean isConnected()
	{
		/* deprecated since API level 29 */
		ConnectivityManager cm = (ConnectivityManager)mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm!=null && org.voany.android.net.NetworkPaths.select(cm,null)!=null;
	}

	@Override
	public void onReceive(Context context, Intent intent)
	{
		synchronized (this)
		{
			mEvents.addLast(isConnected());
			notifyAll();
		}
	}

	@Override
	public void run()
	{
		while (mRegistered)
		{
			boolean connected;

			synchronized (this)
			{
				try
				{
					while (mRegistered && mEvents.isEmpty())
					{
						wait();
					}
				}
				catch (InterruptedException ex)
				{
					break;
				}
				if (!mRegistered)
				{
					break;
				}
				connected = mEvents.removeFirst();
			}
			/* call the native parts without holding the lock */
			networkChanged(!connected);
		}
	}

	/**
	 * Notify the native parts about a network change
	 *
	 * @param disconnected true if no connection is available at the moment
	 */
	public native void networkChanged(boolean disconnected);
}
