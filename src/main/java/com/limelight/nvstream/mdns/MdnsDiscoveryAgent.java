package com.limelight.nvstream.mdns;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.limelight.LimeLog;

public class MdnsDiscoveryAgent implements NsdManager.DiscoveryListener {
	public static final String SERVICE_TYPE = "_nvstream._tcp";
	
	private MdnsDiscoveryListener listener;
	private NsdManager nsdManager;
	private HashMap<InetAddress, MdnsComputer> computers = new HashMap<>();
	private Thread resolveThread;
	private LinkedBlockingQueue<NsdServiceInfo> pendingResolution = new LinkedBlockingQueue<>();
	private boolean started = false;
	
	public MdnsDiscoveryAgent(Context context, MdnsDiscoveryListener listener) {
		this.listener = listener;
		this.nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
	}
	
	public void startDiscovery() {
		if (!started) {
			pendingResolution.clear();

			resolveThread = new Thread() {
				@Override
				public void run() {
					while (!isInterrupted()) {
						try {
							nsdManager.resolveService(pendingResolution.poll(Long.MAX_VALUE, TimeUnit.DAYS),
									new NsdManager.ResolveListener() {
								@Override
								public void onResolveFailed(final NsdServiceInfo serviceInfo, int errorCode) {
									LimeLog.info("mDNS: Failed to resolve "+serviceInfo.getServiceName()+": "+errorCode);
									new Handler().postDelayed(new Runnable() {
										@Override
										public void run() {
											if (started) {
												LimeLog.info("mDNS: Retrying resolution for "+serviceInfo.getServiceName());
												pendingResolution.offer(serviceInfo);
											}
										}
									}, 1000);
								}

								@Override
								public void onServiceResolved(NsdServiceInfo serviceInfo) {
									LimeLog.info("mDNS: Machine resolved: "+serviceInfo.getServiceName()+" -> "+serviceInfo.getHost());
									MdnsComputer computer = new MdnsComputer(serviceInfo.getServiceName(), serviceInfo.getHost());
									if (computers.put(computer.getAddress(), computer) == null) {
										// This was a new entry
										listener.notifyComputerAdded(computer);
									}
								}
							});
						} catch (InterruptedException e) {
							break;
						}
					}
				}
			};
			resolveThread.setName("mDNS Resolver Thread");
			resolveThread.start();

			nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, this);

			started = true;
		}
	}
	
	public void stopDiscovery() {
		if (started) {
			nsdManager.stopServiceDiscovery(this);

			resolveThread.interrupt();
			try {
				resolveThread.join();
			} catch (InterruptedException e) {}

			started = false;
		}
	}
	
	public List<MdnsComputer> getComputerSet() {
		synchronized (computers) {
			return new ArrayList<>(computers.values());
		}
	}

	@Override
	public void onStartDiscoveryFailed(String serviceType, int errorCode) {
		LimeLog.warning("mDNS: Failed to start service discovery: "+errorCode);
	}

	@Override
	public void onStopDiscoveryFailed(String serviceType, int errorCode) {
		LimeLog.warning("mDNS: Failed to stop service discovery: "+errorCode);
	}

	@Override
	public void onDiscoveryStarted(String serviceType) {
		LimeLog.info("mDNS: Discovery started");
	}

	@Override
	public void onDiscoveryStopped(String serviceType) {
		LimeLog.info("mDNS: Discovery stopped");
	}

	@Override
	public void onServiceFound(NsdServiceInfo serviceInfo) {
		LimeLog.info("mDNS: Machine appeared: "+serviceInfo.getServiceName());
		pendingResolution.offer(serviceInfo);
	}

	@Override
	public void onServiceLost(NsdServiceInfo serviceInfo) {
		LimeLog.info("mDNS: Machine disappeared: "+serviceInfo.getServiceName());
	}
}
