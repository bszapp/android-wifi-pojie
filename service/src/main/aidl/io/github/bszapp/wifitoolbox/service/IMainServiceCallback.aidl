package io.github.bszapp.wifitoolbox.service;

import android.os.ParcelFileDescriptor;

oneway interface IMainServiceCallback {
    void onWifiStateChanged(in ParcelFileDescriptor payload);
    void onSavedWifiListChanged(in ParcelFileDescriptor payload);
    void onServiceError(String source, String operation, String message, String details);
}
