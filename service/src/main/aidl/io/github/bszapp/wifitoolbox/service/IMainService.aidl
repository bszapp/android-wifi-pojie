package io.github.bszapp.wifitoolbox.service;

import android.os.ParcelFileDescriptor;
import io.github.bszapp.wifitoolbox.contract.androidapi.AndroidApiRequest;
import io.github.bszapp.wifitoolbox.contract.androidapi.AndroidApiResponse;
import io.github.bszapp.wifitoolbox.contract.startup.StartupInfo;
import io.github.bszapp.wifitoolbox.service.IMainServiceCallback;
import io.github.bszapp.wifitoolbox.service.IServiceLogCallback;

interface IMainService {
    void initializeStartupInfo(in StartupInfo startupInfo);
    boolean connect();
    boolean isAlive();
    StartupInfo getStartupInfo();
    AndroidApiResponse executeAndroidApi(in AndroidApiRequest request);

    long getLatestServiceLogId();
    ParcelFileDescriptor getServiceLogs(long fromIdInclusive, long toIdInclusive);
    oneway void clearServiceLogs();
    void registerServiceLogCallback(IServiceLogCallback cb);
    void unregisterServiceLogCallback(IServiceLogCallback cb);

    void refreshSavedWifiNetworks();
    boolean startWifiScan();

    oneway void acknowledgeWifiState(IMainServiceCallback cb);
    oneway void acknowledgeSavedWifiList(IMainServiceCallback cb);

    void shutdown();
    void registerCallback(IMainServiceCallback cb);
    void unregisterCallback(IMainServiceCallback cb);
}
