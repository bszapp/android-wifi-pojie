package io.github.bszapp.wifitoolbox.service;

import android.os.ParcelFileDescriptor;
import io.github.bszapp.wifitoolbox.contract.androidapi.AndroidApiRequest;
import io.github.bszapp.wifitoolbox.contract.androidapi.AndroidApiResponse;
import io.github.bszapp.wifitoolbox.contract.startup.StartupInfo;
import io.github.bszapp.wifitoolbox.service.IMainServiceCallback;
import io.github.bszapp.wifitoolbox.service.IServiceLogCallback;
import io.github.bszapp.wifitoolbox.service.IContainerTerminalCallback;
import io.github.bszapp.wifitoolbox.service.ITerminalManagerCallback;

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
    int[] getWifiInformationSourceState();//TODO:这啥
    void setWifiInformationSource(int source, String rootfsPath, String runtimePath, String terminalPath);//TODO:为什么传这么多信息，服务不知道吗？下同

    void startContainerTerminal(String rootfsPath, String runtimePath, String terminalPath);
    void stopContainerTerminal();//TODO:不指定id就stop？
    void runContainerWifiScan();//TODO:这啥，有用吗
    void registerContainerTerminalCallback(IContainerTerminalCallback cb);
    void unregisterContainerTerminalCallback(IContainerTerminalCallback cb);

    long[] getAliveTerminalIds();
    long getAliveTerminalGeneration();
    int getTerminalLogCount(long terminalId);//TODO:Count？不应该只有日志id的范围吗
    long[] getTerminalLogRange(long terminalId);
    ParcelFileDescriptor getTerminalLogs(long terminalId, long fromIdInclusive, long toIdInclusive);
    oneway void clearTerminalLogs(long terminalId);//TODO:oneway是啥
    void registerTerminalManagerCallback(ITerminalManagerCallback cb);
    void unregisterTerminalManagerCallback(ITerminalManagerCallback cb);

    oneway void acknowledgeWifiState(IMainServiceCallback cb);
    oneway void acknowledgeSavedWifiList(IMainServiceCallback cb);

    void shutdown();
    void registerCallback(IMainServiceCallback cb);
    void unregisterCallback(IMainServiceCallback cb);
}
