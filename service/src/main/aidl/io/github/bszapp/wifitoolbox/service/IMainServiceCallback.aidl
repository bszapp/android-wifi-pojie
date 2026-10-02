package io.github.bszapp.wifitoolbox.service;

oneway interface IMainServiceCallback {
    void onWifiStateChanged(long generation, int chunkCount, int totalBytes);
    void onSavedWifiListChanged(long generation, int chunkCount, int totalBytes);
    void onWifiModeStateChanged(long generation, int chunkCount, int totalBytes);
    void onMonitorRecordedBytesChanged(long sessionGeneration, long recordedBytes);
    void onMonitorPcapExported(String requestId, String path, String fileName);
    void onServiceError(String source, String operation, String message, String details);
}
