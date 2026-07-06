package io.github.bszapp.wifitoolbox.service;

import android.net.wifi.ScanResult;
import io.github.bszapp.wifitoolbox.service.IMainServiceCallback;

interface IMainService {
    boolean connect();
    boolean isAlive();
    int getUid();
    String getUidStr();
    int getPid();
    String getStartupMode();
    String getStartupVersionName();
    long getStartupVersionCode();
    boolean startScan();
    List<ScanResult> getScanResults();
    byte[] getSavedWifiList();
    boolean isWifiEnabled();
    void setWifiEnabled(boolean enabled);
    boolean updateWifiConfig(int networkId, in byte[] patchBytes);
    void watchApp(IBinder token);
    void shutdown();
    void registerCallback(IMainServiceCallback cb);
    void unregisterCallback(IMainServiceCallback cb);
}
