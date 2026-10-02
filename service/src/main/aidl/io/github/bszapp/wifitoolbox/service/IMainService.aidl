package io.github.bszapp.wifitoolbox.service;

import android.os.ParcelFileDescriptor;
import io.github.bszapp.wifitoolbox.contract.container.ContainerEnvironment;
import io.github.bszapp.wifitoolbox.contract.container.ContainerOperationRequest;
import io.github.bszapp.wifitoolbox.contract.container.ContainerState;
import io.github.bszapp.wifitoolbox.contract.androidapi.AndroidApiRequest;
import io.github.bszapp.wifitoolbox.contract.androidapi.AndroidApiResponse;
import io.github.bszapp.wifitoolbox.contract.startup.StartupInfo;
import io.github.bszapp.wifitoolbox.contract.task.TaskStartRequest;
import io.github.bszapp.wifitoolbox.contract.task.TaskSnapshot;
import io.github.bszapp.wifitoolbox.contract.task.TaskUpdateRequest;
import io.github.bszapp.wifitoolbox.service.IMainServiceCallback;
import io.github.bszapp.wifitoolbox.service.IServiceLogCallback;
import io.github.bszapp.wifitoolbox.service.ITerminalManagerCallback;
import io.github.bszapp.wifitoolbox.service.ITaskManagerCallback;
import io.github.bszapp.wifitoolbox.service.IContainerSystemCallback;

interface IMainService {
    void initializeStartupInfo(in StartupInfo startupInfo);
    boolean connect();
    boolean isAlive();
    StartupInfo getStartupInfo();
    AndroidApiResponse executeAndroidApi(in AndroidApiRequest request);

    void configureContainerSystem(in ContainerEnvironment environment);
    ContainerState getContainerState();
    boolean executeContainerOperation(in ContainerOperationRequest request, in ParcelFileDescriptor archive);
    void registerContainerSystemCallback(IContainerSystemCallback cb);
    void unregisterContainerSystemCallback(IContainerSystemCallback cb);

    long[] getServiceLogRange();
    long[] getSystemWifiLogRange();
    ParcelFileDescriptor getServiceLogs(long fromIdInclusive, long toIdInclusive);
    oneway void clearServiceLogs();
    ParcelFileDescriptor getSystemWifiLogs(long fromIdInclusive, long toIdInclusive);
    oneway void clearSystemWifiLogs();
    void registerServiceLogCallback(IServiceLogCallback cb);
    void unregisterServiceLogCallback(IServiceLogCallback cb);

    void refreshSavedWifiNetworks();
    int saveWifiNetwork(String ssid, String password);
    boolean startWifiScan();
    void setWifiMode(int source, String rootfsPath, String runtimePath, String terminalPath);//TODO:为什么传这么多信息，服务不知道吗？下同
    void configureWifiEnvironment(String rootfsPath, String runtimePath, String terminalPath);
    void setHybridScanEnabled(boolean enabled);
    void setMonitorCapture(boolean enabled, int frequencyMhz, boolean hopping);
    void clearMonitorCapture(boolean handshakesOnly);
    void enterMonitorMode(String command, String rootfsPath, String runtimePath, String terminalPath);
    ParcelFileDescriptor getMonitorChanges(long sessionGeneration, long afterRevision);
    void exportMonitorPcap(String requestId, String mode, String bssid, String deviceMac, in String[] subtypeIds);
    void exportMonitorHandshakePcap(String requestId, String bssid, String deviceMac, String handshakeId);
    void exportMonitorDisconnectionPcap(String requestId, String bssid, String deviceMac, String disconnectionId);
    oneway void releaseMonitorPcapExport(String path);

    void stopHybridScanner();
    //TODO:不指定id就stop？
    //TODO:这啥，有用吗

    long[] getAliveTerminalIds();
    long getAliveTerminalGeneration();
    //TODO:Count？不应该只有日志id的范围吗
    long[] getTerminalLogRange(long terminalId);
    String getTerminalInputPrompt(long terminalId);
    ParcelFileDescriptor getTerminalLogs(long terminalId, long fromIdInclusive, long toIdInclusive);
    void createServiceTerminal(String rootfsPath, String runtimePath, String terminalPath);
    oneway void clearTerminalLogs(long terminalId);//TODO:oneway是啥
    oneway void sendTerminalInput(long terminalId, String text);
    oneway void stopTerminal(long terminalId);
    void registerTerminalManagerCallback(ITerminalManagerCallback cb);
    void unregisterTerminalManagerCallback(ITerminalManagerCallback cb);

    long startTask(in TaskStartRequest request);
    boolean stopTask(long taskId);
    boolean updateTask(long taskId, in TaskUpdateRequest update);
    long getCurrentTaskId();
    TaskSnapshot getTaskSnapshot(long taskId);
    ParcelFileDescriptor getWpsCapturedNetworks(long taskId, int fromIndex);
    long[] getTaskLogRange(long taskId);
    ParcelFileDescriptor getTaskLogs(long taskId, long fromIdInclusive, long toIdInclusive);
    long[] getGlobalTaskLogRange();
    ParcelFileDescriptor getGlobalTaskLogs(long fromIdInclusive, long toIdInclusive);
    oneway void clearTaskLogs();
    void registerTaskManagerCallback(ITaskManagerCallback cb);
    void unregisterTaskManagerCallback(ITaskManagerCallback cb);

    ParcelFileDescriptor getWifiStateChunk(IMainServiceCallback cb, long generation, int chunkIndex);
    ParcelFileDescriptor getSavedWifiListChunk(IMainServiceCallback cb, long generation, int chunkIndex);
    ParcelFileDescriptor getWifiModeStateChunk(IMainServiceCallback cb, long generation, int chunkIndex);
    oneway void acknowledgeWifiState(IMainServiceCallback cb, long generation);
    oneway void acknowledgeSavedWifiList(IMainServiceCallback cb, long generation);
    oneway void acknowledgeWifiModeState(IMainServiceCallback cb, long generation);

    void shutdown();
    void registerCallback(IMainServiceCallback cb);
    void unregisterCallback(IMainServiceCallback cb);
}
