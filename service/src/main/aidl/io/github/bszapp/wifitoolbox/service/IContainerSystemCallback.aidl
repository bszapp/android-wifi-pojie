package io.github.bszapp.wifitoolbox.service;

import io.github.bszapp.wifitoolbox.contract.container.ContainerState;

oneway interface IContainerSystemCallback {
    void onContainerStateChanged(in ContainerState state);
}
