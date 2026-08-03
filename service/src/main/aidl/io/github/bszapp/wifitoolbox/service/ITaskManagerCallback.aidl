package io.github.bszapp.wifitoolbox.service;

oneway interface ITaskManagerCallback {
    void onTaskManagerChanged(
        long currentTaskId,
        long changedTaskId
    );
    void onTaskLogRangeChanged(
        long taskId,
        long generation,
        long oldestAvailableId,
        long latestId,
        int lineCount
    );
    void onGlobalTaskLogRangeChanged(
        long generation,
        long oldestAvailableId,
        long latestId,
        int lineCount
    );
}
