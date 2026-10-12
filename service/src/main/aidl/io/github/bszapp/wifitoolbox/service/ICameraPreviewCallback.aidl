package io.github.bszapp.wifitoolbox.service;

oneway interface ICameraPreviewCallback {
    void onRunning(String sessionId);
    void onError(String sessionId, String message);
}
