package io.github.bszapp.wifitoolbox.service;

import io.github.bszapp.wifitoolbox.contract.androidapi.AndroidApiRequest;
import io.github.bszapp.wifitoolbox.contract.androidapi.AndroidApiResponse;
import io.github.bszapp.wifitoolbox.contract.startup.StartupInfo;
import io.github.bszapp.wifitoolbox.service.IMainServiceCallback;

interface IMainService {
    void initializeStartupInfo(in StartupInfo startupInfo);
    boolean connect();
    boolean isAlive();
    StartupInfo getStartupInfo();
    AndroidApiResponse executeAndroidApi(in AndroidApiRequest request);
    void shutdown();
    void registerCallback(IMainServiceCallback cb);
    void unregisterCallback(IMainServiceCallback cb);
}
