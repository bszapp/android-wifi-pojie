package io.github.bszapp.wifitoolbox.service;
import android.os.ParcelFileDescriptor;

interface IHashcatCallback {
    oneway void onHashcatChanged(String taskId);
    oneway void onHashcatMemoryChanged();
    // 返回 true 之前 App 必须完成输入、恢复文件及任务快照的持久保存。
    boolean saveHashcatBackup(String taskId, in ParcelFileDescriptor backup);
}
