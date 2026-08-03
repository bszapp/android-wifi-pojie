package io.github.bszapp.wifitoolbox.service;

oneway interface IMainServiceCallback {
    void onWifiStateChanged(long generation, int chunkCount, int totalBytes);
    void onSavedWifiListChanged(long generation, int chunkCount, int totalBytes);
    void onWifiInformationSourceStateChanged(long generation, int chunkCount, int totalBytes);
    void onMonitorPcapExported(String requestId, String path, String fileName);
    void onMonitorHandshakeTestResult(String requestId, int outcome);
    void onServiceError(String source, String operation, String message, String details);
}
