package io.github.bszapp.wifitoolbox.service;

oneway interface IServiceLogCallback {
    void onServiceLogRangeChanged(long oldestAvailableId, long latestId);
}
