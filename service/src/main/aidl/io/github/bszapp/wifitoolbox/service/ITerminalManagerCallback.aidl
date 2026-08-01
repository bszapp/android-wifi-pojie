package io.github.bszapp.wifitoolbox.service;

oneway interface ITerminalManagerCallback {
    void onAliveTerminalIdsChanged(long generation, in long[] terminalIds);
    void onTerminalLogRangeChanged(
        long terminalId,
        long generation,
        long oldestAvailableId,
        long latestId,
        int lineCount
    );
}
