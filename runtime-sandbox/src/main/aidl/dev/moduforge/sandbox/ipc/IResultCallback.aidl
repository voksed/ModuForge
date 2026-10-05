package dev.moduforge.sandbox.ipc;

/** Completion of an asynchronous IPC call. Exactly one method is invoked, once. */
oneway interface IResultCallback {
    void onSuccess(String payload);

    void onFailure(String message);
}
