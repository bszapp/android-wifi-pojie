package io.github.bszapp.wifitoolbox.service;

oneway interface IContainerTerminalCallback {
    void onContainerTerminalEvent(String eventJson);
}
